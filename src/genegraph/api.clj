(ns genegraph.api
  (:require [genegraph.framework.app :as app]
            [genegraph.framework.protocol :as p]
            [genegraph.framework.event :as event]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.env :as env]
            [genegraph.api.dosage :as dosage] 
            [genegraph.api.base :as base]
            [genegraph.api.graphql.schema :as gql-schema]
            [genegraph.api.graphql.response-cache :as response-cache]
            [genegraph.api.base.clinvar]
            [genegraph.api.names]
            [genegraph.api.base.gff]
            [genegraph.api.assertion-annotation :as ac]
            [com.walmartlabs.lacinia.pedestal2 :as lacinia-pedestal]
            [com.walmartlabs.lacinia.pedestal.internal :as internal]
            [io.pedestal.http :as http]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.log :as log]
            [clojure.java.io :as io]
            [clojure.set :as set])
  (:import [org.apache.jena.sparql.core Transactional]
           [org.apache.jena.query ReadWrite]
           [org.apache.jena.rdf.model Model]
           [java.time Instant]
           [java.util.concurrent Executor])
  (:gen-class))

;; TODO need to convert hgnc gene IDs from GV SEPIO to NCBIGENE ids

;; stuff to make sure Lacinia recieves an executor which can bookend
;; database transactions

(def direct-executor
  (reify Executor
    (^void execute [this ^Runnable r]
     (.run r))))

;; Environments

(def admin-env
  (if (or (System/getenv "DX_JAAS_CONFIG_DEV")
          (System/getenv "DX_JAAS_CONFIG")) ; prevent this in cloud deployments
    {:platform "stage"
     :dataexchange-genegraph (System/getenv "DX_JAAS_CONFIG")
     :local-data-path "data/"}
    {}))

(def local-env
  (case (or (:platform admin-env) (System/getenv "GENEGRAPH_PLATFORM"))
    "local" {:fs-handle {:type :file :base "data/base/"}
             :local-data-path "data/"
             :graphql-schema (fn []
                               (gql-schema/merged-schema
                                {:executor direct-executor}))}
    "dev" (assoc (env/build-environment "522856288592" ["dataexchange-genegraph"])
                 :version 1
                 :name "dev"
                 :function (System/getenv "GENEGRAPH_FUNCTION")
                 :kafka-user "User:2189780"
                 :fs-handle {:type :gcs
                             :bucket "genegraph-framework-dev"}
                 :local-data-path "/data"
                 :graphql-schema (gql-schema/merged-schema
                                  {:executor direct-executor}))
    "stage" (assoc (env/build-environment "583560269534" ["dataexchange-genegraph"])
                   :version 1
                   :name "stage"
                   :function (System/getenv "GENEGRAPH_FUNCTION")
                   :kafka-user "User:2592237"
                   :fs-handle {:type :gcs
                               :bucket "genegraph-api-stage-1"}
                   :local-data-path "/data"
                   :graphql-schema (gql-schema/merged-schema
                                    {:executor direct-executor}))
    "prod" (assoc (env/build-environment "974091131481" ["dataexchange-genegraph"])
                  :function (System/getenv "GENEGRAPH_FUNCTION")
                  :version 7
                  :name "prod"
                  :kafka-user "User:2592237"
                  :fs-handle {:type :gcs
                              :bucket "genegraph-api-prod-1"}
                  :local-data-path "/data"
                  :graphql-schema (gql-schema/merged-schema
                                   {:executor direct-executor}))
    {}))

(def env
  (merge local-env admin-env))

(defn qualified-kafka-name [prefix]
  (str prefix "-" (:name env) "-" (:version env)))

(def consumer-group
  (qualified-kafka-name "gg"))

;; Topics

(def clinvar-curation-topic
  {:name :clinvar-curation
   :type :kafka-reader-topic
   :serialization :json
   :create-producer true
   :kafka-cluster :data-exchange
   :kafka-topic (qualified-kafka-name "ggapi-clinvar-curation")
   :kafka-topic-config {}})


(def fetch-base-events-topic
  {:name :fetch-base-events
   :serialization :edn
   :kafka-cluster :data-exchange
   :kafka-topic (qualified-kafka-name "ggapi-fb")
   :kafka-topic-config {"cleanup.policy" "compact"
                        "delete.retention.ms" "100"}})

(def base-data-topic
  {:name :base-data
   :serialization :edn
   :kafka-cluster :data-exchange
   :kafka-topic (qualified-kafka-name "ggapi-base")
   :kafka-topic-config {"cleanup.policy" "compact"
                        "delete.retention.ms" "100"}})

(def gene-validity-sepio-topic 
  {:name :gene-validity-sepio
   :kafka-cluster :data-exchange
   :serialization ::rdf/n-triples
   :kafka-topic "gg-gvs2-stage-1"
   :kafka-topic-config {}})

(def api-log-topic
  {:name :api-log
   :kafka-cluster :data-exchange
   :serialization :edn
   :create-producer true
   :kafka-topic (qualified-kafka-name "ggapi-apilog")
   :kafka-topic-config {"retention.ms"
                        (str (* 1000 60 60 24 14))}}) ; 2 wk retention

(def dosage-topic
  {:name :dosage
   :kafka-cluster :data-exchange
   :serialization :json
   :kafka-topic "gene_dosage_raw"})

;; /Topics

;; Interceptors for reader

(def prop-query
  (rdf/create-query "select ?prop where { ?prop a :cg/GeneValidityProposition } "))

(def same-as-query
  (rdf/create-query "select ?x where { ?x :owl/sameAs ?y }"))

;; Jena methods mutate the model, will use this behavior 😱
(defn replace-hgnc-with-ncbi-gene-fn [event]
  (rdf/tx (get-in event [::storage/storage :api-tdb])
      (let [m (::event/data event)
            prop (first (prop-query m))
            hgnc-gene (rdf/ld1-> prop [:cg/gene])
            ncbi-gene (first (same-as-query (get-in event [::storage/storage :api-tdb])
                                            {:y hgnc-gene}))]
        (.remove m (rdf/construct-statement [prop :cg/gene hgnc-gene]))
        (.add m (rdf/construct-statement [prop :cg/gene ncbi-gene]))))
  event)

(def replace-hgnc-with-ncbi-gene
  (interceptor/interceptor
   {:name ::replace-hgnc-with-ncbi-gene
    :enter (fn [e] (replace-hgnc-with-ncbi-gene-fn e))}))

(defn has-publish-action [m]
  (< 0 (count ((rdf/create-query "select ?x where { ?x :cg/role :cg/Publisher } ") m))))

(defn prop-iri [event]
  (-> event ::event/data prop-query first str))

(defn store-curation-fn [event]
  (if (has-publish-action (::event/data event))
    (event/store event :api-tdb (prop-iri event) (::event/data event))
    (event/delete event :api-tdb (prop-iri event))))

(def store-curation
  (interceptor/interceptor
   {:name ::store-curation
    :enter (fn [e] (store-curation-fn e))}))

(def jena-transaction-interceptor
  (interceptor/interceptor
   {:name ::jena-transaction-interceptor
    :enter (fn [context]
             (let [api-tdb (get-in context [::storage/storage :api-tdb])
                   object-db (get-in context [::storage/storage :object-db])]
               (.begin api-tdb ReadWrite/READ)
               (-> context
                   (assoc-in [:request :lacinia-app-context :tdb]
                             api-tdb)
                   (assoc-in [:request :lacinia-app-context :object-db]
                             object-db))))
    :leave (fn [context]
             (.end (get-in context [::storage/storage :api-tdb]))
             context)
    :error (fn [context ex]
             (.end (get-in context [::storage/storage :api-tdb]))
             context)}))

(defn init-graphql-processor [p]
  (assoc-in p
            [::event/metadata ::schema]
            (:graphql-schema env)))

(defn fn->schema [fn-or-schema]
  (if (fn? fn-or-schema)
    (fn-or-schema)
    fn-or-schema))

;; Adapted from version in lacinia-pedestal
;; need to get compiled schema from context, not
;; already passed into interceptor

(def query-parser-interceptor
  (interceptor/interceptor
   {:name ::query-parser
    :enter (fn [context]
             (internal/on-enter-query-parser
              context
              (fn->schema (::schema context))
              (::query-cache context)
              (get-in context [:request ::timing-start])))
    :leave internal/on-leave-query-parser
    :error internal/on-error-query-parser}))

(defn publish-record-to-system-topic-fn [event]
  (event/publish event
                 {::event/topic :system
                  :type :event-marker
                  ::event/data (assoc (select-keys event [::event/key])
                                      :source (::event/topic event))}))

(def publish-record-to-system-topic
  (interceptor/interceptor
   {:name ::publish-record-to-system-topic
    :leave (fn [e] (publish-record-to-system-topic-fn e))}))

;;;; Application config

;; Kafka

(def data-exchange
  {:type :kafka-cluster
   :kafka-user (:kafka-user env)
   :common-config {"ssl.endpoint.identification.algorithm" "https"
                   "sasl.mechanism" "PLAIN"
                   "request.timeout.ms" "20000"
                   "bootstrap.servers" "pkc-4yyd6.us-east1.gcp.confluent.cloud:9092"
                   "retry.backoff.ms" "500"
                   "security.protocol" "SASL_SSL"
                   "sasl.jaas.config" (:dataexchange-genegraph env)}
   :consumer-config {"key.deserializer"
                     "org.apache.kafka.common.serialization.StringDeserializer"
                     "value.deserializer"
                     "org.apache.kafka.common.serialization.StringDeserializer"}
   :producer-config {"key.serializer"
                     "org.apache.kafka.common.serialization.StringSerializer"
                     "value.serializer"
                     "org.apache.kafka.common.serialization.StringSerializer"}})

;;;; Base

(def fetch-base-processor
  {:name :fetch-base-file
   :type :processor
   :subscribe :fetch-base-events
   :interceptors [base/fetch-file
                  base/publish-base-file]
   ::event/metadata {::base/handle
                     (assoc (:fs-handle env) :path "base/")}})

;;;; GraphQL

(def api-tdb
  {:type :rdf
   :name :api-tdb
   :snapshot-handle (assoc (:fs-handle env) :path "api-tdb-v3.nq.gz")
   :path (str (:local-data-path env) "/api-tdb")})

(def object-db
  {:type :rocksdb
   :name :object-db
   :snapshot-handle (assoc (:fs-handle env) :path "object-db-v3.lz4")
   :path (str (:local-data-path env) "/object-db")})

(def sequence-feature-db
  {:type :rocksdb
   :name :sequence-feature-db
   :snapshot-handle (assoc (:fs-handle env) :path "sequence-feature-db.lz4")
   :path (str (:local-data-path env) "/sequence-feature-db")})

(def response-cache-db
  {:type :rocksdb
   :name :response-cache-db
   :path (str (:local-data-path env) "/response-cache-db")})

(def import-base-processor
  {:name :import-base-file
   :type :processor
   :subscribe :base-data
   :backing-store :api-tdb
   :interceptors [publish-record-to-system-topic
                  base/base-event
                  response-cache/invalidate-cache]})

(def read-clinvar-curations
  {:name :read-clinvar-curation
   :type :processor
   :backing-store :api-tdb
   :subscribe :clinvar-curation
   :interceptors [ac/process-annotation]})

(def genes-graph-name
  "https://www.genenames.org/")

(defn init-await-genes [listener-name]
  (fn [p]
    (let [genes-promise (promise)]
      (p/publish (get-in p [:topics :system])
                 {:type :register-listener
                  :name listener-name
                  :promise genes-promise
                  :predicate #(and (= :base-data (get-in % [::event/data :source]))
                                   (= genes-graph-name
                                      (get-in % [::event/data ::event/key])))})

      (assoc p
             ::event/metadata
             {::genes-promise genes-promise
              ::genes-atom (atom false)}))))

(defn graph-initialized? [e graph-name]
  (let [db (get-in e [::storage/storage :api-tdb])]
    (rdf/tx db
      (-> (storage/read db graph-name)
          .size
          (> 0)))))

(defn await-genes-fn [{:keys [::genes-promise ::genes-atom ::event/kafka-topic] :as e}]
  (when-not @genes-atom
    (while (not
            (or (graph-initialized? e genes-graph-name)
                (not= :timeout (deref genes-promise (* 1000 30) :timeout))))
      (log/info :fn ::await-genes-fn
                :msg "Awaiting genes load"
                :topic kafka-topic))
    (log/info :fn ::await-genes-fn :msg "Genes loaded")
    (reset! genes-atom true))
  e)

(def await-genes
  (interceptor/interceptor
   {:name ::await-genes
    :enter (fn [e] (await-genes-fn e))}))

(def import-gv-curations
  {:type :processor
   :subscribe :gene-validity-sepio
   :name :gene-validity-sepio-reader
   :backing-store :api-tdb
   :init-fn (init-await-genes ::import-gv-curations-await-genes)
   :interceptors [await-genes
                  replace-hgnc-with-ncbi-gene
                  store-curation
                  response-cache/invalidate-cache]})

(def import-dosage-curations
  {:type :processor
   :subscribe :dosage
   :name :import-dosage-curations
   :backing-store :api-tdb
   :interceptors [dosage/add-dosage-model
                  dosage/write-dosage-model-to-db
                  dosage/add-dosage-region
                  dosage/add-dosage-indexes
                  response-cache/invalidate-cache]})

(def query-timer-interceptor
  (interceptor/interceptor
   {:name ::query-timer-interceptor
    :enter (fn [e] (assoc e ::start-time (.toEpochMilli (Instant/now))))
    :leave (fn [e] (assoc e ::end-time (.toEpochMilli (Instant/now))))}))

(defn publish-result-fn [e]
  (event/publish
   e
   {::event/data {:start-time (::start-time e)
                  :end-time (::end-time e)
                  :query (get-in e [:request :body])
                  :remote-addr (get-in e [:request :remote-addr])
                  :response-size (count (get-in e [:response :body]))
                  :status (get-in e [:response :status])
                  :handled-by (::event/handled-by e)
                  :error-message (::error-message e)}
    ::event/key (str (::start-time e))
    ::event/topic :api-log}))

(def publish-result-interceptor
  (interceptor/interceptor
   {:name ::publish-result
    :leave (fn [e] (publish-result-fn e))}))

(defn report-error-interceptor-fn [e]
  (if-let [errors (seq (get-in e [:response :body :errors]))]
    (assoc e ::error-message (mapv :message errors))
    e))


(def report-error-interceptor
  (interceptor/interceptor
   {:name ::report-error
    :leave (fn [e] (report-error-interceptor-fn
                    (assoc e ::status :ok)))
    :error (fn [e] (report-error-interceptor-fn
                    (assoc e ::status :error)))}))

(def inspect-event-interceptor
  (interceptor/interceptor
   {:name ::inspect-event
    :enter (fn [e] (tap> (assoc e ::on :enter)) e)
    :leave (fn [e] (tap> (assoc e ::on :leave)) e)
    :error (fn [e] (tap> (assoc e ::on :error)) e)}))

;; If any prior interceptors ever have side effects, this may
;; wipe them out. But that should never happen...
(defn enter-graphql-mutation-effects [e]
  (assoc-in e [:request :lacinia-app-context :effects] (atom {})))

(defn leave-graphql-mutation-effects [e]
#_  (tap> @(get-in e [:request :lacinia-app-context :effects]))
  (merge e @(get-in e [:request :lacinia-app-context :effects])))

(def graphql-mutation-effects-interceptor
  (interceptor/interceptor
   {:name ::graphql-mutation-effects
    :enter (fn [e] (enter-graphql-mutation-effects e))
    :leave (fn [e] (leave-graphql-mutation-effects e))}))

(def graphql-api
  {:name :graphql-api
   :type :processor
   :interceptors [#_lacinia-pedestal/initialize-tracing-interceptor
                  publish-result-interceptor
                  query-timer-interceptor
                  lacinia-pedestal/body-data-interceptor
                  #_response-cache/response-cache
                  jena-transaction-interceptor
                  lacinia-pedestal/json-response-interceptor
                  report-error-interceptor
                  lacinia-pedestal/error-response-interceptor
                  lacinia-pedestal/graphql-data-interceptor
                  lacinia-pedestal/status-conversion-interceptor
                  lacinia-pedestal/missing-query-interceptor
                  query-parser-interceptor
                  lacinia-pedestal/disallow-subscriptions-interceptor
                  lacinia-pedestal/prepare-query-interceptor
                  #_lacinia-pedestal/enable-tracing-interceptor
                  #_inspect-event-interceptor
                  graphql-mutation-effects-interceptor
                  lacinia-pedestal/query-executor-handler]
   :init-fn init-graphql-processor})

(def type-query
  (rdf/create-query "select ?x where { ?x a ?type . } "))

(defn gv-ready-fn [e]
  (let [tdb (get-in e [::storage/storage :api-tdb])
        type-count (fn [t]
                     (count (type-query tdb {:type t})))
        in-tx (.isInTransaction tdb)]
    (try
      (when-not in-tx
        (.begin tdb ReadWrite/READ))
      (let [gv-count (type-count
                      :sepio/GeneValidityEvidenceLevelAssertion)
            ac-count (type-count :sepio/ActionabilityReport)
            gd-count (type-count :sepio/GeneDosageReport)]
        (.commit tdb) ; https://github.com/apache/jena/issues/2584
        (.end tdb)
        #_(log/info :fn ::gv-ready-fn
                  :gv-count gv-count
                  :ac-count ac-count
                  :gd-count gd-count)
        (assoc e
               :response
               (if (and (< 2700 gv-count)
                        (< 200 ac-count)
                        (< 2000 gd-count))
                 {:status 200 :body "ready"}
                 {:status 500 :body "not ready"}))))))


(defn ready-fn [e]
  (assoc e
         :response
         {:status 200 :body "ready"}))

(def graphql-ready-interceptor
  (interceptor/interceptor
   {:name :graphql-ready
    :enter (fn [e] (ready-fn e))}))

(def graphql-ready
  {:name :graphql-ready
   :type :processor
   :interceptors [graphql-ready-interceptor]})

(def http-server
  {:gene-validity-server
   {:type :http-server
    :name :gene-validity-server
    :endpoints [{:path "/api"
                 :processor :graphql-api
                 :method :post}
                {:path "/ready"
                 :processor :graphql-ready
                 :method :get}]
    ::http/host "0.0.0.0"
    ::http/allowed-origins {:allowed-origins (constantly true)
                            :creds true}
    ::http/routes
    (conj
     (lacinia-pedestal/graphiql-asset-routes "/assets/graphiql")
     ["/ide" :get (lacinia-pedestal/graphiql-ide-handler {})
      :route-name ::lacinia-pedestal/graphql-ide]
     #_["/ready"
      :get (fn [_] {:status 200 :body "server is ready"})
      :route-name ::readiness]
     ["/live"
      :get (fn [_] {:status 200 :body "server is live"})
      :route-name ::liveness])
    ::http/type :jetty
    ::http/port 8888
    ::http/join? false
    ::http/secure-headers nil}})

(def ready-server
  {:gene-validity-server
   {:type :http-server
    :name :ready-server
    ::http/host "0.0.0.0"
    ::http/allowed-origins {:allowed-origins (constantly true)
                            :creds true}
    ::http/routes
    [["/ready"
      :get (fn [_] {:status 200 :body "server is ready"})
      :route-name ::readiness]
     ["/live"
      :get (fn [_] {:status 200 :body "server is live"})
      :route-name ::liveness]]
    ::http/type :jetty
    ::http/port 8888
    ::http/join? false
    ::http/secure-headers nil}})

(def base-app-def
  {:type :genegraph-app
   :kafka-clusters {:data-exchange data-exchange}
   :topics {:fetch-base-events
            (assoc fetch-base-events-topic
                   :type :kafka-consumer-group-topic
                   :kafka-consumer-group consumer-group)
            :base-data
            (assoc base-data-topic
                   :type :kafka-producer-topic)}
   :processors {:fetch-base (assoc fetch-base-processor
                                   :kafka-cluster :data-exchange
                                   :kafka-transactional-id (qualified-kafka-name "fetch-base"))}
   :http-servers ready-server})


(def reporter-interceptor
  (interceptor/interceptor
   {:name ::reporter
    :enter (fn [e]
             (log/info :fn :reporter :key (::event/key e))
             e)}))

(def graphql-endpoint-def
  {:type :genegraph-app
   :kafka-clusters {:data-exchange data-exchange}
   :storage {:api-tdb (assoc api-tdb :load-snapshot true)
             :response-cache-db response-cache-db
             :object-db (assoc object-db :load-snapshot true)}
   :topics {:api-log
            (assoc api-log-topic
                   :type :kafka-producer-topic)
            :dosage
            (assoc dosage-topic
                   :type :kafka-reader-topic)
            :base-data
            (assoc base-data-topic
                   :type :kafka-reader-topic)
            :clinvar-curation clinvar-curation-topic
            :gene-validity-complete (assoc gene-validity-sepio-topic
                                           :type :kafka-reader-topic)}
   :processors {:import-base-file import-base-processor
                :graphql-api graphql-api
                :graphql-ready graphql-ready
                :import-dosage-curations import-dosage-curations
                :read-clinvar-curations read-clinvar-curations
                :import-gv-curations import-gv-curations}
   :http-servers http-server})

(def genegraph-function
  {"fetch-base" base-app-def
   "graphql-endpoint" graphql-endpoint-def})

(defn store-snapshots! [app]
  (->> (:storage app)
       (map val)
       (filter :snapshot-handle)
       (run! storage/store-snapshot)))

(defn periodically-store-snapshots
  "Start a thread that will create and store snapshots for
   storage instances that need/support it. Adds a variable jitter
   so that similarly configured apps don't try to backup at the same time."
  [app period-hours run-atom]
  (let [period-ms (* 60 60 1000 period-hours)]
    (Thread/startVirtualThread
     (fn []
       (while @run-atom
         (Thread/sleep period-ms)
         (try
           (store-snapshots! app)
           (catch Exception e
             (log/error :fn ::periodically-store-snapshots
                        :exception e))))))))

(defn -main [& args]
  (log/info :fn ::-main
            :msg "starting genegraph"
            :function (:function env))
  (let [app (p/init (get genegraph-function (:function env)))
        run-atom (atom true)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. (fn []
                                 (log/info :fn ::-main
                                           :msg "stopping genegraph")
                                 (reset! run-atom false)
                                 (p/stop app))))
    (p/start app)
    (periodically-store-snapshots app 6 run-atom)))
