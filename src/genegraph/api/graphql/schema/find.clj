(ns genegraph.api.graphql.schema.find
  (:require [genegraph.api.filter :as query-filter]
            [genegraph.api.hybrid-resource :as hr]
            [genegraph.framework.storage.rdf :as rdf]))

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

(def filter-ops
  {:name :FilterOps
   :graphql-type :enum
   :description "Operators that can be used with a filter call. Set operations, such as Union and Difference are available."
   :values [{:enum-value :union
             :description "Filters the result using the union of the set returned by this filter and whatever other filters are used."}
            {:enum-value :difference
             :description "Values matching this filter are removed from the result set"}
            {:enum-value :exists
             :description "Filter pattern must exist in result"}
            {:enum-value :not_exists
             :description "Filter pattern must not exist in result"}]})

(def filter-call
  {:name :Filter
   :graphql-type :input-object
   :description "Application of a filter to a query."
   :fields {:filter {:type :FilterNames}
            :argument {:type 'String}
            :operation {:type :FilterOps}}})

(defn find-query-fn [context args _]
  (mapv #(hr/hybrid-resource % context)
        (query-filter/apply-filters context (:filters args))))

(def find-query
  {:name :find
   :graphql-type :query
   :description "Query to find resources in Genegraph. Apply combinations of filters to limit the available results to the desired set."
   :type '(list :Resource)
   :args {:filters {:type '(list :Filter)}}
   :resolve (fn [context args value] (find-query-fn context args value))})

(defn assertions-query-fn [context args _]
  (let [q (query-filter/compile-filter-query [:bgp ['x :rdf/type :cg/EvidenceStrengthAssertion]]
                                             (:filters args))]
    (mapv #(hr/hybrid-resource % context)
          (q (:tdb context) {::rdf/params {:limit 500}}))))

(def assertions-query
  {:name :assertions
   :graphql-type :query
   :description "Query to find assertions in Genegraph. Apply combinations of filters to limit the available results to the desired set."
   :type '(list :EvidenceStrengthAssertion)
   :args {:filters {:type '(list :Filter)}}
   :resolve (fn [context args value] (assertions-query-fn context args value))})
