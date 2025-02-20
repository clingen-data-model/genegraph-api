(ns genegraph.api.graphql.schema.find
  (:require [genegraph.api.filter :as query-filter]
            [genegraph.api.hybrid-resource :as hr]))

(def query-result
  {:name :QueryResult
   :graphql-type :object
   :description "A list of results for a given query. Includes the count of potential results, in case the list exceeds the limit."
   :fields {:results {:description "Results of the query"
                      :type '(list :Resource)
                      :resolve (fn [_ _ value] (:results value))}
            :count {:description "Total possible results in list}"
                    :type 'Int}}})
(def filters-enum
  {:name :FilterNames
   :graphql-type :enum
   :description "Names of filters available for use."
   :values (mapv (fn [[k v]] {:enum-value k :description (:description v)})
                 query-filter/filters)})

(def filter-call
  {:name :Filter
   :graphql-type :input-object
   :description "Application of a filter to a query."
   :fields {:filter {:type :FilterNames}
            :argument {:type 'String}}})

(defn find-query-fn [context args _]
  (tap> context)
  (mapv #(hr/hybrid-resource % context)
        (query-filter/apply-filters context (:filters args))))

(def find-query
  {:name :find
   :graphql-type :query
   :description "Query to find resources in Genegraph. Apply combinations of filters to limit the available results to the desired set."
   :type '(list :Resource)
   :args {:filters {:type '(list :Filter)}}
   :resolve (fn [context args value] (find-query-fn context args value))})
