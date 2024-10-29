(ns genegraph.gv-setup
  "Functions and procedures generally intended to be run from the REPL
  for the purpose of configuring and maintaining a genegraph gene validity
  instance."
  (:require [genegraph.framework.app :as app]
            [genegraph.framework.protocol :as p]
            [genegraph.framework.event.store :as event-store]
            [genegraph.framework.event :as event]
            [genegraph.framework.kafka.admin :as kafka-admin]
            [genegraph.framework.storage :as storage]
            [genegraph.api :as ggapi]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.log :as log]
            [clojure.set :as set]
            [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:import [java.time Instant OffsetDateTime Duration]
           [java.io PushbackReader]))

;; TODO Reconfigure to add ClinVar Curation topic

;; Step 1
;; Select the environment to configure

;; The gv/admin-env var specifies the platform to target and the
;; data exchange credentials to use. Set at least the :platform
;; and :dataexchange-genegraph keys for the correct environment
;; and kafka instance. This should be done in genegraph/gene_validity.clj

;; Step 2
;; Set up kafka topics, configure permissions

;; The kafka-admin/configure-kafka-for-app! function accepts an
;; (initialized) Genegraph app and creates the topics and necessary
;; permissions for those topics.

;; There are three Genegraph instances that need to be set up to create a
;; working installation:

;; gv-base-app-def: listents to fetch topic, retrieves base data and notifies gql endpoint
;; gv-transformer-def: Transforms gene validity curations to SEPIO format, publishes to Kafka
;; gv-graphql-endpoint-def: Ingest curations from various sources, publish via GraphQL endpoint

(comment
  (run! #(kafka-admin/configure-kafka-for-app! (p/init %))
        [ggapi/gv-base-app-def
         ggapi/gv-graphql-endpoint-def])

  ;; Delete all (or some) Genegraph-created topics
  ;; Use this to fix mistakes.
  (with-open [admin-client (kafka-admin/create-admin-client ggapi/data-exchange)]
    (run! #(try
             (kafka-admin/delete-topic admin-client (:kafka-topic %))
             (catch Exception e
               (log/info :msg "Exception deleting topic "
                         :topic (:kafka-topic %))))
          [#_ggapi/fetch-base-events-topic
           #_ggapi/base-data-topic
           #_ggapi/gene-validity-complete-topic
           #_ggapi/gene-validity-legacy-complete-topic
           #_ggapi/gene-validity-sepio-topic
           #_ggapi/api-log-topic]))
  )

;; Step 3
;; Seed newly created topics with initialization data. Use the local Genegraph
;; app definitions and rich comments to set up these topics.

;; Three topics need to be seeded with initialization data:

;; Step 3.1
;; :fetch-base-events: requests to update the base data supporting Genegraph
;; The data needed to seed this topic is stored in version control with
;;    genegraph-gene-validity in resources/base.edn

(def gv-seed-base-event-def
  {:type :genegraph-app
   :kafka-clusters {:data-exchange ggapi/data-exchange}
   :topics {:fetch-base-events
            (assoc ggapi/fetch-base-events-topic
                   :type :kafka-producer-topic
                   :create-producer true)}})

(comment
  (def gv-seed-base-event
    (p/init gv-seed-base-event-def))

  (p/start gv-seed-base-event)
  (p/stop gv-seed-base-event)
  
  (->> (-> "base.edn" io/resource slurp edn/read-string)
       (run! #(p/publish (get-in gv-seed-base-event
                                 [:topics :fetch-base-events])
                         {::event/data %
                          ::event/key (:name %)})))

  (->> (-> "base.edn" io/resource slurp edn/read-string)
       (filter #(= "http://purl.obolibrary.org/obo/mondo.owl" (:name %)))
       (run! #(p/publish (get-in gv-seed-base-event
                                 [:topics :fetch-base-events])
                         {::event/data %
                          ::event/key (:name %)})))

  (->> (-> "base.edn" io/resource slurp edn/read-string)
       (filter #(= "http://dataexchange.clinicalgenome.org/gci-express" (:name %)))
       (run! #(p/publish (get-in gv-seed-base-event
                                 [:topics :fetch-base-events])
                         {::event/data %
                          ::event/key (:name %)})))

  (->> (-> "base.edn" io/resource slurp edn/read-string)
       (filter #(= "http://dataexchange.clinicalgenome.org/missing-dosage-curations" (:name %)))
       (run! #(p/publish (get-in gv-seed-base-event
                                 [:topics :fetch-base-events])
                         {::event/data %
                          ::event/key (:name %)})))

  (p/stop gv-seed-base-event)
  )


