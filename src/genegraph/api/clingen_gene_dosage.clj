;; Needs work -- updating the gene validity transformation into
;; the current standard spec. Will replace the existing dosage transform,
;; time permitting

(ns genegraph.api.clingen-gene-dosage
  (:require [genegraph.framework.event.store :as event-store]
            [genegraph.framework.event :as event]
            [genegraph.framework.id :as id]
            [genegraph.api.sequence-index :as idx]
            [genegraph.api.shared-data :as shared-data]
            [genegraph.api.overlaps :as overlaps]
            [clojure.spec.alpha :as spec]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [charred.api :as charred])
  (:import java.time.Instant
           java.time.OffsetDateTime))

(spec/def :gene-dosage-raw/status #(= "Closed" (:name %)))

(spec/def :gene-dosage-raw/resolutiondate string?)

(spec/def :gene-dosage-raw/resolution #(= "Complete" (:name %)))

(spec/def :gene-dosage-raw/fields (spec/keys :req-un [::resolutiondate
                                       ::status
                                       ::resolution]))

(def cg-prefix "https://genegraph.clinicalgenome.org/r/")
(def region-prefix (str cg-prefix "region-"))

(def evidence-levels
  {"3" :cg/DosageSufficientEvidence
   "2" :cg/DosageModerateEvidence
   "1" :cg/DosageMinimalEvidence
   "0" :cg/DosageNoEvidence
   "30: Gene associated with autosomal recessive phenotype"
   :cg/DosageAutosomalRecessive
   ;; assume moderate evidence for dosage sensitivity unlikely
   "40: Dosage sensitivity unlikely" :cg/DosageSensitivityUnlikely})

(def evidence-field-map
  {:cg/Haploinsufficiency
   [[:customfield_10183 :customfield_10184]
    [:customfield_10185 :customfield_10186]
    [:customfield_10187 :customfield_10188]
    [:customfield_12231 :customfield_12237]
    [:customfield_12232 :customfield_12238]
    [:customfield_12233 :customfield_12239]]
   :cg/Triplosensitivity
   [[:customfield_10189 :customfield_10190]
    [:customfield_10191 :customfield_10192]
    [:customfield_10193 :customfield_10194]
    [:customfield_12234 :customfield_12240]
    [:customfield_12235 :customfield_12241]
    [:customfield_12236 :customfield_12242]]})

(defn- format-jira-datetime-string
  "Corrects flaw in JIRA's formatting of datetime strings. By default JIRA does not
  include a colon in the offset, which is incompatible with standard java.util.time
  libraries. This inserts an appropriate offset with a regex"
  [s]
  (s/replace s #"(\d\d)(\d\d)$" "$1:$2"))

(defn region-curation? [curation]
  (= "ISCA Region Curation"
     (get-in curation [::event/data :fields :issuetype :name])))

(defn- time-str-offset-to-instant [s]
  ;; "2018-03-27T09:55:41.000-0400"
  (->> s
       format-jira-datetime-string
       OffsetDateTime/parse
       Instant/from
       str))

(defn- updated-date [interp]
  (when-let [updated (get-in interp [:fields :updated])]
    (time-str-offset-to-instant updated)))

(defn- assertion-iri [curation mechanism]
  (str cg-prefix
       (:key curation)
       "-"
       (name mechanism)
       "-"
       (updated-date curation)))

(defn- dosage-assertion-description [jira-data mechanism]
  (let [description-field (if (= :cg/Haploinsufficiency mechanism)
                            :customfield_10198
                            :customfield_10199)]
    (or (get-in jira-data [:fields description-field]) "")))

(defn dosage-proposition-object [curation dosage]
  (let [legacy-mondo-field (if (= 1 dosage) :customfield_11631 :customfield_11633)
        legacy-mondo (some->> curation
                              :fields
                              legacy-mondo-field
                              (re-find #"MONDO:\d*"))
        phenotype-field (if (= 1 dosage) :customfield_10200 :customfield_10201)
        phenotype (get-in curation [:fields phenotype-field])]
    ;; Bad IRIs prevent Jena restore. Remove spaces in IRIs
    (or (when phenotype (s/replace phenotype " " ""))
        legacy-mondo
        :mondo/HereditaryDisease)))
#_(defn- proposition-iri [curation dosage]
  (rdf/resource (str cg-prefix (:key curation) "x" dosage)))
(defn region-iri
  ([curation]
   (str region-prefix (:key curation)))
  ([curation suffix]
   (str region-prefix (:key curation) suffix)))

(defn- gene-iri [curation]
  (get-in curation [:fields :customfield_10157]))

(defn- subject-iri [curation]
  (if-let [gene (gene-iri curation)]
    (s/replace gene
               "https://www.ncbi.nlm.nih.gov/gene/"
               "https://identifiers.org/ncbigene:")
    (region-iri curation)))

(defn proposition [jira-data mechanism]
  {:type :cg/GeneticConditionMechanismProposition
   :cg/subjectFeature (subject-iri jira-data)})

(defn statement [jira-data mechanism]
  {:type :cg/Statement
   :dc/description (dosage-assertion-description jira-data mechanism)
   :cg/proposition (proposition jira-data mechanism)})

(defn jira-data->genegraph-model [e]
  (let [jira-data (::event/data e)]
    {:type :cg/Report
     :cg/statements [(statement jira-data :cg/Haploinsufficiency)
                     (statement jira-data :cg/Triplosensitivity)]}))

(defn add-dosage-data [e]
  (assoc e ::dosage-report (jira-data->genegraph-model e)))

(comment
  (def dosage-events
    "/Users/tristan/data/genegraph-neo/gene_dosage_raw-2026-05-17.edn.gz")
  (event-store/with-event-reader [r dosage-events]
    (->> (event-store/event-seq r)
         (take 1)
         (map event/deserialize)
         (mapv add-dosage-data)
         tap>
         #_(mapv ::event/key)
         #_(take 1)
         #_(run! #(p/publish (get-in api-test-app [:topics :dosage])
                             (assoc % ::event/completion-promise (promise))))))
  )
