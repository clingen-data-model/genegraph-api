(ns genegraph.api.graphql.legacy-schema.actionability
  (:require [genegraph.framework.storage.rdf :as rdf]
            [clojure.string :as str]
            [io.pedestal.log :as log]))

(defn actionability-query [context args value]
  (rdf/resource (:iri args) (:db context)))

(def report-date-query 
  (rdf/create-query 
   (str "select ?contribution where "
        " { ?report :sepio/qualified-contribution ?contribution . "
        "   ?contribution :bfo/realizes :sepio/EvidenceRole . "
        "   ?contribution :sepio/activity-date ?date } "
        " order by desc(?date) "
        " limit 1 ")))

(defn report-date [context args value]
  (some-> (report-date-query (:db context) {:report value})
          first
          :sepio/activity-date
          first))

(defn report-id [context args value]
  (->> value str (re-find #"\w+$")))

(defn wg-label [context args value]
  (rdf/ld1-> value [:sepio/qualified-contribution :sepio/has-agent :rdfs/label]))

(defn classification-description [context args value]
  "View report for scoring details")

(defn conditions [context args value]
  (rdf/ld-> value [:sepio/is-about-condition]))

(defn source [context args value]
  (rdf/ld1-> value [:dc/source]))

(def wg-search-actionability-reports
  (rdf/create-query (str "select ?qc where { ?s a :sepio/ActionabilityReport . "
                       "?s :sepio/qualified-contribution ?qc . "
                       "?qc :bfo/realizes :sepio/EvidenceRole ."
                       "?qc :sepio/has-agent ?agent . }"
                       )))

(defn statistics-query [context args value]
  1)

(defn tot-actionability-reports [context args value]
  ((rdf/create-query "select ?s where { ?s a :sepio/ActionabilityReport }" {::rdf/distinct false})
   (:db context)
   {::rdf/params {:type :count}}))

(defn tot-actionability-updated-reports [context args value]
  (let [updated-reports-query (str "select ?s where { ?s a :sepio/ActionabilityReport . "
                                  "?s :dc/has-version ?v . "
                                  "FILTER regex(?v, '[2-9].[0-9].[0-9]') }")]
  ((rdf/create-query updated-reports-query {::rdf/distinct false})
   (:db context)
   {::rdf/params {:type :count}})))

(def uniq-disease-pairs (rdf/create-query (str "select ?gene where { "
                             "?part a :cg/ActionabilityAssertionForPreferredCondition . "
                             "?part :sepio/has-object ?disease . "
                             "?part :sepio/has-subject ?gene . "
                             "?s :bfo/has-part ?part . "
                             "?s a :sepio/ActionabilityReport . "
                             "?s :sepio/qualified-contribution ?qc . "
                             "?qc :sepio/has-agent ?wg } "
                             "GROUP BY ?gene ?disease ") {::rdf/distinct false}))

(defn tot-gene-disease-pairs [context args value]
  (uniq-disease-pairs (:db context) {::rdf/params {:type :count}}))

(defn tot-adult-gene-disease-pairs [context args value]
  (uniq-disease-pairs (:db context) {::rdf/params {:type :count} :wg :cg/AdultActionabilityWorkingGroup}))

(defn tot-pediatric-gene-disease-pairs [context args value]
  (uniq-disease-pairs (:db context) {::rdf/params {:type :count} :wg :cg/PediatricActionabilityWorkingGroup}))

(def score-counts (rdf/create-query (str "select ?s where { "
                       "?s a :sepio/ActionabilityReport . "
                       "?s :sepio/qualified-contribution ?qc . "
                       "?qc :bfo/realizes :sepio/ApproverRole . "
                       "?qc :sepio/has-agent ?wg }") {::rdf/distinct false}))


(defn tot-wg-score-counts [db wg]
  (let [records (if (some? wg)
                  (score-counts db {:wg wg})
                  (score-counts db))
        counts (->> records
                    (mapcat #(rdf/ld-> % [ :cg/has-total-actionability-score ]))
                    frequencies
                    sort)]
    counts))

(defn tot-adult-score-counts [context args value]
  (str/join " "
            (map #(str/join "=" %)
                 (tot-wg-score-counts (:db context)
                                      :cg/AdultActionabilityWorkingGroup))))

(defn tot-pediatric-score-counts [context args value]
  (str/join " "
            (map #(str/join "=" %)
                 (tot-wg-score-counts (:db context)
                                      :cg/PediatricActionabilityWorkingGroup))))

(defn tot-outcome-intervention-pairs [context args value]
  (->> (tot-wg-score-counts (:db context) nil)
       (map second)
       (reduce +)))

(defn tot-adult-outcome-intervention-pairs [context args value]
  (->> (tot-wg-score-counts (:db context)  :cg/AdultActionabilityWorkingGroup)
       (map second)
       (reduce +)))

(defn tot-pediatric-outcome-intervention-pairs [context args value]
  (->> (tot-wg-score-counts (:db context) :cg/PediatricActionabilityWorkingGroup)
       (map second)
       (reduce +)))

(def rule-out (rdf/create-query (str "select ?p where { "
                       "?s a :sepio/ActionabilityReport . "
                       "?s :bfo/has-part ?p . "
                       "?p :sepio/has-predicate :sepio/InsufficientEvidenceForActionabilityEarlyRuleOut . "
                       "?s :sepio/qualified-contribution ?qc . "
                       "?qc :sepio/has-agent ?wg }") {::rdf/distinct false}))

(defn tot-adult-failed-early-rule-out [context args value]
  (rule-out (:db context)
            {::rdf/params {:type :count}
             :wg :cg/AdultActionabilityWorkingGroup}))

(defn tot-pediatric-failed-early-rule-out [context args value]
  (rule-out (:db context)
            {::rdf/params {:type :count}
             :wg :cg/PediatricActionabilityWorkingGroup}))
