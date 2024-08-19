(ns genegraph.api.graphql.legacy-schema.contribution
  (:require [genegraph.framework.storage.rdf :as rdf]
            [clojure.string :as s])
  (:refer-clojure :exclude [agent]))

(defn  agent [context args value]
  (rdf/ld1-> value [:sepio/has-agent]))

(defn  realizes [context args value]
  (rdf/ld1-> value [:bfo/realizes]))

(defn  date [context args value]
  (rdf/ld1-> value [:sepio/activity-date]))
