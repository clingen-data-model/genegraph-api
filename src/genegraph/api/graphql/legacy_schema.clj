(ns genegraph.api.graphql.legacy-schema
  (:require [genegraph.api.graphql.legacy-schema.gene :as gene]
            [genegraph.api.graphql.legacy-schema.resource :as resource]
            [genegraph.api.graphql.legacy-schema.actionability :as actionability]
            [genegraph.api.graphql.legacy-schema.actionability-assertion :as ac-assertion]
            [genegraph.api.graphql.legacy-schema.gene-validity :as gene-validity]
            [genegraph.api.graphql.legacy-schema.gene-dosage :as gene-dosage]
            [genegraph.api.graphql.legacy-schema.dosage-proposition :as dosage-proposition]
            [genegraph.api.graphql.legacy-schema.contribution :as contribution]
            [genegraph.api.graphql.legacy-schema.condition :as condition]
            [genegraph.api.graphql.legacy-schema.genetic-condition :as genetic-condition]
            [genegraph.api.graphql.legacy-schema.affiliation :as affiliation]
            [com.walmartlabs.lacinia :as lacinia]
            [com.walmartlabs.lacinia.schema :as schema]
            [com.walmartlabs.lacinia.util :as util]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [genegraph.api.graphql.common.curation :as curation]))

;; Declare as a function to benefit from dynamic binding
(defn resolvers []
  {:ac-assertion/attributed-to ac-assertion/attributed-to,
   :ac-assertion/classification ac-assertion/classification,
   :ac-assertion/report-date ac-assertion/report-date,
   :ac-assertion/report-label ac-assertion/report-label,
   :ac-assertion/source ac-assertion/source,
   :actionability/tot-actionability-reports
   actionability/tot-actionability-reports,
   :actionability/tot-actionability-updated-reports
   actionability/tot-actionability-updated-reports,
   :actionability/tot-adult-failed-early-rule-out
   actionability/tot-adult-failed-early-rule-out,
   :actionability/tot-adult-gene-disease-pairs
   actionability/tot-adult-gene-disease-pairs,
   :actionability/tot-adult-outcome-intervention-pairs
   actionability/tot-adult-outcome-intervention-pairs,
   :actionability/tot-adult-score-counts
   actionability/tot-adult-score-counts,
   :actionability/tot-gene-disease-pairs
   actionability/tot-gene-disease-pairs,
   :actionability/tot-outcome-intervention-pairs
   actionability/tot-outcome-intervention-pairs,
   :actionability/tot-pediatric-failed-early-rule-out
   actionability/tot-pediatric-failed-early-rule-out,
   :actionability/tot-pediatric-gene-disease-pairs
   actionability/tot-pediatric-gene-disease-pairs,
   :actionability/tot-pediatric-outcome-intervention-pairs
   actionability/tot-pediatric-outcome-intervention-pairs,
   :actionability/tot-pediatric-score-counts
   actionability/tot-pediatric-score-counts,
   :affiliation/gene-validity-assertions
   affiliation/gene-validity-assertions,
   :condition/curation-activities condition/curation-activities,
   :condition/description condition/description,
   :condition/genetic-conditions condition/genetic-conditions,
   :condition/last-curated-date condition/last-curated-date,
   :condition/synonyms condition/synonyms,
   :dosage-proposition/assertion-type dosage-proposition/assertion-type,
   :dosage-proposition/disease dosage-proposition/disease,
   :dosage-proposition/dosage-classification
   dosage-proposition/dosage-classification,
   :dosage-proposition/report-date dosage-proposition/report-date,
   :gene/chromosome-band gene/chromosome-band,
   :gene/conditions gene/conditions,
   :gene/curation-activities gene/curation-activities,
   :gene/dosage-curation gene/dosage-curation,
   :gene/hgnc-id gene/hgnc-id,
   :gene/last-curated-date gene/last-curated-date,
   :gene-dosage/haplo gene-dosage/haplo,
   :gene-dosage/report-date gene-dosage/report-date,
   :gene-dosage/triplo gene-dosage/triplo,
   :gene-validity/animal-model gene-validity/animal-model,
   :gene-validity/attributed-to gene-validity/attributed-to,
   :gene-validity/classification gene-validity/classification,
   :gene-validity/contributions gene-validity/contributions,
   :gene-validity/disease gene-validity/disease,
   :gene-validity/gene gene-validity/gene,
   :gene-validity/legacy-json gene-validity/legacy-json,
   :gene-validity/mode-of-inheritance gene-validity/mode-of-inheritance,
   :gene-validity/report-date gene-validity/report-date,
   :gene-validity/report-id gene-validity/report-id,
   :gene-validity/specified-by gene-validity/specified-by,
   :genetic-condition/actionability-assertions
   genetic-condition/actionability-assertions,
   :genetic-condition/disease genetic-condition/disease,
   :genetic-condition/gene genetic-condition/gene,
   :genetic-condition/gene-dosage-curation
   genetic-condition/gene-dosage-curation,
   :genetic-condition/gene-validity-curation
   genetic-condition/gene-validity-curation,
   :genetic-condition/mode-of-inheritance
   genetic-condition/mode-of-inheritance,
   :gv-contribution/agent contribution/agent,
   :gv-contribution/realizes contribution/realizes,
   :resource/alternative-label resource/alternative-label,
   :resource/curie resource/curie,
   :resource/iri resource/iri,
   :resource/label resource/label,
   :resource/website-display-label resource/website-display-label,
   :resource/description resource/description
   #_#_:resource/type resource/type
   ;; queries
   :gene/gene-query gene/gene-query
   :gene/genes gene/genes
   :condition/condition-query condition/condition-query
   :condition/diseases condition/diseases
   :affiliation/affiliations affiliation/affiliations
   :affiliation/affiliation-query affiliation/affiliation-query
   :gene-validity/gene-validity-curations gene-validity/gene-validity-curations
   :gene-validity/gene-validity-assertion-query gene-validity/gene-validity-assertion-query
   :actionability/statistics-query actionability/statistics-query
   })

(defn schema-for-merge
  "Return the schema map for later compilation based on the schema to merge
  with the new, model based schema."
  []
  (-> (io/resource "new-graphql-schema-for-merge.edn")
      slurp
      edn/read-string
      (util/attach-resolvers (resolvers))))

(comment
  (schema-for-merge)
  )
