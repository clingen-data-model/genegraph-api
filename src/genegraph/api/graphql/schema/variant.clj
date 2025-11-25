(ns genegraph.api.graphql.schema.variant
  (:require [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.event :as event]
            [genegraph.api.hybrid-resource :as hr]
            [io.pedestal.log :as log]))


(defn variant-assertions-fn [context args value]
  (let [q (rdf/create-query "
select ?x where {
?p :cg/variant ?v .
?x :cg/subject ?p .
?x a :cg/EvidenceStrengthAssertion .
}")]
    (->> (q value {:v value})
         (mapv #(hr/hybrid-resource % context)))))

(defn overlapping-features-fn [context args value]
  (let [q (rdf/create-query "
select ?x where {
?variant :cg/CompleteOverlap ?x .
?x a ?type .
filter (?type in ( :so/GeneWithProteinProduct , :cg/DosageRegion ))}")]
    (->> (q (:tdb context) {:variant value})
         (mapv #(hr/hybrid-resource % context)))))

(comment
  (count
   (let [context {:tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])
                  :object-db @(get-in genegraph.user/api-test-app [:storage :object-db :instance])}]
     (rdf/tx (:tdb context)
       (overlapping-features-fn
        context
        nil
        (rdf/ld1-> (rdf/resource "CVSCV:SCV005044868" (:tdb context))
                   [:cg/subject :cg/variant])))))
  )


(def canonical-variant
  {:name :CanonicalVariant
   :graphql-type :object
   :implements [:Resource]
   :fields {:includedVariants {:type '(list :Resource)
                               :path [:cg/includedVariants]}
            :copyChange {:type :Resource
                         :path [:ga4gh/copyChange]}
            :overlappingFeatures {:type '(list :SequenceFeature)
                                  #_#_:path [:cg/CompleteOverlap]
                                  :resolve (fn [c a v] (overlapping-features-fn c a v))}
            :assertions {:type '(list :EvidenceStrengthAssertion)
                         :resolve
                         (fn [c a v]
                           (variant-assertions-fn c a v))}}})

(def copy-number-variant
  {:name :CopyNumberChange
   :graphql-type :object
   :implements [:Resource]
   :fields {:copyChange {:type :Resource
                         :path [:ga4gh/copyChange]}
            :location {:type :SequenceLocation}}})

