(ns genegraph.api.graphql.schema.conflicts
  (:require [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.event :as event]
            [genegraph.framework.id :as id]
            [genegraph.api.hybrid-resource :as hr]
            [com.walmartlabs.lacinia.schema :as schema]
            [io.pedestal.log :as log])
  (:import [java.time Instant]))

(defn conflicts-query-fn [{:keys [tdb object-db] :as context} args _]
  (let [haplo-conflict-query (rdf/create-query "
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
  FILTER NOT EXISTS { ?pathAssertion :cg/classification :cg/OtherClassification }
  FILTER NOT EXISTS { ?annotation :cg/subject ?pathAssertion ;
                                  a :cg/AssertionAnnotation . } }
")]
    (->> (haplo-conflict-query tdb {::rdf/params {:type :table}})
         (group-by :pathAssertion)
         (mapv (fn [[path-assertion tuples]]
                 (assoc (hr/hybrid-resource path-assertion
                                            context)
                        :conflictingAssertions
                        (mapv (fn [{:keys [mechanismAssertion]}]
                                (hr/hybrid-resource mechanismAssertion
                                                    context))
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

(def conflicts-query
  {:name :conflicts
   :graphql-type :query
   :description "Query to find conflicts in interpretation between knowledge statements, such as GeneticConditionMechanismPropositions and VariantPathogenicityPropositions."
   :type '(list :EvidenceStrengthAssertion)
   ;;:skip-type-resolution true
   :resolve conflicts-query-fn})

(def assertion-annotation
  {:name :AssertionAnnotation
   :graphql-type :object
   :implements [:Resource]
   :description "An assessment on a clinvar curation"
   :fields {:subject {:type :EvidenceStrengthAssertion
                      :path [:cg/subject]}
            :classification {:type :Resource
                             :path [:cg/classification]}
            :description {:type 'String
                          :path [:dc/description]}
            :contributions {:type '(list :Contribution)
                            :path [:cg/contributions]}
            :evidence {:type '(list :EvidenceStrengthAssertion)
                       :path [:cg/evidence]}}})



(defn create-annotation-fn [context args _]
  (let [annotation (assoc (select-keys args [:agent
                                           :classification
                                           :description
                                           :evidence])
                        :subject (:subject args)
                        :date (str (Instant/now))
                        :iri (id/random-iri))]
    (swap! (:effects context)
           event/publish
           {::event/topic :clinvar-curation
            ::event/key (:iri annotation)
            ::event/data annotation})
    annotation))

(def create-annotation
  {:name :createAssertionAnnotation
   :graphql-type :mutation
   :description "Mutation to create a annotation of a clinvar assertion"
   :type :AssertionAnnotation
   :skip-type-resolution true
   :args {:subject {:type 'String}
          :agent {:type 'String}
          :classification {:type 'String}
          :description {:type 'String}
          :evidence {:type '(list String)}}
   :resolve create-annotation-fn})

(defn assertion-annotation-query-fn
  [context args _]
  (let [query (rdf/create-query "
select ?assertion where {
?annoation a :cg/AssertionAnnotation ;
:cg/subject ?assertion .
}")]
    (mapv #(hr/hybrid-resource % context)
          (query (:tdb context)))))

(def assertion-annotation-query
  {:name :assertionAnnotation
   :graphql-type :query
   :description "Query to find assertions for which there exists annotation."
   :type '(list :EvidenceStrengthAssertion)
   :resolve assertion-annotation-query-fn})



(comment
  (let [tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])
        object-db @(get-in genegraph.user/api-test-app [:storage :object-db :instance])]
    (rdf/tx tdb
      (->> (conflicts-query-fn {:tdb tdb :object-db object-db} nil nil)
           tap>)))

  (let [tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])
        object-db @(get-in genegraph.user/api-test-app [:storage :object-db :instance])]
    (rdf/tx tdb
      (->> (assertion-annotation-query-fn {:tdb tdb :object-db object-db} nil nil)
           tap>)))
  
  (tap>
   (storage/read @(get-in genegraph.user/api-test-app
                          [:storage :object-db :instance])
                 [:objects
                  "https://identifiers.org/clinvar.submission:SCV000176131"
                  #_"https://genegraph.clingen.app/unMuqS1LlBQ"

                  #_"https://identifiers.org/clinvar:146850"]))
  
  )
