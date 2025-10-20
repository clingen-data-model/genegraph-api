(ns genegraph.api.graphql.schema.text-search
  (:require [genegraph.api.hybrid-resource :as hr]
            [genegraph.api.lucene :as lucene]
            [genegraph.framework.storage :as storage]
            [clojure.string :as str]))

(defn text-search-query-fn [{:keys [text-index] :as context} {:keys [query]} _]
  (println query)
  (let [results (concat 
         (lucene/search text-index
                        {:field :symbol
                         :query (str/upper-case query)})
         (lucene/search text-index
                        {:field :label
                         :query query}))]
    #_(tap> results)
    (mapv #(hr/hybrid-resource (:iri %) context) results)))

(def text-search-query
  {:name :textSearch
   :graphql-type :query
   :description "Query using full text indexes to find entities in Genegraph"
   :type '(list :Resource)
   :args {:query {:type 'String}}
   :resolve (fn [c a v] (text-search-query-fn c a v))})
