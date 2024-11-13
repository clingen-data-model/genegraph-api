(ns genegraph.api.graphql.schema.sequence-location)

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
