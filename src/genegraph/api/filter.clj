(ns genegraph.api.filter
  (:require [genegraph.framework.storage.rdf :as rdf]))

(defn filter-call->query-params [filters {:keys [filter select]
                                          :as filter-call}]
  ((:fn (get filters filter)) filter-call))

(defn compile-jena-query [combined-filters]
  (rdf/create-query
   [:project ['x]
    (->> (:bgp combined-filters)
         (cons :bgp)
         (into []))]))

(defn proposition-type-filter-fn [{:keys [argument]}]
  {:bgp
   [['x :cg/subject 'proposition]
    ['proposition :rdf/type 'proposition_type]]
   :params {:proposition_type (rdf/resource argument)}})

(defn resource-type-filter-fn [{:keys [argument]}]
  {:bgp
   [['x :rdf/type 'resource_ortype]]
   :params {:resource_type (rdf/resource argument)}})


(def filters
  {:proposition_type {:fn proposition-type-filter-fn
                      :description "Type of proposition referred to by the evidence level assertion. Types include CG:VariantPathogenicityProposition, CG:GeneValidityProposition, and CG:ConditionMechanismProposition"}
   :resource_type {:fn resource-type-filter-fn
                   :description "Type of resource to select. Mandatory for most queries. For curated knowledge assertions, use CG:EvidenceStrengthAssertion"}})


(defn combine-filters [{:keys [tdb]} filter-calls]
  (reduce
   (fn [m filter-call]
     (merge-with into
                 m
                 (filter-call->query-params filters filter-call)))
   {:bgp [] :params {}}
   filter-calls))

(defn apply-filters [context filter-calls]
  (let [combined-filters (combine-filters context filter-calls)
        query (compile-jena-query combined-filters)]
    (query (:tdb context) (assoc (:params combined-filters)
                                 ::rdf/params {:limit 200}))))

;; Consider auto-generating enumeration values with description text.


(comment
  (filter-call->query-params filters
                             {:filter :proposition_type
                              :param "CG:VariantPathogenicityProposition"})
  
  (let [tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])
        q (compile-jena-query
           :cg/EvidenceStrengthAssertion
           [(proposition-type-filter :cg/VariantPathogenicityProposition)])]
    (rdf/tx tdb
      (count (q tdb))))

  (let [context {:tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])
                 :filters filters}]
    (rdf/tx (:tdb context)
      (take 5
       (apply-filters context
                      [{:filter :resource_type
                        :param "CG:EvidenceStrengthAssertion"}
                       {:filter :proposition_type
                        :param "CG:VariantPathogenicityProposition"}]))))
  )
