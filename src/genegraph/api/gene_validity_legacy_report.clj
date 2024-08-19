(ns genegraph.api.gene-validity-legacy-report
  (:require [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.event :as event]
            [genegraph.framework.storage :as storage]
            [clojure.data.json :as json]
            [io.pedestal.interceptor :as interceptor]))

(def gci-root "http://dataexchange.clinicalgenome.org/gci/")
(def affiliation-root "http://dataexchange.clinicalgenome.org/agent/")

(defn json-content-node [report iri]
  [[iri :rdf/type :cnt/ContentAsText]
   [iri :cnt/chars (if (string? (:scoreJson report)) ; covers legacy neo4j reports
                     (:scoreJson report)
                     (json/write-str report))]])

(defn gci-legacy-report-to-triples [report]
  (let [root-version (str gci-root (-> report :iri))
        id (:iri report) 
        iri (rdf/resource (str gci-root id "_report"))
        content-id (rdf/blank-node)
        assertion-id (rdf/resource (str gci-root id))
        animal-model (get-in report [:scoreJson :summary :AnimalModelOnly])
        result (concat [[iri :rdf/type :sepio/GeneValidityReport]
                        [iri :bfo/has-part content-id]
                        [iri :bfo/has-part assertion-id]]
                      (json-content-node report content-id))]
    (if (some? animal-model)
      (concat result [[iri :cg/is-animal-model-only animal-model]])
      result)))

(defn add-gci-legacy-model-fn [event]
  (assoc event
         ::model
         (-> event ::event/data gci-legacy-report-to-triples rdf/statements->model)))

(def add-gci-legacy-model
  (interceptor/interceptor
   {:name ::add-gci-legacy-model
    :enter (fn [e] (add-gci-legacy-model-fn e))}))

(defn write-gci-model-to-db-fn [event]
  (event/store event
               :gv-tdb
               (str gci-root "legacy/" (get-in event [::event/data :iri]))
               (::model event)))

(def write-gci-legacy-model-to-db
  (interceptor/interceptor
   {:name ::write-gci-legacy-model-to-db
    :enter (fn [e] (write-gci-model-to-db-fn e))}))
