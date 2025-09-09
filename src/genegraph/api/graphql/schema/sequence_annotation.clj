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
  (let [params {:feature value}
        assertions-query
        (rdf/create-query "
select ?x where {
?prop :cg/gene | :cg/feature | :cg/subject ?feature .
?prop :rdf/type ?proposition_type .
?x :cg/subject ?prop .
filter not exists { ?x :prov/wasInvalidatedBy ?otherx }
}")]
    (mapv
     #(hr/hybrid-resource % context)
     (assertions-query (:tdb context)
                       (if proposition_type
                         (assoc params
                                :proposition_type
                                (rdf/resource proposition_type))
                         params)))))

(defn overlapping-genes-set [v]
  (let [q (rdf/create-query "
select ?g where {
?v :cg/CompleteOverlap | :cg/PartialOverlap  ?g .
?g a :so/GeneWithProteinProduct .
}")]
    (set (q v {:v v})))
  #_(->> (rdf/ld-> v [:cg/CompleteOverlap])
         (concat (rdf/ld-> v
                           [:cg/PartialOverlap]))
         set))

(defn same-region-by-genes? [gs v]
  (let [ogs (overlapping-genes-set v)]
    (= gs ogs)))

(defn variants-defined-by-feature [context args value]
  (let [candidates-query (rdf/create-query "
select ?x where {
  ?r :cg/CompleteOverlap | :cg/PartialOverlap ?g .
  ?g a :so/GeneWithProteinProduct .
  ?x :cg/CompleteOverlap | :cg/PartialOverlap ?g
}")
        overlapping-genes (overlapping-genes-set value)
        candidate-variants (candidates-query value {:r value})]
    (->> (candidates-query value {:r value})
         (filter #(same-region-by-genes? overlapping-genes %))
         (mapv #(hr/hybrid-resource % context)))))

(defn completely-overlapping-variants [context args value]
  (mapv
   #(hr/hybrid-resource % context)
   (rdf/ld-> value [[:cg/CompleteOverlap :<]])))

(defn overlapping-variants-fn [context args value]
  (case (:overlap_kind args)
    "equal" (variants-defined-by-feature context args value)
    (completely-overlapping-variants context args value)))

(defn completely-overlapping-features [context args value]
  (mapv
   #(hr/hybrid-resource % context)
   (rdf/ld-> value [:cg/CompleteOverlap])))

(defn all-overlapping-features [context args value]
  (->> (rdf/ld-> value [:cg/CompleteOverlap])
       (concat (rdf/ld-> value [:cg/PartialOverlap]))
       (mapv #(hr/hybrid-resource % context))))

(defn overlapping-features-fn [context args value]
  (case (:overlap_kind args)
    "complete" (completely-overlapping-features context args value)
    (all-overlapping-features context args value)))

(def sequence-feature
  {:name :SequenceFeature
   :graphql-type :object
   :implements [:Resource]
   :fields {:location {:type '(list :SequenceLocation)
                       :path [:ga4gh/location]}
            :subjectOf {:type '(list :Resource)
                        :resolve (fn [c a v] (sequence-subject-resolver c a v))}
            :overlappingVariants {:type '(list :CanonicalVariant)
                                  :args {:overlap_kind
                                         {:type 'String
                                          :description "how to determine what is an overlapping variant. currently only supports equal"}}
                                  :resolve
                                  (fn [c a v] (overlapping-variants-fn c a v))}
            :overlappingFeatures {:type '(list :SequenceFeature)
                                  :args {:overlap_kind
                                         {:type 'String
                                          :description "how to determine what is an overlapping variant. currently only supports equal"}}
                                  :resolve (fn [c a v]
                                             (overlapping-features-fn c a v))}
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
