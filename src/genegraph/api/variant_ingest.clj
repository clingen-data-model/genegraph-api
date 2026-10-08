(ns genegraph.api.variant-ingest
  )

;; Clinvar
;; VCF (gnomAD CNV and SV)
;; mayo ISCN


;; first pass filter (reduces number of variants for memory usage
;; ClinVar is-cnv? has-score?
;; VCF pass QC? is-cnv? has-score?
;; mayo ISCN is-cnv? has-score?

(defmulti if->ga4gh-bundle ::type)




