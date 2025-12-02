(ns genegraph.api.base.affils-json
  "Import affiliations from JSON-based service"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [genegraph.api.protocol :as ap]
            [genegraph.api.rdf-conversion :as rdf-conversion]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.event :as event]))

(defn affil->doc [{:keys [affiliation_fullname affiliation_id]}]
  {:iri (str affils-root affiliation_id)
   :rdfs/label affiliation_fullname
   :type :cg/Affiliation})

(defn add-docs [event]
  (with-open [r (-> event ::event/data :source storage/->input-stream io/reader)]
    (assoc event ::docs (mapv affil->doc (json/read r :key-fn keyword)))))

(defn add-model [event]
  (assoc event ::model (rdf/statements->model (mapcat rdf-conversion/map->statements (::docs event)))))

(def affils-root "https://genegraph.clinicalgenome.org/agent/")

#_(defmethod rdf/as-model :genegraph.api.base/affils-json
  [{:keys [source]}]

  (with-open [r (-> source storage/->input-stream io/reader)]
    (->> (json/read r :key-fn keyword)
         (mapcat (fn [{:keys [affiliation_fullname
                              affiliation_id]}]
                   (let [iri (str affils-root affiliation_id)]
                     [[iri :rdf/type :cg/Affiliation]
                      [iri :rdfs/label affiliation_fullname]])))
         rdf/statements->model)))

(defmethod ap/process-base-event :genegraph.api.base/load-affils
  [{::event/keys [data] :as event}]
  (println "processing base event affils")
  (let [event-with-model (-> event add-docs add-model)]
    (reduce
     (fn [e a]
       (event/store e :text-index (:iri a) (rdf-conversion/map->text-index a (:name data))))
     (event/store event :api-tdb (:name data) (::model event))
     (::docs event-with-model))))


(comment

  (def data
    (-> "/Users/tristan/data/genegraph-base/affils.json"
        slurp
        (json/read-str :key-fn keyword)))

  (->> data
       (take 5)
       #_(into [])
       #_(mapv affil->doc)
       #_(mapv #(rdf-conversion/map->text-index % "affils.json"))
       tap>)

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
