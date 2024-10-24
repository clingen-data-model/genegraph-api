(ns genegraph.api.graphql.schema.contribution)

(def contribution
  {:name :Contribution
   :graphql-type :object
   :description "A contribution made by an agent to an entity."
   :fields {:attributed_to {:type :Agent
                            :description "The agent responsible for this contribution"
                            :path [:sepio/has-agent]}
            :date {:type 'String
                   :description "The date of this contribution"
                   :path [:sepio/activity-date]}
            :artifact {:type :Resource
                       :description "The artifact described in this contribution"
                       :path [[:sepio/qualified-contribution :<]]}
            :realizes {:type :Resource
                       :description "The role realized by this contribution in relation to the described artifact."
                       :path [:bfo/realizes]}}})
