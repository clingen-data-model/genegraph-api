(ns genegraph.api.graphql.schema.text-search
  (:require [genegraph.api.hybrid-resource :as hr]
            [genegraph.api.lucene :as lucene]
            [genegraph.framework.storage :as storage]
            [clojure.string :as str]))

(defn text-search-query-fn
  [{:keys [text-index] :as context} params _]
  (clojure.pprint/pprint params)
  (let [results
        (concat 
         (lucene/search text-index
                        (-> params
                            (assoc :field :symbol)
                            (update :query str/upper-case)))
         (lucene/search text-index
                        (assoc params :field :label)))]
    (mapv #(hr/hybrid-resource (:iri %) context) results)))

(def text-search-query
  {:name :textSearch
   :graphql-type :query
   :description "Query using full text indexes to find entities in Genegraph"
   :type '(list :Resource)
   :args {:query {:type 'String}
          :type {:type 'String}
          :limit {:type 'Int}}
   :resolve (fn [c a v] (text-search-query-fn c a v))})
