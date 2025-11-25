(ns genegraph.api.graphql.schema
  (:require [genegraph.api.graphql.schema.text-search :as text-search]
            [genegraph.api.graphql.schema.resource :as model-resource]
            [genegraph.api.graphql.schema.sequence-annotation :as sequence-annotation]
            [genegraph.api.graphql.schema.conflicts :as model-conflicts]
            [genegraph.api.graphql.schema.variant :as variant]
            [genegraph.api.graphql.schema.agent :as agent]
            [genegraph.api.graphql.schema.contribution :as contribution]
            [genegraph.api.graphql.schema.assertion :as assertion]
            [genegraph.api.graphql.schema.find :as find-query]
            [genegraph.api.graphql.legacy-schema :as legacy-schema]
            [genegraph.api.graphql.common.schema-builder :as schema-builder]
            [com.walmartlabs.lacinia :as lacinia]
            [genegraph.framework.storage.rdf :refer [tx]]
            [com.walmartlabs.lacinia.schema :as lacinia-schema]))

(def rdf-to-graphql-type-mappings
  {:type-mappings
   [[:cg/EvidenceStrengthAssertion :EvidenceStrengthAssertion]
    [:cg/EvidenceLine :EvidenceLine]
    [:cg/AssertionAnnotation :AssertionAnnotation]
    [:cg/VariantPathogenicityProposition :VariantPathogenicityProposition]
    [:cg/GeneticConditionMechanismProposition :GeneticConditionMechanismProposition]
    [:cg/GeneValidityProposition :GeneValidityProposition]
    [:cg/CanonicalVariant :CanonicalVariant]
    [:ga4gh/CopyNumberChange :CopyNumberChange]
    [:ga4gh/SequenceLocation :SequenceLocation]
    [:so/SequenceFeature :SequenceFeature]
    [:cg/DosageRegion :SequenceFeature]]
   :default-type-mapping :GenericResource})

;; changing to function to benefit from dynamic type bindings
;; Note that resource-interface must appear before any
;; entities that implement resource if any of the default
;; methods in resource are used. The same would apply for any
;; interface that implements default methods.
(defn model []
  [rdf-to-graphql-type-mappings
   #_agent/agent
   text-search/text-search-query
   model-resource/resource-interface
   model-resource/generic-resource
   model-resource/resource-query
   contribution/contribution
   sequence-annotation/sequence-location
   sequence-annotation/sequence-feature
   sequence-annotation/sequence-feature-query
   variant/canonical-variant
   variant/copy-number-variant
   ;; model-conflicts/mechanism-assertion
   assertion/assertion
   assertion/evidence-line
   assertion/genetic-condition-mechanism-proposition
   assertion/variant-pathogenicity-proposition
   assertion/gene-validity-proposition
   assertion/assertion-query
   model-conflicts/conflicts-query
   model-conflicts/assertion-annotation
   model-conflicts/create-annotation
   model-conflicts/assertion-annotation-query
   find-query/query-result
   find-query/filter-ops
   find-query/filters-enum
   find-query/display-enum
   find-query/filter-call
   find-query/assertions-query
   find-query/sequence-features-query
   find-query/filter-description
   find-query/filter-option
   find-query/filters-query])


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

(defn merged-schema
  ([] (lacinia-schema/compile
       (schema-builder/schema-description (model))))
  ([options] 
   (lacinia-schema/compile
    (schema-builder/schema-description (model))
    options)))

(defn schema-description []
  (schema-builder/schema-description (model)))

(comment
  (merged-schema)
  (tap> (schema-description))
  )
