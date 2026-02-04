(ns clinvar-cnv-curation
  {:nextjournal.clerk/visibility {:code :hide}}
  (:require [nextjournal.clerk :as clerk]
            [genegraph.api.hybrid-resource :as hr]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.user :as gg]))


;; ### State of ClinVar CNVs

;; Defining CNV as Copy Gain/Loss, Del/Dup <= 1kb

;; Chart showing submissions over time

;; Chart showing more recent submissions. 2 years?

;; Chart showing CNV Size by submitter

;; ### Conflicts

;; Against dosage map

;; Reviewed by curators

;; Workflow

;; Not yet reviewed by curators

;; #### All other conflicts

;; Chart showing that dosage map is just tip of the iceberg. Pie chart?

;; ### Relative to a specific condition

;; ### Star level

;; Additional charts

;; (restore some charts from previous presentations)
