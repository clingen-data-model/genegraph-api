(ns genegraph.api.util
  (:require [genegraph.framework.storage.rdf :as rdf]))
;; Temporarily stashing metadata about properties that refer to
;; resources, rather than primitives here. Should at some point
;; be a part of some, larger schema concept.

(def resource-references
#{:ga4gh/sequenceReference})

#_(defn map->statements [m]
  (let [iri (:iri m)]
    (conj (mapv
           (fn [[p o]]
             (cond
               (resource-references p) [iri p (rdf/resource o)]
               :default [iri p o]))
                (dissoc m :iri :type))
          [iri :rdf/type (rdf/resource (:type m))])))

(defn map->statements [m]
  (let [iri (:iri m)]
    (conj (reduce
           (fn [v [p o]]
             (cond
               (resource-references p) (conj v [iri p (rdf/resource o)])
               :default (conj v [iri p o])))
           []
           (dissoc m :iri :type))
          [iri :rdf/type (rdf/resource (:type m))])))

(comment
  (map->statements
   {:type :ga4gh/SequenceLocation,
    :ga4gh/sequenceReference "https://identifiers.org/refseq:NC_000002.11",
    :ga4gh/start 242930600,
    :ga4gh/end 243102476, 
    :iri "https://genegraph.clingen.app/-SBInA_I8jw"})
  )
