(ns genegraph.user
  (:require [genegraph.framework.protocol]
            [genegraph.framework.kafka :as kafka]
            [genegraph.framework.kafka.admin :as kafka-admin]
            [genegraph.framework.event :as event]
            [genegraph.framework.protocol :as p]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage.rdf.jsonld :as jsonld]
            [genegraph.framework.event.store :as event-store]
            [genegraph.api :as api]
            [genegraph.api.protocol :as ap]
            [genegraph.api.dosage :as dosage]
            [genegraph.api.graphql.response-cache :as response-cache]
            [portal.api :as portal]
            [clojure.data.json :as json]
            [clojure.data.csv :as csv]
            [io.pedestal.log :as log]
            [io.pedestal.interceptor :as interceptor]
            [hato.client :as hc]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.edn :as edn]
            [clojure.walk :as walk]
            [clojure.spec.alpha :as spec])
  (:import [ch.qos.logback.classic Logger Level]
           [org.slf4j LoggerFactory]
           [java.time Instant LocalDate]))

;; Portal
(comment
  (def p (portal/open))
  (add-tap #'portal/submit)
  (portal/close)
  (portal/clear)
  )

;; Test app

(defn log-api-event-fn [e]
  (let [data (::event/data e)]
    (log/info :fn ::log-api-event
              :duration (- (:end-time data) (:start-time data))
              :response-size (:response-size data)
              :handled-by (:handled-by data)
              :status (:status data)
              :error-message (:error-message data))
    e))

(def log-api-event
  (interceptor/interceptor
   {:name ::log-api-event
    :enter (fn [e] (log-api-event-fn e))}))

(def read-api-log
  {:name :read-api-log
   :type :processor
   :subscribe :api-log
   :interceptors [log-api-event]})

(def api-test-app-def
  {:type :genegraph-app
   :kafka-clusters {:data-exchange api/data-exchange}
   :topics {:gene-validity-sepio
            {:name :gene-validity-sepio
             :type :simple-queue-topic}
            :fetch-base-events
            {:name :fetch-base-events
             :type :simple-queue-topic}
            :base-data
            {:name :base-data
             :type :simple-queue-topic}
            :dosage
            {:name :dosage
             :type :simple-queue-topic}
            :api-log
            {:name :api-log
             :type :simple-queue-topic}}
   :storage {:api-tdb api/api-tdb
             :response-cache-db api/response-cache-db
             :sequence-feature-db api/sequence-feature-db
             :object-db api/object-db}
   :processors {:fetch-base-file api/fetch-base-processor
                :import-base-file api/import-base-processor
                :import-gv-curations api/import-gv-curations
                :graphql-api (assoc api/graphql-api
                                    ::event/metadata
                                    {::response-cache/skip-response-cache true})
                :graphql-ready api/graphql-ready
                :import-dosage-curations api/import-dosage-curations
                :read-api-log read-api-log}
   :http-servers api/gv-http-server})

(comment
  (def api-test-app (p/init api-test-app-def))
  (p/start api-test-app)
  (p/stop api-test-app)
  )

;; Downloading events

(def root-data-dir "/Users/tristan/data/genegraph-neo/")

(defn get-events-from-topic [topic]
  ;; topic->event-file redirects stdout
  ;; need to supress kafka logs for the duration
  (.setLevel
   (LoggerFactory/getLogger Logger/ROOT_LOGGER_NAME) Level/ERROR)
  (kafka/topic->event-file
   (assoc topic
          :type :kafka-reader-topic
          :kafka-cluster api/data-exchange)
   (str root-data-dir
        (:kafka-topic topic)
        "-"
        (LocalDate/now)
        ".edn.gz"))
  (.setLevel (LoggerFactory/getLogger Logger/ROOT_LOGGER_NAME) Level/INFO))

;; Event Writers

(comment

  (get-events-from-topic api/actionability-topic)
  (time (get-events-from-topic api/gene-validity-complete-topic))
  (get-events-from-topic api/gene-validity-raw-topic)
  (time (get-events-from-topic api/gene-validity-legacy-complete-topic))
  (time (get-events-from-topic api/dosage-topic))

  (time (get-events-from-topic api/gene-validity-sepio-topic))

)


;; restructuring base, adding ClinVar
(comment
  (->> (-> "base.edn" io/resource slurp edn/read-string)
       (filter #(= "https://www.ncbi.nlm.nih.gov/clinvar/"
                   (:name %)))
       (run! #(p/publish (get-in api-test-app
                                 [:topics :fetch-base-events])
                         {::event/data %
                          ::event/key (:name %)})))

  (->> (-> "base.edn" io/resource slurp edn/read-string)
       (filter #(= "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                   (:name %)))
       (run! #(p/publish (get-in api-test-app
                                 [:topics :fetch-base-events])
                         {::event/data %
                          ::event/key (:name %)})))
  (tap>
   (p/process
    (get-in api-test-app [:processors :import-base-file])
    (-> (filter #(= "https://www.ncbi.nlm.nih.gov/clinvar/"
                    (:name %))
                (-> "base.edn" io/resource slurp edn/read-string))
        first
        (assoc :genegraph.api.base/handle
               {:type :file
                :base "data/base"
                :file "clinvar.xml.gz"}))))
  
  (tap>
   (storage/scan @(get-in api-test-app [:storage :object-db :instance])
                 ["clinvar"]))
  
  )

;; Dosage modifications

;; gene_dosage_raw-2024-09-18.edn.gz

;; Should also deal with dosage records throwing exceptions
;; though possibly the work done for this will handle that issue
(comment
  (time
   (def recent-dosage-records
     (event-store/with-event-reader [r (str root-data-dir "gene_dosage_raw-2024-09-18.edn.gz")]
       (->> (event-store/event-seq r)
            (take-last 100)
            (into [])))))

  (def chr16p13
    (event-store/with-event-reader [r (str root-data-dir "gene_dosage_raw-2024-09-18.edn.gz")]
      (->> (event-store/event-seq r)
           #_(filter #(re-find #"ISCA-37415" (::event/value %)))
           (filter #(= "ISCA-37415" (::event/key %)))
           (into []))))

  (count chr16p13)

  (defn process-dosage [event]
    (try
      (p/process (get-in api-test-app [:processors
                                       :import-dosage-curations])
                 (assoc event
                        ::event/skip-local-effects true
                        ::event/skip-publish-effects true))
      (catch Exception e (assoc event ::error e))))
  
  (def errors
    (event-store/with-event-reader [r (str root-data-dir "gene_dosage_raw-2024-09-27.edn.gz")]
      (->> (event-store/event-seq r)
           #_(take 100)
           (map process-dosage)
           (filter ::error)
           (into []))))
  
  (count errors)

  (mapv ::event/key errors)

  (-> errors
      first
      event/deserialize
      ::event/data
      dosage/gene-dosage-report
      tap>)

  (tap> (mapv process-dosage errors))
  (->> recent-dosage-records
       (map process-dosage)
       (remove ::spec/invalid)
       (take 1)
       (into [])
       #_tap>
       (run! #(rdf/pp-model (:genegraph.api.dosage/model %))))

  (->> chr16p13
       (map process-dosage)
       (remove ::spec/invalid)
       (take-last 1)
       (into [])
       #_tap>
       (run! #(rdf/pp-model (:genegraph.api.dosage/model %))))
  (->> chr16p13
       (map process-dosage)
       (remove ::spec/invalid)
       (take-last 1)
       (into [])
       (map #(-> %
                 ::event/data
                 dosage/location
                 rdf/statements->model
                 rdf/pp-model))

       #_(run! #(rdf/pp-model (:genegraph.api.dosage/model %))))

  #_(get-in chr16p13data [:fields :customfield_10532])
  
  )
