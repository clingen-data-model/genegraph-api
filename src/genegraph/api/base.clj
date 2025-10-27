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
            [genegraph.api.base.affils-json]
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

(def included-base-files
  #{"http://www.w3.org/2004/02/skos/core"
    "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    "http://www.w3.org/2011/content#"
    "https://www.w3.org/2002/07/owl"
    "http://www.w3.org/2000/01/rdf-schema#"
    "http://purl.obolibrary.org/obo/so.owl"
    "http://purl.obolibrary.org/obo/mondo.owl"
    "http://purl.obolibrary.org/obo/hp.owl"
    "https://genegraph.app/resources"
    "https://www.genenames.org/"
    "https://ncbi.nlm.nih.gov/genomes/GCF_000001405.40_GRCh38.p14_genomic.gff"
    "https://ncbi.nlm.nih.gov/genomes/GCF_000001405.25_GRCh37.p13_genomic.gff"
    "https://affils.clinicalgenome.org/"
    "https://www.ncbi.nlm.nih.gov/clinvar/submitters"
    "http://dataexchange.clinicalgenome.org/gci-express"
    "https://thegencc.org/"
    "https://www.ncbi.nlm.nih.gov/clinvar/"
    #_"https://omim.org/genemap"})

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
  (log/info :phase :enter
            :fn ::base-event-fn
            :name (get-in event [::event/data :name]))
  (let [e (ap/process-base-event event)]
    (log/info :fn ::base-event-fn
              :phase :complete
              :name (get-in event [::event/data :name]))
    e))

(def base-event
  (interceptor/interceptor
   {:name ::base-event
    :enter (fn [e] (base-event-fn e))}))


