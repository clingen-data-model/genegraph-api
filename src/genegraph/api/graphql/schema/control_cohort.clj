;; EVAL I don't know why this exists separately from Cohort

(ns genegraph.api.graphql.schema.control-cohort)

(def control-cohort
  {:name :ControlCohort
   :graphql-type :object
   :description "A control cohort in a case control study."
   :implements [:Resource :Cohort]
   :fields {}})

