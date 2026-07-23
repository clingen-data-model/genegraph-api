(ns genegraph.api.base.genebayes
  (:require [charred.api :as charred]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.edn :as edn]
            [io.pedestal.log :as log]
            [genegraph.framework.id :as id]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.api.rdf-conversion :as rdf-conversion])
  (:import [java.io PushbackReader]))

(id/register-type
 {:type :cg/LOFProbabilityEstimation
  :defining-attributes
  [:cg/feature
   :dc/source]})

(defn read-hgnc->entrez-map []
  (with-open [r (-> "hgnc-entrez.edn"
                    io/resource
                    io/reader
                    PushbackReader.)]
    (edn/read r)))

(defn parse-double-or-negative [n]
  (try
    (Double/parseDouble n)
    (catch Exception e
      -1)))

(defn genebayes-row->m
  [[_
    hgnc-id
    _
    obs-lof
    exp-lof
    prior-mean
    post-mean
    post-l95
    post-u95]
   hgnc->entrez-map]
  (try
    (let [m {:cg/feature (get hgnc->entrez-map hgnc-id)
             :type :cg/LOFProbabilityEstimation
             :cg/observedLOF (parse-double-or-negative obs-lof)
             :cg/expectedLOF (parse-double-or-negative exp-lof)
             :cg/priorMean (parse-double-or-negative prior-mean)
             :cg/mean (parse-double-or-negative post-mean)
             :cg/lower95CI (parse-double-or-negative post-l95)
             :cg/upper95CI (parse-double-or-negative post-u95)
             :dc/source "https://pubmed.ncbi.nlm.nih.gov/38977852/"}]
      (assoc m :iri (id/iri m)))
    (catch Exception e
      (log/info :fn ::genebayes-row->m :gene hgnc-id)
      {})))

(defmethod rdf/as-model :genegraph.api.base/genebayes
  [{:keys [source]}]
  (let [hgnc-map (read-hgnc->entrez-map)]
    (with-open [r (io/reader (storage/->input-stream source))]
      (->> (charred/read-csv r)
           rest
           (map #(genebayes-row->m % hgnc-map))
           (filter :iri)
           (mapcat rdf-conversion/map->statements)
           (into [])
           rdf/statements->model))))

(comment
  (.size
   (rdf/as-model
    {:format :genegraph.api.base/genebayes
     :source {:type :file
              :base "/Users/tristan/data/genegraph-base/"
              :path "genebayes.csv"}}))
  )

(comment
  (def genebayes-path
    (str "/Users/tristan/Documents/genebayes_supplemental/"
         "Supplementary Table 1-Table 1.csv"))
  (let [hgnc-map (read-hgnc->entrez-map)]
    (with-open [r (io/reader genebayes-path)]
      (->> (charred/read-csv r)
           (drop 1)
           (take 2)
           (mapcat #(-> %
                        (genebayes-row->m hgnc-map)
                        rdf-conversion/map->statements))
           (into [])
           tap>)))


  (with-open [r (io/reader genebayes-path)]
    (->> (charred/read-csv r)
         rest
         (filter (fn [[_ _ _ obs exp _ _ _ _]] (= "" obs)))
         count))
  )


