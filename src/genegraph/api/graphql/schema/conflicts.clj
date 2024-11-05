(ns genegraph.api.graphql.schema.conflicts
  (:require [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.event :as event]
            [io.pedestal.log :as log])
  (:import [java.time Instant]))

(defn conflicts-query-fn [{:keys [tdb object-db]} args _]
  (let [haplo-conflict-query (def haplo-conflict-query
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
"))]
    (->> (haplo-conflict-query tdb {::rdf/params {:type :table}})
         (group-by :pathAssertion)
         (mapv (fn [[path-assertion tuples]]
                 (assoc (storage/read object-db [:objects (str path-assertion)])
                        ::rdf/resource path-assertion
                        :conflictingAssertions
                        (mapv (fn [{:keys [mechanismAssertion]}]
                                mechanismAssertion)
                              tuples)))))))

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

(def conflict-curation
  {:name :ConflictCuration
   :graphql-type :object
   :description "An assessment on a clinvar curation"
   :skip-type-resolution true
   :fields {:iri {:type 'String}
            :subject {:type :Assertion}
            :classification {:type 'String}
            :description {:type 'String}
            :agent {:type 'String}
            :date {:type 'String}
            :evidence {:type '(list String)}}})

(defn create-curation-fn [context args _]
  (let [curation (assoc (select-keys args [:agent
                                           :classification
                                           :description
                                           :evidence])
                        :subject {:iri (:subject args)}
                        :date (str (Instant/now)))]
    (swap! (:effects context)
           event/publish
           {::event/topic :clinvar-curation
            ::event/key (:iri curation)
            ::event/data curation})
    curation))

(def create-curation
  {:name :createCuration
   :graphql-type :mutation
   :description "Mutation to create a curation of a clinvar assertion"
   :type :ConflictCuration
   :skip-type-resolution true
   :args {:subject {:type 'String}
          :agent {:type 'String}
          :classification {:type 'String}
          :description {:type 'String}
          :evidence {:type '(list String)}}
   :resolve create-curation-fn})

(comment
  (let [tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])
        object-db @(get-in genegraph.user/api-test-app [:storage :object-db :instance])]
    (rdf/tx tdb
      (->> (conflicts-query-fn {:tdb tdb :object-db object-db} nil nil)
           tap>)))
  
  (storage/read @(get-in genegraph.user/api-test-app
                         [:storage :object-db :instance])
                [:objects
                 #_"https://identifiers.org/clinvar.submission:SCV000176131"
                 #_"https://genegraph.clingen.app/unMuqS1LlBQ"

                 "https://identifiers.org/clinvar:146850"])

  )
