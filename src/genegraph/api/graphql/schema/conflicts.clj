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
               (assoc (storage/read object-db [:objects (str path-assertion)])
                      ::rdf/resource path-assertion
                      :conflictingAssertions
                      (mapv (fn [{:keys [mechanismAssertion]}]
                              mechanismAssertion)
                            tuples))))))

;; Technical debt, in case anyone is wondering
(def mechanism-assertion
  {:name :GeneticConditionMechanismAssertion
   :graphql-type :object
   :description "Temporary type to get us moving without yet having a grand merged database schema. Allows us to differentiate access between dosageassertion objects and regular assertions. Will figure out how to handle the differences later."
   :skip-type-resolution true
   :fields {:iri {:type 'String
                  :resolve (fn [_ _ v] (str v))}}})

(def resource
  {:name :Resource
   :graphql-type :object
   :skip-type-resolution true
   :fields {:iri {:type 'String
                  :resolve (fn [_ _ v] (str v))}
            :label {:type 'String
                    :path [:rdfs/label]}}})



(def assertion
  {:name :Assertion
   :graphql-type :object
   :skip-type-resolution true
   :fields {:iri {:type 'String}
            
            :conflictingAssertions
            {:type '(list :GeneticConditionMechanismAssertion)
             :resolve (fn [_ _ v] (:conflictingAssertions v))}
            
            :classification
            {:type :Resource
             :resolve (fn [{:keys [tdb]} _ v]
                        (rdf/resource (:cg/classification v)
                                      tdb))}
            ;; :comments {}
            ;; :submitter {}
            ;; :date {}
            ;; :reviewStatus {}
            ;; :description {}
            }})

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
