(ns clinvar-acmg
  {:nextjournal.clerk/visibility {:code :hide}}
  (:require [nextjournal.clerk :as clerk]
            [genegraph.api.hybrid-resource :as hr]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage :as storage]
            [genegraph.user :as gg]
            [genegraph.api.filter :as filters]
            [genegraph.api.graphql.schema.conflicts :as conflicts]))


;; ClinVar

;; What information about CNVs is in ClinVar?

;; How can it be used?

;; How can it be improved?

;; What's in there?

^{::clerk/visibility {:result :hide}}
(def tdb @(get-in gg/api-test-app [:storage :api-tdb :instance]))
^{::clerk/visibility {:result :hide}}
(def object-db @(get-in gg/api-test-app [:storage :object-db :instance]))
^{::clerk/visibility {:result :hide}}
(def hybrid-db {:tdb tdb :object-db object-db})

^{::clerk/visibility {:result :hide}}
(defn query->hr [query-form]
  (let [q (rdf/create-query query-form)]
    (rdf/tx tdb
            (mapv #(hr/hybrid-resource % hybrid-db) (q tdb)))))

^{::clerk/visibility {:result :hide}}
(def cnv-query
  (rdf/create-query "
select ?v where {
?v :ga4gh/copyChange ?c
}
"))
^{::clerk/visibility {:result :hide}}
(def cnvs
  (rdf/tx tdb
          (mapv #(hr/hybrid-resource % hybrid-db) (cnv-query tdb))))

^{::clerk/visibility {:result :hide}}
(def cnv-assertions
  (query->hr "
select ?x where {
?x a :cg/EvidenceStrengthAssertion ;
 :cg/subject ?prop .
?prop a :cg/VariantPathogenicityProposition .
}
"))

(clerk/html
 [:div.font-sans.flex.gap-3.items-center
  [:div.text-2xl.font-bold
   (count cnv-assertions)]
  [:span.mt-0.text-sm "Copy number variant submissions in ClinVar" ]])

;; cnvs without a date
(->> cnv-assertions
     (filter :cg/dateLastEvaluated)
     count
     (- (count cnv-assertions)))

;; CNVs evaluated 2016 and later
(let [cnv-submission-dates (->> cnv-assertions
                                (filter :cg/dateLastEvaluated)
                                (mapv #(subs (get % :cg/dateLastEvaluated "1900") 0 4))
                                frequencies
                                (sort-by key)
                                (take-last 10))]
  #_(mapv val cnv-submission-dates)
  (clerk/plotly {:data [{:x (mapv key cnv-submission-dates)
                         :y (mapv val cnv-submission-dates)
                         :type "bar"}]}))

(rdf/tx
 tdb
 (let [cnv-submitters (->> cnv-assertions
                           (mapv #(-> % :cg/contributions first :cg/agent))
                           frequencies
                           (sort-by val)
                           (take-last 10))]
   (clerk/plotly
    {:data
     [{:x (mapv #(-> % key (rdf/resource tdb) (rdf/ld1-> [:rdfs/label]))
         cnv-submitters)
       :y (mapv val cnv-submitters)
       :type "bar"}]})))

^{::clerk/visibility {:result :hide}}
(defn newer-than [assertion date]
  (if-let [assertion-date (:cg/dateLastEvaluated assertion)]
    (<= 0 (compare assertion-date date))
    false))

;; all submissions

(rdf/tx
 tdb
 (let [cnv-submitters (->> cnv-assertions
                           (mapv #(-> % :cg/contributions first :cg/agent))
                           frequencies
                           (sort-by val)
                           (take-last 10))]
   (clerk/plotly
    {:data
     [{:x (mapv #(-> % key (rdf/resource tdb) (rdf/ld1-> [:rdfs/label]))
                cnv-submitters)
       :y (mapv val cnv-submitters)
       :type "bar"}]})))


;; All submissions since the beginning of 2019
(rdf/tx
 tdb
 (let [cnv-submitters (->> cnv-assertions
                           (filter #(newer-than % "2019"))
                           (mapv #(-> % :cg/contributions first :cg/agent))
                           frequencies
                           (sort-by val)
                           (take-last 10))]
   (clerk/plotly
    {:data
     [{:x (mapv #(-> % key (rdf/resource tdb) (rdf/ld1-> [:rdfs/label]))
                cnv-submitters)
       :y (mapv val cnv-submitters)
       :type "bar"}]})))

;; All submissions since the beginning of 2024
(rdf/tx
 tdb
 (let [cnv-submitters (->> cnv-assertions
                           (filter #(newer-than % "2024"))
                           (mapv #(-> % :cg/contributions first :cg/agent))
                           frequencies
                           (sort-by val)
                           (take-last 10))]
   (clerk/plotly
    {:data
     [{:x (mapv #(-> % key (rdf/resource tdb) (rdf/ld1-> [:rdfs/label]))
                cnv-submitters)
       :y (mapv val cnv-submitters)
       :type "bar"}]})))

(def queries
  [{:label "Deletions with >= 35 Genes"
    :description "Copy Number Loss variants in ClinVar that meet the criteria for Likely Pathogenic according to the ACMG guidelines based on gene count alone."
    :filters [{:filter :proposition_type
               :argument "CG:VariantPathogenicityProposition"}
              {:filter :copy_change
               :argument "EFO:0030067"}
              {:filter :gene_count_min
               :argument "CG:Genes35"}]}
   {:label "Duplications with >= 50 Genes"
    :description "Copy Number Gain variants in ClinVar that meet the criteria for Likely Pathogenic according to the ACMG guidelines based on gene count alone."
    :filters [{:filter :proposition_type
               :argument "CG:VariantPathogenicityProposition"}
              {:filter :copy_change
               :argument "EFO:0030070"}
              {:filter :gene_count_min
               :argument "CG:Genes50"}]}
   {:label "Deletions with complete overlap of HI 3 features"
    :description "Copy Number Loss variants in ClinVar that have a complete overlap with a gene or region classified as Haploinsufficient with sufficient evidence in the ClinGen Dosage Map."
    :filters [{:filter :proposition_type
               :argument "CG:VariantPathogenicityProposition"}
              {:filter :copy_change
               :argument "EFO:0030067"}
              {:filter :complete_overlap_with_feature_set
               :argument "CG:HaploinsufficiencyFeatures"}]}
   {:label "Duplications with complete overlap of TS 3 features"
    :description "Copy Number Gain variants in ClinVar that have a complete overlap with a gene or region classified as Triplosensitive with sufficient evidence by the ClinGen Dosage Map."
    :filters [{:filter :proposition_type
               :argument "CG:VariantPathogenicityProposition"}
              {:filter :copy_change
               :argument "EFO:0030070"}
              {:filter :complete_overlap_with_feature_set
               :argument "CG:TriplosensitivityFeatures"}]}
   {:label "Deletions with complete overlap of AD/XL gene-disease-validity"
    :description "Copy Number Loss variants in ClinVar that have a complete overlap with a gene classified as Moderate or greater in the ClinGen Gene-Disease Validity curation framework with an Autosomal Dominant or X-Linked inheritance pattern."
    :filters [{:filter :proposition_type
               :argument "CG:VariantPathogenicityProposition"}
              {:filter :copy_change
               :argument "EFO:0030067"}
              {:filter :complete_overlap_with_feature_set
               :argument "CG:GeneValidityModerateAndGreaterADXL"}]}
   {:label "Deletions with complete overlap with AR genes not AD genes"
    :description "Copy Number Loss variants in ClinVar that have a complete overlap with a gene classified as Moderate or greater in the ClinGen Gene-Disease Validity curation framework with an Autosomal Recessive inheritance pattern or a gene classified as having an Autosomal Recessive pattern in Gene Dosage (Score 30), excluding variants that overlap a gene associated with an Autosomal Dominant condition."
    :filters [{:filter :proposition_type
               :argument "CG:VariantPathogenicityProposition"}
              {:filter :copy_change
               :argument "EFO:0030067"}
              {:filter :complete_overlap_with_feature_set
               :argument "CG:ARGene"}
              {:filter :complete_overlap_with_feature_set
               :argument "CG:GeneValidityModerateAndGreaterADXL"
               :operation "not_exists"}
              {:filter :complete_overlap_with_feature_set
               :argument "CG:HaploinsufficiencyFeatures"
               :operation "not_exists"}
              {:filter :gene_count_min
               :argument "CG:Genes35"
               :operation "not_exists"}]}
   {:label "Deletions with partial overlap of HI genes"
    :description "Copy Number Loss variants in ClinVar that have a partial overlap with a gene classified as Haploinsufficenty genes in the ClinGen Dosage map. Excluding variants that could be classified as pathogenic for another reason."
    :filters [{:filter :proposition_type
               :argument "CG:VariantPathogenicityProposition"}
              {:filter :copy_change
               :argument "EFO:0030067"}
              {:filter :partial_overlap_with_feature_set
               :argument "CG:HaploinsufficiencyFeatures"}
              {:filter :complete_overlap_with_feature_set
               :argument "CG:HaploinsufficiencyFeatures"
               :operation "not_exists"}
              {:filter :gene_count_min
               :argument "CG:Genes35"
               :operation "not_exists"}]}
   {:label "Deletions with partial overlap of AD/XL Gene Validity features"
    :description "Copy Number Loss variants in ClinVar that have a partial overlap with a gene classified as having Moderate or greater evidence and AD/XL inheritance pattern in the ClinGen Gene Validity framework; Excluding variants that could be classified as pathogenic for another reason."
    :filters [{:filter :proposition_type
               :argument "CG:VariantPathogenicityProposition"}
              {:filter :copy_change
               :argument "EFO:0030067"}
              {:filter :partial_overlap_with_feature_set
               :argument "CG:GeneValidityModerateAndGreaterADXL"}
              {:filter :partial_overlap_with_feature_set
               :argument "CG:HaploinsufficiencyFeatures"
               :operation "not_exists"}
              {:filter :complete_overlap_with_feature_set
               :argument "CG:HaploinsufficiencyFeatures"
               :operation "not_exists"}
              {:filter :gene_count_min
               :argument "CG:Genes35"
               :operation "not_exists"}]}
   {:label "Deletions with partial overlap of autosomal recessive genes"
    :description "Copy Number Loss variants in ClinVar that have a partial overlap with a gene associated with an autosomal recessive condition. Excluding variants that could be classified as pathogenic for another reason."
    :filters [{:filter :proposition_type
               :argument "CG:VariantPathogenicityProposition"}
              {:filter :copy_change
               :argument "EFO:0030067"}
              {:filter :partial_overlap_with_feature_set
               :argument "CG:ARGene"}
              {:filter :partial_overlap_with_feature_set
               :argument "CG:GeneValidityModerateAndGreaterADXL"
               :operation "not_exists"}
              {:filter :complete_overlap_with_feature_set
               :argument "CG:GeneValidityModerateAndGreaterADXL"
               :operation "not_exists"}
              {:filter :partial_overlap_with_feature_set
               :argument "CG:HaploinsufficiencyFeatures"
               :operation "not_exists"}
              {:filter :complete_overlap_with_feature_set
               :argument "CG:HaploinsufficiencyFeatures"
               :operation "not_exists"}
              {:filter :gene_count_min
               :argument "CG:Genes35"
               :operation "not_exists"}]}
   {:label "Variants with no applicable ClinGen data"
    :description "Copy Number Loss variants in ClinVar that have no overlap with features annotated in ClinGen knowledgebases."
    :filters [{:filter :proposition_type
               :argument "CG:VariantPathogenicityProposition"}
              {:filter :copy_change
               :argument "EFO:0030067"}
              {:filter :partial_overlap_with_feature_set
               :argument "CG:ARGene"
               :operation "not_exists"}
              {:filter :complete_overlap_with_feature_set
               :argument "CG:ARGene"
               :operation "not_exists"}
              {:filter :partial_overlap_with_feature_set
               :argument "CG:GeneValidityModerateAndGreaterADXL"
               :operation "not_exists"}
              {:filter :complete_overlap_with_feature_set
               :argument "CG:GeneValidityModerateAndGreaterADXL"
               :operation "not_exists"}
              {:filter :partial_overlap_with_feature_set
               :argument "CG:HaploinsufficiencyFeatures"
               :operation "not_exists"}
              {:filter :complete_overlap_with_feature_set
               :argument "CG:HaploinsufficiencyFeatures"
               :operation "not_exists"}
              {:filter :gene_count_min
               :argument "CG:Genes35"
               :operation "not_exists"}]}
   {:label "Annotated assertions"
    :description "Assertions that have been annotated by curators."
    :filters [{:filter :proposition_type
               :argument "CG:VariantPathogenicityProposition"}
              {:filter :has_annotation}]}
   #_{:label "Other annotated assertions"
      :description "Assertions that have been annotated by curators, without making an assessment about the quality of the submission or the "
      :filters [{:filter :proposition_type
                 :argument "CG:VariantPathogenicityProposition"}
                {:filter :has_annotation
                 :argument "CG:NoAssessment"}]}])

(rdf/tx
 tdb
 (let [q (filters/compile-filter-query
          [:bgp ['x :rdf/type :cg/EvidenceStrengthAssertion]]
          (-> queries first :filters))]
   (count (q tdb))))

(defn filter-result [query]
  (rdf/tx
   tdb
   (let [q (filters/compile-filter-query
            [:bgp ['x :rdf/type :cg/EvidenceStrengthAssertion]]
            (:filters query))]
     (into [] (q tdb)))))

(defn add-variant [assertion]
  (assoc assertion
         :variant
         (storage/read object-db [:objects (:cg/subject assertion)])))

^{::clerk/visibility {:result :hide}}
(defn query-detail [query]
  (let [result (filter-result query)
        sample (mapv #(-> (hr/hybrid-resource % hybrid-db)) (take 5 result))]
    (rdf/tx
     tdb
     (clerk/html
      [:div
       {:class "divide-y divide-gray-100"}
       [:div
        {:class "px-4 sm:px-0"}
        [:h3
         {:class "text-base/7 font-semibold text-gray-900"}
         (:label query)]
        [:p
         {:class "mt-1 text-xl text-blue-900 font-medium text-wrap"}
         (count result)]
        [:p
         {:class "mt-1 text-sm/6 text-gray-500 text-wrap"}
         (:description query)]]
       [:div
        {:class "mt-6 border-t border-gray-100"}
        [:dl
         {:class "divide-y divide-gray-100"}
         (for [a sample]
           [:div
            {:class "px-4 py-6 sm:grid sm:grid-cols-3 sm:gap-4 sm:px-0"}
            
            [:dt
             {:class "text-sm/6 font-medium text-gray-900"}]
            [:dd
             {:class "mt-1 text-sm/6 text-gray-700 sm:col-span-2 sm:mt-0"}
             "Margot Foster"]])]]
       #_[:div
          {:class "mt-6 border-t border-gray-100"}
          [:dl
           {:class "divide-y divide-gray-100"}
           [:div
            {:class "px-4 py-6 sm:grid sm:grid-cols-3 sm:gap-4 sm:px-0"}
            [:dt {:class "text-sm/6 font-medium text-gray-900"} "Full name"]
            [:dd
             {:class "mt-1 text-sm/6 text-gray-700 sm:col-span-2 sm:mt-0"}
             "Margot Foster"]]]]]))))


(query-detail (first queries))

(query-detail (second queries))




#_(for [q (take 3 queries)]
    (query-detail q))
