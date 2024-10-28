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
  ?pathAssertion :cg/subject ?pathProp .
  FILTER NOT EXISTS { ?pathAssertion :cg/reviewStatus :cg/Flagged }
  FILTER NOT EXISTS { ?pathAssertion :cg/direction :cg/Supports }
}
"))

(defn conflicts-query-fn [{:keys [tdb object-db]} args _]
  (->> (haplo-conflict-query tdb {::rdf/params {:type :table}})
       (group-by :pathAssertion)
       (mapv (fn [[path-assertion tuples]]
               (assoc (storage/read object-db [:objects (str path-assertion)])
                      ::rdf/resource path-assertion
                      :conflictingAssertions
                      (mapv (fn [{:keys [mechanismAssertion]}]
                              mechanismAssertion)
                            tuples))
               ))))

(def conflict-curation
  {:name :ConflictCuration
   :graphql-type :object
   :description "An assessment on a clinvar curation"
   :skip-type-resolution true
   :fields {:iri {:type 'String}
            :subject {:type :Assertion}
            :annotation {:type 'String}
            :description {:type 'String}
            :agent {:type 'String}
            :date {:type 'String}}})

(str "https://clingen.app/" (random-uuid))

;; Technical debt, in case anyone is wondering
(def mechanism-assertion
  {:name :GeneticConditionMechanismAssertion
   :graphql-type :object
   :description "Temporary type to get us moving without yet having a grand merged database schema. Allows us to differentiate access between dosageassertion objects and regular assertions. Will figure out how to handle the differences later."
   :skip-type-resolution true
   :fields {:iri {:type 'String
                  :resolve (fn [_ _ v] (str v))}
            :gene {:type :Resource
                   :path [:cg/subject :cg/feature]}}})

(def resource
  {:name :Resource
   :graphql-type :object
   :skip-type-resolution true
   :fields {:iri {:type 'String
                  :resolve (fn [_ _ v] (str v))}
            :label {:type 'String
                    :path [:rdfs/label]}}})

(defn scv-date [{:keys [tdb]} _ v]
  (let [contribs (group-by :cg/role (:cg/contributions v))]
    (tap> contribs) 
    (-> (some #(get contribs %)
           [:cg/Evaluator
            :cg/Submitter
            :cg/Creator])
        first
        :cg/date)))

(defn assertion-label [{:keys [object-db]} _ v]
  (let [get-object #(storage/read object-db [:objects %])]
    (-> v
        :cg/subject
        get-object
        :cg/variant
        get-object
        :rdfs/label)))

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
            :submitter
            {:type :Resource
             :resolve (fn [{:keys [tdb]} _ v]
                        (-> (:cg/contributions v)
                            first
                            :cg/agent
                            (rdf/resource tdb)))}
            
            :date {:type 'String
                   :resolve scv-date}
            :label {:type 'String
                    :resolve assertion-label}
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

(def create-curation
  :name :createCuration
  :graphql-type :mutation
  :description "Mutation to create a curation of a clinvar assertion"
  :type :ConflictCuration
  :args {:subject {:type 'String}
         :user {:type 'String}
         :description {:type 'String}})

(comment
  (let [tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])]
    (rdf/tx tdb
      (->> (conflicts-query-fn {:tdb tdb} nil nil)
           tap>)))
  (tap> 
   (storage/read @(get-in genegraph.user/api-test-app
                          [:storage :object-db :instance])
                 [:objects
                  #_"https://identifiers.org/clinvar.submission:SCV000176131"
                  #_"https://genegraph.clingen.app/unMuqS1LlBQ"
                  "https://identifiers.org/clinvar:146850"]))

  )
