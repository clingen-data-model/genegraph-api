(ns genegraph.api.graphql.legacy-schema.condition
  (:require [genegraph.framework.storage.rdf :as rdf]
            [genegraph.api.graphql.common.curation :as curation]
            [com.walmartlabs.lacinia.schema :refer [tag-with-type]]
            [clojure.string :as str]))

(defn condition-query [context args value]
  (rdf/resource (:iri args) (:db context)))

;; used
(defn ^:expire-by-value genetic-conditions [context args value]
  (curation/curated-genetic-conditions-for-disease (:db context) {:disease value}))

;; used
(defn description [context args value]
  (rdf/ld1-> value [:iao/definition]))

;; used
(defn synonyms [context args value]
  (rdf/ld-> value [:oboinowl/hasExactSynonym]))

;; used
(defn last-curated-date [context args value]
  (let [curation-dates (concat (rdf/ld-> value [[:sepio/has-object :<] ;;GENE_VALIDITY
                                                [:sepio/has-subject :<]
                                                :sepio/qualified-contribution
                                                :sepio/activity-date])
                               (rdf/ld-> value [[:rdfs/subClassOf :<] ;; ACTIONABILITY
                                                [:sepio/is-about-condition :<]
                                                :sepio/qualified-contribution
                                                :sepio/activity-date])
                               (rdf/ld-> value [:owl/equivalent-class ;; DOSAGE
                                                [:sepio/has-object :<]
                                                [:sepio/has-subject :<]
                                                :sepio/qualified-contribution
                                                :sepio/activity-date]))]
    (->> curation-dates sort last)))

;; used
(defn curation-activities [context args value]
  (curation/disease-activities (:db context) {:disease value}))

(defn diseases [context args value]
  (curation/diseases-for-resolver context args value))

