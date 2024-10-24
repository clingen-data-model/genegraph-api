(ns genegraph.api.graphql.schema.conflicts
  (:require [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage :as storage]))

(def haplo-conflict-query
  (rdf/create-query "
select ?pathAssertion ?mechanismAssertion where 
{ ?mechanismAssertion :cg/evidenceStrength :cg/DosageSufficientEvidence ;
  :cg/subject ?dosageProp .
  ?dosageProp :cg/mechanism :cg/Haploinsufficiency ;
  a :cg/GeneticConditionMechanismProposition ;
  :cg/feature ?feature .
  ?variant :cg/CompleteOverlap ?feature ;
  :ga4gh/copyChange :efo/copy-number-loss .
  ?pathProp :cg/variant ?variant .
  ?pathAssertion :cg/subject ?pathProp ;
  :cg/direction :cg/Refutes .
  FILTER NOT EXISTS { ?pathAssertion :cg/reviewStatus :cg/Flagged }
}
"))

(defn conflicts-query-fn [{:keys [tdb object-db]} args _]
  (->> (haplo-conflict-query tdb {::rdf/params {:type :table}})
       (group-by :pathAssertion)
       (take 1)
       (mapv (fn [[path-assertion tuples]]
               (tap> (storage/read object-db [:objects (str path-assertion)]))
               {:iri (str path-assertion)
                ::rdf/resource path-assertion
                :conflictingAssertions
                (mapv (fn [{:keys [mechanismAssertion]}]
                        {::rdf/resource mechanismAssertion
                         :iri (str mechanismAssertion)})
                      tuples)}))))


(def assertion
  {:name :Assertion
   :graphql-type :object
   :skip-type-resolution true
   :fields {:iri {:type 'String}
            :conflictingAssertions
            {:type '(list :Assertion)
             :resolve (fn [_ _ v] (:conflictingAssertions v))}}})

#_(def assertion-conflict
  {:name :AssertionConflict
   :graphql-type :object
   :description "A statement of conflict between an assertion and a set of assertions extant in a knowledgebase "
   :fields {:assertion {:type 'String}
            :conflictingAssertions {:type 'String}}})

(def conflicts-query
  {:name :conflicts
   :graphql-type :query
   :description "Query to find conflicts in interpretation between knowledge statements, such as GeneticConditionMechanismPropositions and VariantPathogenicityPropositions."
   :type '(list :Assertion)
   :skip-type-resolution true
   :resolve conflicts-query-fn})

(comment
  (let [tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])]
    (rdf/tx tdb
      (->> (conflicts-query-fn {:tdb tdb} nil nil)
           tap>)))
  )
