(ns genegraph.api.graphql.schema
  (:require [genegraph.api.graphql.schema.resource :as model-resource]
            [genegraph.api.graphql.schema.statement :as model-statement]
            [genegraph.api.graphql.schema.evidence-line :as model-evidence-line]
            [genegraph.api.graphql.schema.agent :as model-agent]
            [genegraph.api.graphql.schema.contribution :as model-contribution]
            [genegraph.api.graphql.schema.find :as model-find]
            [genegraph.api.graphql.schema.conflicts :as model-conflicts]
            [genegraph.api.graphql.schema.proband-evidence :as model-proband]
            [genegraph.api.graphql.schema.variant-evidence :as model-variant-evidence]
            [genegraph.api.graphql.schema.family :as family]
            [genegraph.api.graphql.schema.value-set :as value-set]
            [genegraph.api.graphql.schema.bibliographic-resource :as model-bibliographic-resource]
            [genegraph.api.graphql.schema.segregation :as model-segregation]
            [genegraph.api.graphql.schema.case-control-evidence :as model-case-control]
            [genegraph.api.graphql.schema.cohort :as model-cohort]
            [genegraph.api.graphql.schema.case-cohort :as model-case-cohort]
            [genegraph.api.graphql.schema.control-cohort :as model-control-cohort]
            [genegraph.api.graphql.schema.variation-descriptor :as variation-descriptor]
            [genegraph.api.graphql.legacy-schema :as legacy-schema]
            [genegraph.api.graphql.common.schema-builder :as schema-builder]
            [com.walmartlabs.lacinia :as lacinia]
            [genegraph.framework.storage.rdf :refer [tx]]
            [com.walmartlabs.lacinia.schema :as lacinia-schema]))

#_(def rdf-to-graphql-type-mappings
  {:type-mappings
   [[:sepio/Assertion :Statement]
    [:sepio/Proposition :Statement]
    [:prov/Agent :Agent]
    [:sepio/EvidenceLine :Statement]
    [:dc/BibliographicResource :BibliographicResource]
    [:sepio/ProbandWithVariantEvidenceItem :ProbandEvidence]
    [:sepio/VariantEvidenceItem :VariantEvidence]
    [:ga4gh/VariationDescriptor :VariationDescriptor]
    [:sepio/FamilyCosegregation :Segregation]
    [:sepio/CaseControlEvidenceItem :CaseControlEvidence]
    [:stato/Cohort :Cohort]
    [:sepio/ValueSet :ValueSet]
    [:pco/Family :Family]]
   :default-type-mapping :GenericResource})

(def rdf-to-graphql-type-mappings
  {:type-mappings
   [[:sepio/Assertion :Statement]
    [:sepio/Proposition :Statement]
    [:prov/Agent :Agent]
    [:sepio/EvidenceLine :Statement]
    [:dc/BibliographicResource :BibliographicResource]
    [:sepio/ProbandWithVariantEvidenceItem :ProbandEvidence]
    [:sepio/VariantEvidenceItem :VariantEvidence]
    [:ga4gh/VariationDescriptor :VariationDescriptor]
    [:sepio/FamilyCosegregation :Segregation]
    [:sepio/CaseControlEvidenceItem :CaseControlEvidence]
    [:stato/Cohort :Cohort]
    [:sepio/ValueSet :ValueSet]
    [:pco/Family :Family]]
   :default-type-mapping :GenericResource})

;; changing to function to benefit from dynamic type bindings
(defn model []
  [rdf-to-graphql-type-mappings
   ;; model-resource/resource-interface
   ;; model-resource/generic-resource
   ;; model-resource/resource-query
   ;; model-resource/record-metadata-query
   ;; model-resource/record-metadata-query-result
   ;; model-statement/statement
   ;; model-evidence-line/evidence-line
   ;; model-contribution/contribution
   ;; model-proband/proband-evidence
   ;; model-variant-evidence/variant-evidence
   ;; model-agent/agent
   ;; model-find/types-enum
   ;; model-find/find-query
   ;; model-find/query-result
   model-conflicts/assertion
   model-conflicts/conflicts-query
   #_model-conflicts/assertion-conflict
   ;; variation-descriptor/variation-descriptor
   ;; value-set/value-set
   ;; family/family
   ;; model-segregation/segregation
   ;; model-case-control/case-control-evidence
   ;; model-cohort/cohort
   ;; model-case-cohort/case-cohort
   ;; model-control-cohort/control-cohort
   ;; model-bibliographic-resource/bibliographic-resource
   ])


(defn schema
  ([]
   (schema-builder/schema (model)))
  ([options]
   (schema-builder/schema (model) options)))

;; https://gist.github.com/danielpcox/c70a8aa2c36766200a95
(defn deep-merge [v & vs]
  (letfn [(rec-merge [v1 v2]
            (if (and (map? v1) (map? v2))
              (merge-with deep-merge v1 v2)
              v2))]
    (when (some identity vs)
      (reduce #(rec-merge %1 %2) v vs))))

#_(defn merged-schema
  ([] (-> (legacy-schema/schema-for-merge)
          (deep-merge (schema-builder/schema-description (model)))
          lacinia-schema/compile))
  ([options] (-> (legacy-schema/schema-for-merge)
                 (deep-merge (schema-builder/schema-description (model)))
                 (lacinia-schema/compile options))))

(defn merged-schema
  ([] (lacinia-schema/compile
       (schema-builder/schema-description (model))))
  ([options] 
   (lacinia-schema/compile
    (schema-builder/schema-description (model))
    options)))

(defn schema-description []
  (schema-builder/schema-description model))

(comment
  (merged-schema)
  )
