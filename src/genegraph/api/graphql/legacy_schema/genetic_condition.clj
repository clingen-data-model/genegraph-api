(ns genegraph.api.graphql.legacy-schema.genetic-condition
  (:require [genegraph.api.graphql.common.curation :as curation]))

(defn gene [context args value]
  (:gene value))

(defn disease [context args value]
  (:disease value))

(defn mode-of-inheritance [context args value]
  (:mode-of-inheritance value))

(defn actionability-curations [context args value]
  (curation/actionability-curations-for-genetic-condition (:db context) value))

(defn actionability-assertions [context args value]
  (curation/actionability-assertions-for-genetic-condition (:db context) value))

(defn gene-validity-curation [context args value]
  (curation/gene-validity-curations (:db context) value))

(defn  gene-dosage-curation [context args value]
  (curation/dosage-sensitivity-curations-for-genetic-condition (:db context) value))
