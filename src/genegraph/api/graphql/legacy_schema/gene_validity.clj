(ns genegraph.api.graphql.legacy-schema.gene-validity
  (:require [genegraph.framework.storage.rdf :as rdf]
            [genegraph.api.graphql.common.curation :as curation]
            [clojure.string :as s]
            [cheshire.core :as json]
            [io.pedestal.log :as log]))

;; CGGV:assertion_43fb4e99-e97a-4d9c-af11-79c2b09ecd2e-2019-07-24T160000.000Z
;; CGGCIEX:assertion_10075
;; https://search.clinicalgenome.org/kb/gene-validity/3210
;; "https://search.clinicalgenome.org/kb/gene-validity/CGGV:assertion_2f6d71c6-6595-49bf-a50e-fce726b22088-2018-10-03T160000.000Z"

(defn find-newest-gci-curation [id]
  (when-let [uuid-part (->> id (re-find #"(\w+_)(\w+-\w+-\w+-\w+-\w+)") last)]
    (let [proposition (rdf/resource (str "CGGV:proposition_" uuid-part))]
      (when (rdf/is-rdf-type? proposition :sepio/GeneValidityProposition)
        (rdf/ld1-> proposition [[:sepio/has-subject :<]])))))

(defn find-gciex-curation [id]
  (when (re-matches #"\d+" id)
    (let [gciex-assertion (rdf/resource (str "CGGCIEX:assertion_" id))]
      (when (rdf/is-rdf-type? gciex-assertion :sepio/GeneValidityEvidenceLevelAssertion)
        gciex-assertion))))

;; Find query. Seems unreasonably complex.
#_(defn gene-validity-assertion-query [context args value]
  (let [requested-assertion (rdf/resource (:iri args) (:db context))]
    (if (rdf/is-rdf-type? requested-assertion :sepio/GeneValidityEvidenceLevelAssertion)
      requested-assertion
      (or (rdf/ld1-> requested-assertion [[:cg/website-legacy-id :<]])
          (find-newest-gci-curation (:iri args))
          (find-gciex-curation (:iri args))))))

(defn phil-requested-assertion_be-ok-in-iris [iri]
  (s/replace iri #"assertion_([0-9a-f\-]{36}).*" "$1"))

(defn gene-validity-assertion-query [context args value]
  (let [requested-assertion (rdf/resource
                             (phil-requested-assertion_be-ok-in-iris (:iri args))
                             (:db context))]
    (if (rdf/is-rdf-type? requested-assertion :sepio/GeneValidityEvidenceLevelAssertion)
      requested-assertion
      nil)))

(def approval-query
  (rdf/create-query "
select ?contrib where
{
 ?stmt :sepio/qualified-contribution ?contrib . 
 ?contrib :bfo/realizes :sepio/ApproverRole 
}"))

(defn report-date [context args value]
  (if-let [contrib (first (approval-query value {:stmt value}))]
    (rdf/ld1-> contrib [:sepio/activity-date])))

(defn gene-validity-curations [context args value]
  (curation/gene-validity-curations-for-resolver context args value))

;; used
(defn classification [context args value]
  (rdf/ld1-> value [:sepio/has-object]))

;; used
(defn gene [context args value]
  (rdf/ld1-> value [:sepio/has-subject :sepio/has-subject]))

;; used
(defn disease [context args value]
  (rdf/ld1-> value [:sepio/has-subject :sepio/has-object]))

;; used
(defn mode-of-inheritance [context args value]
  (rdf/ld1-> value [:sepio/has-subject :sepio/has-qualifier]))

(def primary-attribution-query
  (rdf/create-query
   "select ?agent where {
    ?assertion :sepio/qualified-contribution ?contribution . 
    ?contribution :bfo/realizes :sepio/ApproverRole ;
    :sepio/has-agent ?agent . } 
   limit 1 "))

;; used
(defn attributed-to [context args value]
  (first (primary-attribution-query (:db context) {:assertion value})))

;; used
(defn contributions [context args value]
  (rdf/ld-> value [:sepio/qualified-contribution]))

;; used
(defn specified-by [context args value]
  ;; this returns a resource
  (rdf/ld1-> value [:sepio/is-specified-by]))

;; used
(defn legacy-json [_ _ value]
  (rdf/ld1-> value [[:bfo/has-part :<] :bfo/has-part :cnt/chars]))

;; used
;; also ugly AF. this could be a major source of slowness
;; This is *really* bad

;; TODO should be able to remove first part after
;; releasing full GCI
(defn report-id [context args value]
  (let [curie (rdf/curie value)]
    ;; match vintage style curie with date time stamp at the end
    (if (re-find #"\.\d{3}Z$" curie)
      (-> (legacy-json nil nil value)
          (json/parse-string true)
          :report_id)
      ;; match GCI Express is always nil
      (if (re-find #"^CGGCIEX:assertion_\d+$" curie)
        nil
        ;; match gci refactor
        (when-let [proposition-id (-> (rdf/ld1-> value [:sepio/has-subject])
                                      str
                                      (s/split #"/")
                                      last)]
          (re-find #"[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$"
                   proposition-id))))))

(def animal-model-query
  (rdf/create-query "
select ?s where {
 ?s :bfo/has-part ?resource ;
 a :sepio/GeneValidityReport ;
 :cg/is-animal-model-only ?animal }"))

;; used
(defn animal-model [context args value]
  (let [res (first (animal-model-query (:db context) {:resource value}))]
    (if res
      (case (rdf/ld1-> res [:cg/is-animal-model-only])
        "YES" true
        "NO"  false
        nil)
      nil)))

