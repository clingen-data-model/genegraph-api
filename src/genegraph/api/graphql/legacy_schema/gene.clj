(ns genegraph.api.graphql.legacy-schema.gene
  (:require [genegraph.framework.storage.rdf :as rdf]
            [com.walmartlabs.lacinia.schema :refer [tag-with-type]]
            [genegraph.api.graphql.common.curation :as curation]
            [clojure.string :as str]
            [io.pedestal.log :as log]))

(defn upcase-hgnc-id [{:keys [iri]}]
  (if (re-find #"^((HGNC|hgnc):)?\d{1,5}$" iri)
    (str/upper-case iri)
    iri))

;; underlying query--probably need to keep
(defn gene-query [context args value]
  (let [gene (rdf/resource (upcase-hgnc-id args) (:db context))]
    (if (rdf/is-rdf-type? gene :so/Gene)
      gene
      (first (filter #(rdf/is-rdf-type? % :so/Gene) (rdf/ld-> gene [[:owl/sameAs :<]]))))))

(defn genes [context args value]
  (curation/genes-for-resolver context args value))

(defn curation-activities [context args value]
  (curation/activities (:db context) {:gene value}))

(def most-recent-curation-for-gene 
  (rdf/create-query "select ?contribution where {
{ ?validityproposition :sepio/has-subject ?gene .
  ?validityassertion :sepio/has-subject ?validityproposition .
  ?validityassertion :sepio/qualified-contribution ?contribution .  }
 union
{ ?dosagereport :iao/is-about ?gene .
  ?dosagereport a :sepio/GeneDosageReport .
  ?dosagereport :sepio/qualified-contribution ?contribution . }
 union
{ ?actionabilitycondition :sepio/is-about-gene ?gene .
  ?actionabilityreport :sepio/is-about-condition ?actionabilitycondition .
  ?actionabilityreport a :sepio/ActionabilityReport .
  ?actionabilityreport :sepio/qualified-contribution ?contribution .
  ?contribution :bfo/realizes :sepio/EvidenceRole . }
 ?contribution :sepio/activity-date ?activitydate }
 order by desc(?activitydate)
 limit 1"))

(defn last-curated-date [context args value]
  (some-> (most-recent-curation-for-gene (:db context) {:gene value})
          first
          (rdf/ld1-> [:sepio/activity-date])))

(defn hgnc-id [context args value]
  (->> (rdf/ld-> value [:owl/sameAs])
       (filter #(= (str (rdf/ld1-> % [:dc/source])) "https://www.genenames.org"))
       first
       rdf/curie))

(defn conditions [context args value]
  (curation/curated-genetic-conditions-for-gene (:db context) {:gene value}))

(def dosage-query
  (rdf/create-query
   [:project ['dosage_report] (cons :bgp curation/gene-dosage-bgp)]))

(defn dosage-curation [context args value]
  (first (dosage-query (:db context) {::rdf/params {:limit 1} :gene value})))

(defn chromosome-band [context args value]
  (rdf/ld1-> value [:so/chromosome-band]))
