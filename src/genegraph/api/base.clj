(ns genegraph.api.base
  (:require [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.event :as event]
            [genegraph.framework.storage.gcs :as gcs]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.protocol :as p]
            [genegraph.framework.app :as app]
            [genegraph.framework.processor :as processor]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [hato.client :as hc]
            ;; may not need instance
            [genegraph.framework.storage.rdf.instance :as tdb-instance]
            [genegraph.api.protocol :as ap]
            [genegraph.api.base.gene :as gene]
            [genegraph.api.base.affiliations]
            [genegraph.api.base.features]
            [genegraph.api.base.ucsc-cytoband]
            [genegraph.api.base.gci-express]
            [genegraph.api.base.gencc]
            [genegraph.api.base.clinvar-submitters]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.log :as log])
  (:import [java.io File InputStream OutputStream]
           [java.nio.channels Channels]))

;; formats
;; RDF: RDFXML, Turtle, JSON-LD
;; ucsc-cytoband
;; affiliations
;; loss-intolerance
;; hi-index
;; features
;; genes
;;

(defn output-handle [event]
  (-> event
      ::handle
      (assoc :path (get-in event [::event/data :target]))))

(defn success? [{status ::http-status}]
  (and (<= 200 status) (< status 400)))

(defn fetch-file-fn [event]
  (log/info :fn ::fetch-file-fn
            :source (get-in event [::event/data :source])
            :name  (get-in event [::event/data :name])
            :status :started)
  (let [response (hc/get (get-in event [::event/data :source])
                         {:http-client (hc/build-http-client {:redirect-policy :always})
                          :as :stream})]

    (when (instance? InputStream (:body response))
      (with-open [os (io/output-stream (storage/as-handle (output-handle event)))]
        (.transferTo (:body response) os)))
    (assoc event
           ::http-status (:status response))))

(def fetch-file
  (interceptor/interceptor
   {:name ::fetch-file
    :enter (fn [e] (fetch-file-fn e))}))

(defn publish-base-file-fn [event]
  (event/publish event {::event/topic :base-data
                        ::event/key (get-in event [::event/data :name])
                        ::event/data (-> event
                                         ::event/data
                                         (assoc :source (output-handle event)))}))

(def publish-base-file
  (interceptor/interceptor
   {:name ::publish-base-file
    :enter (fn [e] (publish-base-file-fn e))}))

(defn read-base-data-fn [event]
  (assoc event ::model (rdf/as-model (::event/data event))))

(def read-base-data
  (interceptor/interceptor
   {:name ::read-base-data
    :enter (fn [e] (read-base-data-fn e))}))

(defn store-model-fn [event]
  (event/store event
               :api-tdb
               (get-in event [::event/data :name])
               (::model event)))

(def store-model
  (interceptor/interceptor
   {:name ::store-model
    :enter (fn [e] (store-model-fn e))}))

(defmethod ap/process-base-event :default
  [event]
  (-> event
      read-base-data-fn
      store-model-fn))

(defn base-event-fn [event]
  (log/info :fn ::base-event-fn)
  (ap/process-base-event event))

(def base-event
  (interceptor/interceptor
   {:name ::base-event
    :enter (fn [e] (base-event-fn e))}))


