(ns genegraph.api.graphql.schema.segregation)

(def segregation
  {:name :Segregation
   :graphql-type :object
   :description "Evidence describing phenotype/genotype segregation in the context of a family."
   :implements [:Resource]
   :fields {:conditions {:type '(list :Resource)
                         :description "segregating phenotypes within the family"
                         :path [:sepio/is-about-condition]}
            :family {:type :Family
                     :description "Family used as the basis for this segregation"
                     :path [:sepio/is-about-family]}
            :sequencing_method {:type :Resource
                                :description "sequencing method used to detect the genotype under consideration"
                                :path [:sepio/sequencing-method]}
            :phenotype_positive_allele_positive_count
            {:type 'Int
             :description "Count of individuals with described phenotype noted genotype."
             :path [:sepio/phenotype-positive-allele-positive]}
            :phenotype_negative_allele_negative_count
            {:type 'Int
             :description "Count of individuals with negative phenotype and noted genotype."
             :path [:sepio/phenotype-negative-allele-negative]}
            :estimated_lod_score {:type 'Float
                                  :description "LOD score estimated from family size and count of affected individuals."
                                  :path [:sepio/estimated-lod-score]}
            :published_lod_score {:type 'Float
                                  :description "LOD score as published in the original source of the segretation evidence"
                                  :path [:sepio/published-lod-score]}
            :meets_inclusion_criteria {:type 'Boolean
                                       :description "Whether the segregation described meets the criteria for inclusion in the overall score."
                                       :path [:sepio/meets-inclusion-criteria]}
            :phenotype_free_text {:type 'String
                                  :description "Free text regarding the phenotypes."
                                  :path [:sepio/has-textual-part]}
            :proband {:type :Resource
                      :description "Associated proband"
                      :path [:sepio/is-about-proband]}}})
