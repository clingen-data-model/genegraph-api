(ns genegraph.api.base.clinvar-submitters
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage :as storage]
            [io.pedestal.log :as log]))

  #_(with-open [r (io/reader (storage/->input-stream source))]
      (-> (json/read r :key-fn keyword)
          genes-as-triple
          rdf/statements->model))

(def submitter-base
  "https://identifiers.org/clinvar.submitter:")

(defn row->statements [[label id]]
  (let [iri (rdf/resource (str submitter-base id))]
    [[iri :rdf/type :cg/Agent]
     [iri :rdfs/label label]]))

(defmethod rdf/as-model :genegraph.api.base/clinvar-submitters
  [{:keys [source]}]
  (log/info :fn ::rdf/as-model
            :format :genegraph.api.base/clinvar-submitters)
  (with-open [r (io/reader (storage/->input-stream source))]
          (->>(csv/read-csv r :separator \tab)
          rest
          (mapcat row->statements)
          rdf/statements->model)))



(comment
  (do
  
    (with-open [r (io/reader "/Users/tristan/code/genegraph-api/data/base/clinvar-submitters.txt")]
      (->>(csv/read-csv r :separator \tab)
          rest
          (take 5)
          (mapv row->statements)
          tap>)))
  )
