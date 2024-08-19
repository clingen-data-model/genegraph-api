(ns genegraph.api.graphql.legacy-schema.actionability-assertion
  (:require [genegraph.framework.storage.rdf :as rdf]))

(def report-date-query 
  (rdf/create-query 
   (str "select ?contribution where "
        " { ?report :bfo/has-part ?assertion . "
        "   ?report :sepio/qualified-contribution ?contribution . "
        "   ?contribution :bfo/realizes :sepio/EvidenceRole . "
        "   ?contribution :sepio/activity-date ?date } "
        " order by desc(?date) "
        " limit 1 ")))

(defn report-date [context args value]
  (some-> (report-date-query (:db context) {:assertion value})
          first
          (rdf/ld1-> [:sepio/activity-date])))

(defn source [context args value]
  (rdf/ld1-> value [[:bfo/has-part :<] :dc/source]))

(defn classification [context args value]
  (rdf/ld1-> value [:sepio/has-predicate]))

(defn report-label [context args value]
  (rdf/ld1-> value [[:bfo/has-part :<] :rdfs/label]))

(defn attributed-to [context args value]
  (rdf/ld1-> value [[:bfo/has-part :<] :sepio/qualified-contribution :sepio/has-agent]))
