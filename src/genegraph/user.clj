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
            [genegraph.api :as gv]
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
  (:import [java.time Instant LocalDate]
           [ch.qos.logback.classic Logger Level]
           [org.slf4j LoggerFactory]
           [org.apache.jena.riot RDFDataMgr Lang]
           [org.apache.jena.riot.system JenaTitanium]
           [org.apache.jena.rdf.model Model Statement]
           [org.apache.jena.query Dataset DatasetFactory]
           [org.apache.jena.sparql.core DatasetGraph]
           [com.apicatalog.jsonld.serialization RdfToJsonld]
           [com.apicatalog.jsonld.document Document RdfDocument]
           [com.apicatalog.rdf Rdf]
           [com.apicatalog.rdf.spi RdfProvider]
           [jakarta.json JsonObjectBuilder Json]
           [java.io StringWriter PushbackReader File]
           [java.util.concurrent Semaphore]))

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

(def gv-test-app-def
  {:type :genegraph-app
   :kafka-clusters {:data-exchange gv/data-exchange}
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
   :storage {:gv-tdb gv/gv-tdb
             :gene-validity-version-store gv/gene-validity-version-store
             :response-cache-db gv/response-cache-db}
   :processors {:fetch-base-file gv/fetch-base-processor
                :import-base-file gv/import-base-processor
                :import-gv-curations gv/import-gv-curations
                :graphql-api (assoc gv/graphql-api
                                    ::event/metadata
                                    {::response-cache/skip-response-cache true})
                :graphql-ready gv/graphql-ready
                :import-dosage-curations gv/import-dosage-curations
                :read-api-log read-api-log}
   :http-servers gv/gv-http-server})

(comment
  (def gv-test-app (p/init gv-test-app-def))
  (p/start gv-test-app)
  (p/stop gv-test-app)
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
          :kafka-cluster gv/data-exchange)
   (str root-data-dir
        (:kafka-topic topic)
        "-"
        (LocalDate/now)
        ".edn.gz"))
  (.setLevel (LoggerFactory/getLogger Logger/ROOT_LOGGER_NAME) Level/INFO))

;; Event Writers

(comment

  (get-events-from-topic gv/actionability-topic)
  (time (get-events-from-topic gv/gene-validity-complete-topic))
  (get-events-from-topic gv/gene-validity-raw-topic)
  (time (get-events-from-topic gv/gene-validity-legacy-complete-topic))

  (time (get-events-from-topic gv/gene-validity-sepio-topic))

)

