(ns genegraph.api.base.affils-json
  "Import affiliations from JSON-based service"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage :as storage]))

(def affils-root "https://genegraph.clinicalgenome.org/agent/")

#_(defmethod rdf/as-model :genegraph.api.base/affils-json
  [{:keys [source]}]

  (with-open [r (-> source storage/->input-stream io/reader)]
    (->> (json/read r :key-fn keyword)
         (map #(get-in % [:subgroups :gcep]))
         (remove nil?)
         (mapcat (fn [{:keys [fullname id]}]
                   (let [iri (str affils-root id)]
                     [[iri :rdf/type :cg/Affiliation]
                      [iri :rdfs/label fullname]])))
         rdf/statements->model)))

(defmethod rdf/as-model :genegraph.api.base/affils-json
  [{:keys [source]}]

  (with-open [r (-> source storage/->input-stream io/reader)]
    (->> (json/read r :key-fn keyword)
         (mapcat (fn [{:keys [affiliation_fullname
                              affiliation_id]}]
                   (let [iri (str affils-root affiliation_id)]
                     [[iri :rdf/type :cg/Affiliation]
                      [iri :rdfs/label affiliation_fullname]])))
         rdf/statements->model)))

(comment

  (def data
    (-> "data/base/affils.json"
        slurp
        (json/read-str :key-fn keyword)))

  (->> data
       (take 5)
       (map #(get-in % [:subgroups :gcep]))
       (remove nil?)
       (mapcat (fn [{:keys [fullname id]}]
                 (let [iri (str affils-root id)]
                   [[iri :rdf/type :cg/Affiliation]
                    [iri :rdfs/label fullname]])))
       (into []))
  )
