(ns genegraph.api.graphql.schema.sequence-annotation
  (:require [genegraph.framework.storage.rdf :as rdf]
            [genegraph.api.hybrid-resource :as hr]))

(def sequence-location
  {:name :SequenceLocation
   :graphql-type :object
   :implements [:Resource]
   :fields {:start {:type 'Int
                    :path [:ga4gh/start]}
            :end {:type 'Int
                  :path [:ga4gh/end]}
            :sequenceReference {:type :Resource
                                :path [:ga4gh/sequenceReference]}}})

(defn sequence-subject-resolver
  [context args value]
  (mapv
   #(hr/hybrid-resource % context)
   (rdf/ld->* value [[:cg/subject :<]
                     [:cg/gene :<]
                     [:cg/feature :<]])))

(def assertions-query
  (rdf/create-query "
select ?x where {
?prop :cg/gene | :cg/feature | :cg/subject ?feature .
?prop :rdf/type ?proposition_type .
?x :cg/subject ?prop .
}"))

(defn feature-assertions-resolver
  [context {:keys [proposition_type]} value]
  (let [params {:feature value}]
    (mapv
     #(hr/hybrid-resource % context)
     (assertions-query (:tdb context)
                       (if proposition_type
                         (assoc params
                                :proposition_type
                                (rdf/resource proposition_type))
                         params)))))

(def sequence-feature
  {:name :SequenceFeature
   :graphql-type :object
   :implements [:Resource]
   :fields {:location {:type '(list :SequenceLocation)
                       :path [:ga4gh/location]}
            :subjectOf {:type '(list :Resource)
                        :resolve (fn [c a v] (sequence-subject-resolver c a v))}
            :assertions {:type '(list :EvidenceStrengthAssertion)
                         :description "Evidence Strength Assertions about the given resource."
                         :args {:proposition_type
                                {:type 'String
                                 :description "Restrict results to assertions with a given type"}}
                         :resolve (fn [c a v] (feature-assertions-resolver c a v))}}})



(defn sequence-feature-query-fn
  [context args value]
  (if (:iri args)
    (rdf/resource (:iri args) (:tdb context))
    (let [q (rdf/create-query "select ?x where { ?x :skos/prefLabel ?symbol }")]
      (first (q (:tdb context) args)))))

(def sequence-feature-query
  {:name :sequenceFeatureQuery
   :graphql-type :query
   :description "Query to find a sequence feature in Genegraph"
   :type :SequenceFeature
   :args {:iri {:type 'String
                :description "IRI or CURIE identifying the feature."}
          :symbol {:type 'String
                   :description "Symbol identifying the feature. Currently only supports gene symbols."}}
   :resolve (fn [c a v] (sequence-feature-query-fn c a v))})
