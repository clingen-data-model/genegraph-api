(ns genegraph.api.graphql.schema.conflicts
  (:require [genegraph.framework.storage.rdf :as rdf]))

(defn query [{:keys [tdb]} args _]
  (let [q (rdf/create-query "
select ?pathAssertion where 
{ ?assertion :cg/evidenceStrength :cg/DosageSufficientEvidence ;
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
")
        results (q tdb)]
    {:results results
     :count (count results)}))

(def conflicts-query
  {:name :conflicts
   :graphql-type :query
   :description "Query to find conflicts in interpretation between knowledge statements, such as GeneticConditionMechanismPropositions and VariantPathogenicityPropositions."
   :type :QueryResult
   :skip-type-resolution true
   :resolve query})
