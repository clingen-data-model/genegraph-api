(ns genegraph.api.graphql.legacy-schema.dosage-proposition
  (:require [genegraph.framework.storage.rdf :as rdf]
            [clojure.string :as str]
            [io.pedestal.log :as log]))

(def evidence-level-enum 
{:sepio/DosageNoEvidence :NO_EVIDENCE
 :sepio/DosageMinimalEvidence :MINIMAL_EVIDENCE
 :sepio/DosageModerateEvidence :MODERATE_EVIDENCE
 :sepio/DosageSufficientEvidence :SUFFICIENT_EVIDENCE})

;; used
;; Reflects the classification from the dosage process and not the SEPIO-derived value
;; and not the SEPIO structure of gene dosage curations generally, pending a discussion
;; with stakeholders, hopefully can be reviewed and (perhaps) deprecated in future iterations
(defn dosage-classification [context args value]
  (if (rdf/is-rdf-type? value :sepio/EvidenceLevelAssertion)
    (case (rdf/->kw (rdf/ld1-> value [:sepio/has-subject :sepio/has-predicate]))
      :geno/PathogenicForCondition (let [score (rdf/ld1-> value [:sepio/has-object])]
                                     {:label (rdf/ld1-> score [:rdfs/label])
                                      :ordinal (rdf/ld1-> score
                                                       [:sepio/has-ordinal-position])
                                      :enum_value (-> score rdf/->kw evidence-level-enum)})
      ;; For the moment, there is only a benign assertion if the score is 
      ;; 'dosage sensitivity unlikely
      :geno/BenignForCondition {:label "dosage sensitivity unlikely"
                                :ordinal 40
                                :enum_value :DOSAGE_SENSITIVITY_UNLIKELY})
    ;; If the assertion is of any other type, expect that its an assertion
    ;; the curation of the variant is outside the scope of curation
    {:label "gene associated with autosomal recessive phenotype"
     :ordinal 30
     :enum_value :ASSOCIATED_WITH_AUTOSOMAL_RECESSIVE_PHENOTYPE}))

;; used
(defn report-date [context args value]
  (rdf/ld1-> value [:sepio/qualified-contribution :sepio/activity-date]))

;;used
(defn assertion-type [context args value]
  (if (= 1 (rdf/ld1-> value [:sepio/has-subject :sepio/has-subject :geno/has-member-count]))
    :HAPLOINSUFFICIENCY_ASSERTION
    :TRIPLOSENSITIVITY_ASSERTION))

;; not used, but seems odd to leave out
(defn gene [context args value]
  (rdf/ld1-> value [:sepio/has-subject :sepio/has-subject :geno/has-location]))

;; used
(defn disease [context args value]
  (let [disease  (rdf/ld1-> value [:sepio/has-subject :sepio/has-object])]
    (when-not (= (rdf/resource :mondo/Disease) disease)
      disease)))
