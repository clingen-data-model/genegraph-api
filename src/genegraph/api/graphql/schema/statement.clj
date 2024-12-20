(ns genegraph.api.graphql.schema.statement
  (:require [genegraph.api.graphql.schema.common :as common]))

(def statement
  {:name :Statement
   :graphql-type :object
   :description "An statement or proposition in the SEPIO model."
   :implements [:Resource]
   :fields {:subject {:type :Resource
                      :description "The subject of this statement"
                      :path [:sepio/has-subject]}
            :predicate {:type :Resource
                        :description "The predicate of this statement"
                        :path [:sepio/has-predicate]}
            :object {:type :Resource
                     :description "The object of this statement"
                     :path [:sepio/has-object]}
            :qualifier {:type '(list :Resource)
                        :description "Additional elements limiting the scope of the statement"
                        :path [:sepio/has-qualifier]}
            :score_status {:type :Resource
                           :description "The status of the score."
                           :path [:sepio/score-status]}
            :score {:type 'Float
                    :description "Numeric score of the statement. May be nil, used only when the applicable criteria calls for a numeric score in the statement or critera assessment."
                    :path [:sepio/evidence-line-strength-score]}
            :calculated_score {:type 'Float
                               :description "Calculated score as presented per the parameters of the SOP by the curation tool. May differ from the score if the curator selected a score apart from the default score."
                               :path [:sepio/calculated-score]}
            :specified_by {:type :Resource
                           :description "The criteria or method used to create the assertion or evidence line."
                           :path [:sepio/is-specified-by]
                           }
            :contributions {:type '(list :Contribution)
                            :description "Contributions made by agents towards the creation of this resource."
                            :path [:sepio/qualified-contribution]}
            :evidence {:type '(list :Resource)
                       :description "Evidence used in in support of the statement"
                       :args {:class
                              {:type 'String
                               :description "Filter list for instances of class, expects a curie, will use sub-class relationships."}
                              :transitive
                              {:type 'Boolean
                               :description "Traverse nested evidence relationships to find all evidence from root structure satisfying constrants."}
                              :hide_nested_variant_evidence
                              {:type 'Boolean
                               :description "When a variant evidence line is contained in a proband score cap evidence line, do not return the variant evidence line as a part of this result.

This field is offered to satsify a very specific use case for the ClinGen website. It is recommended to avoid using it otherwise."}}
                       :resolve common/evidence-items}
            :earliest_articles {:type '(list :Resource)
                                :description "Earlist articles reported for this statement"
                                :path [:sepio/earliest-article]}}})
