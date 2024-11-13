(ns genegraph.api.graphql.schema
  (:require [genegraph.api.graphql.schema.resource :as model-resource]
            [genegraph.api.graphql.schema.conflicts :as model-conflicts]
            [genegraph.api.graphql.schema.evidence-strength-assertion :as es-assertion]
            [genegraph.api.graphql.legacy-schema :as legacy-schema]
            [genegraph.api.graphql.common.schema-builder :as schema-builder]
            [com.walmartlabs.lacinia :as lacinia]
            [genegraph.framework.storage.rdf :refer [tx]]
            [com.walmartlabs.lacinia.schema :as lacinia-schema]))

(def rdf-to-graphql-type-mappings
  {:type-mappings
   [[:cg/EvidenceStrengthAssertion :EvidenceStrengthAssertion]]
   :default-type-mapping :GenericResource})

;; changing to function to benefit from dynamic type bindings
(defn model []
  [rdf-to-graphql-type-mappings
   model-resource/resource-interface
   model-resource/generic-resource
   model-resource/resource-query
   ;; model-conflicts/mechanism-assertion
   es-assertion/assertion
   model-conflicts/conflicts-query
   ;; model-conflicts/resource
   ;; model-conflicts/conflict-curation
   ;; model-conflicts/create-curation
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
