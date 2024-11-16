(ns genegraph.api.graphql.schema.contribution)

#_(def contribution
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


(def contribution
  {:name :Contribution
   :graphql-type :object
   :implements [:Resource]
   :description "A contribution made by an agent to an entity."
   :fields {:agent {:type :Resource
                    :description "The agent responsible for this contribution"
                    :path [:cg/agent]}
            :date {:type 'String
                   :description "The date of this contribution"
                   :path [:cg/date]}
            ;; :artifact {:type :Resource
            ;;            :description "The artifact described in this contribution"
            ;;            :path [[:sepio/qualified-contribution :<]]}
            :role {:type :Resource
                   :description "The role realized by this contribution in relation to the described artifact."
                   :path [:cg/role]}}})
