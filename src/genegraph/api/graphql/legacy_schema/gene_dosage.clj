(ns genegraph.api.graphql.legacy-schema.gene-dosage
  (:require [genegraph.framework.storage.rdf :as rdf]
            [clojure.string :as string]))

;; used
(defn report-date [context args value]
  (rdf/ld1-> value [:sepio/qualified-contribution :sepio/activity-date]))

;; used
(defn haplo [context args value]
  (->> (rdf/ld-> value [:bfo/has-part])
       (filter #(= 1 (rdf/ld1-> % [:sepio/has-subject :sepio/has-subject :geno/has-member-count])))
       first))

;; used
(defn triplo [context args value]
  (->> (rdf/ld-> value [:bfo/has-part])
       (filter #(= 3 (rdf/ld1-> % [ :sepio/has-subject :sepio/has-subject :geno/has-member-count])))
       first))
