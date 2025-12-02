(ns genegraph.api.rdf-conversion
  (:require [genegraph.framework.storage.rdf :as rdf]
            [clojure.set :as set]))

(def literal-attrs
  #{:cg/dateLastEvaluated
    :cg/date
    :schema/email
    :schema/givenName
    :schema/familyName
    :rdfs/label})

(defn value->rdf-object [v]
  (if (map? v)
    (rdf/resource (:iri v))
    (rdf/resource v)))

(defn map->statements
  ([m] (map->statements m []))
  ([m statements]
   (let [iri (:iri m)]
     (reduce
      (fn [a [k v]]
        (cond
          (literal-attrs k) (conj a [iri k v])
          (map? v) (map->statements
                    v
                    (conj a [iri k (value->rdf-object v)]))
          (vector? v) (reduce
                       (fn [a1 v1]
                         (let [a2 (conj a1 [iri k (value->rdf-object v1)])]
                           (if (map? v1)
                             (map->statements v1 a2)
                             a2)))
                       a
                       v)
          :default (conj a [iri k (value->rdf-object v)])))
      statements
      (remove
       (fn [[_ v]] (nil? v))
       (set/rename-keys (dissoc m :iri)
                        {:type :rdf/type}))))))

(defn map->model [m]
  (rdf/statements->model (map->statements m)))

(defn map->text-index [m source]
  {:iri (:iri m)
   :labels [(:rdfs/label m)]
   :source source})

(comment
  (->
   {:iri
    "https://genegraph.clinicalgenome.org/agent/1324",
    :schema/email "jane@example.com",
    :schema/givenName "Jane",
    :schema/familyName "Doe"}
   map->model)
  )
