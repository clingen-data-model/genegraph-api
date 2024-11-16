(ns genegraph.api.graphql.schema.sequence-annotation)

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

(def sequence-feature
  {:name :SequenceFeature
   :graphql-type :object
   :implements [:Resource]
   :fields {:location {:type '(list :SequenceLocation)
                       :path [:ga4gh/location]}}})
