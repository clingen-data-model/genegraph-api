(ns genegraph.api.graphql.schema.variant)


(def canonical-variant
  {:name :CanonicalVariant
   :graphql-type :object
   :implements [:Resource]
   :fields {:includedVariants {:type '(list :Resource)
                               :path [:cg/includedVariants]}
            :copyChange {:type :Resource
                         :path [:ga4gh/copyChange]}}})

(def copy-number-variant
  {:name :CopyNumberChange
   :graphql-type :object
   :implements [:Resource]
   :fields {:copyChange {:type :Resource
                         :path [:ga4gh/copyChange]}
            :location {:type :SequenceLocation}}})

