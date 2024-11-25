(ns clinvar
  {:nextjournal.clerk/visibility {:code :hide}}
  (:require [nextjournal.clerk :as clerk]
            [genegraph.api.hybrid-resource :as hr]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.user :as gg]
            [genegraph.api.graphql.schema.conflicts :as conflicts]))

;; We want ClinVar to be at least as useful of a resource for the assessment of
;; copy number variation as it is for sequence variants.

;; Part of this effort
;; involves cleaning up the existing set of copy number variation in ClinVar
;; to make it as useful as possible, and also so that new submissions
;; don't get drowned out in the noise of older submissions of dubious
;; quality.

;; The other part is constructing and applying standards for how classifications
;; of copy number variants should be represented in public knowledgebases.
;; Reviewing copy number variants in ClinVar will help identify gaps
;; in these standards, as well as allowing us to suggest updates for or
;; flag these classifications as standards are adopted.

(clerk/html
 [:div.font-sans.flex.gap-3.items-center
  [:div.text-2xl.font-bold
   (let [tdb @(get-in gg/api-test-app [:storage :api-tdb :instance])
         object-db @(get-in gg/api-test-app [:storage :object-db :instance])
         q (rdf/create-query "
select ?v where {
?v :ga4gh/copyChange ?c
}
")]
     (rdf/tx tdb
             (->> (q tdb)
                  count)))]
  [:span.mt-0.text-sm "Copy number variants in ClinVar" ]])

(clerk/html
 [:div
  [:div.font-sans.text-3xl.font-semibold.text-gray-900.mb-0
   "Stage 1"]
  [:div.text-gray-500.font-sans.font-semibold.text-sm.mt-0 "Clearly incorrect classifications against existing knowledgebases (ClinGen Dosage Map)"]])

;; Within the [existing SOP](https://docs.google.com/document/d/1HQ8jngoMPaI-1IgXOGC_gm3SnNW4toU_/edit) for curation of sequence variant calls in ClinVar
;; we already have a category for flagging *older claims that do not
;; account for recent evidence*

;; This type of curation seems to fit well within this category, although
;; when presenting the reason for a variant being flagged in ClinVar,
;; we will want to be specific that the evidence is the ClinGen Dosage
;; Map, as well as identify the record in the dosage map, with the
;; date that the record was saved.

;; By starting here we are working against variation that already have
;; a well defined standard for classification, both in patient reporting
;; as well as classification in public knowledgebases. We can make a
;; meaningful impact quickly, as well as engage submitters of CNVs to
;; ClinVar early on.

;; That said, these variants are going to be relatively modest in number (100's)
(clerk/html
 [:div.font-sans.flex.gap-3.items-center
  [:div.text-2xl.font-bold
   (let [tdb @(get-in gg/api-test-app [:storage :api-tdb :instance])
         object-db @(get-in gg/api-test-app [:storage :object-db :instance])]
     (rdf/tx tdb
             (->> (conflicts/conflicts-query-fn
                   {:tdb tdb
                    :object-db object-db}
                   nil
                   nil)
                  (remove #(= :cg/OtherClassification
                              (:cg/classification %)))
                  count)))]
  [:span.mt-0.text-sm "Classifications of B/LB/VUS of a copy number loss variant with" [:span.font-bold.mx-1 "complete"] "overlap with a ClinGen haploinsufficiency gene"]])

;; We will need to add to this set:
;; * Complete overlaps with haploinsufficiency regions
;; * Complete overlaps of copy number gains with triplosensitivity regions, genes
;; * Partial overlaps with haploinsufficiency genes that result in a clear negation of gene function (relative to MANE transcript):
;;   * More than 50% of CDS deleted
;;   * Deletion of the start codon
;;   * Deletion of the first exon
;;   * Deletion of an odd number of splice signals
;;   * Frameshift

;; We will pay attention to the age of a dosage curation.
;; ClinVar assertions conflicting with dosage curations
;; that are old (prior to 2020), have no recent evidence
;; added to them, and do not relate to an especially
;; well-established gene would not be flagged until the
;; the dosage record is updated.


(clerk/html
 [:div
  [:div.font-sans.text-3xl.font-semibold.text-gray-900.mb-0
   "Stage 2"]
  [:div.text-gray-500.font-sans.font-semibold.text-sm.mt-0 "Conflicts between copy number variants not covered by ClinGen Dosage Map"]])


;; As with Stage 1, within our [SOP](https://docs.google.com/document/d/1HQ8jngoMPaI-1IgXOGC_gm3SnNW4toU_/edit)
;; we already have a category for flagging *outlier claims with insufficient
;; supporting evidence*.

(clerk/html
 [:div.font-sans.flex.gap-3.items-center
  [:div.text-2xl.font-bold "2009"]
  [:span.mt-0.text-sm "Copy number loss conflicts"]])

(clerk/html
 [:div.font-sans.flex.gap-3.items-center
  [:div.text-2xl.font-bold "1787"]
  [:span.mt-0.text-sm "Copy number gain conflicts"]])

;; These are calculated applying the most conservative criteria possible
;; (complete overlap of genomic content).
;; Will expect to curate a bit more after taking into account partial overlaps.

;; With assessments against sequence variants we are assessing conflicts
;; of identical variants. With CNVs, identical variants are rare, but
;; similar variants are common. Here, we are going to assess similarity
;; by content of overlapping gene features. This should solve
;; some of the problems of comparing variants mapped to different
;; genome builds
 
;; A brief exploration of some of these conflicts offers some potential ways
;; to think about them:

;; #### Likely dosage sensitive gene
;; https://www.ncbi.nlm.nih.gov/clinvar/variation/152675
;; https://www.ncbi.nlm.nih.gov/clinvar/variation/1412663

;; #### Potential liftover problems
;; https://www.ncbi.nlm.nih.gov/clinvar/variation/1703631
;; https://www.ncbi.nlm.nih.gov/clinvar/variation/148533
;; https://www.ncbi.nlm.nih.gov/clinvar/variation/666433

;; Comment: This is an oddball one - the variants are represented in different genome builds and using a UCSC liftover they do overlap, however comparing the liftover from hg38 to hg37 it looks like the coordinates listed in ClinVar for the VUS CNV are incorrect (it is not a perfect liftover using UCSC, but even still the discrepancy between what UCSC gives and what ClinVar has is quite large) - liftover from hg38 to hg37 according to UCSC should be chr1:146,449,865-147,706,477, which does overlap with the path variant - My concern here is that if the hg37 coordinates were what was submitted by the ISCA site, then this ClinVar CNV representation is incorrect and in fact does not overlap with the path variant. This is one we will need to go back to the ISCA site for the VUS to have them confirm the coordinates for the intended CNV

;; #### Autosomal Recessive / Carrier Status
;; https://www.ncbi.nlm.nih.gov/clinvar/variation/1527293
;; https://www.ncbi.nlm.nih.gov/clinvar/variation/2425657

;; Comment: The path variant has a flagged submission that is redundant with another submission - Also the path variant partially overlaps MSTO1 which is associated with AR myopathy (per GDV, but may also be associated with AD disease) and LOF is the proposed mech of disease. This gene is not included in the coordinates for the VUS variatn in column B - so I don't think we can call these in conflict

