(ns genegraph.api.dosage
  (:require [clojure.java.io :as io]
            [clojure.string :as s]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.event :as event]
            [genegraph.framework.id :as id]
            [genegraph.api.sequence-index :as idx]
            [genegraph.api.shared-data :as shared-data]
            [clojure.spec.alpha :as spec]
            [io.pedestal.interceptor :as interceptor])
  (:import java.time.Instant
           java.time.OffsetDateTime))

(def evidence-levels {"3" :cg/DosageSufficientEvidence
                      "2" :cg/DosageModerateEvidence
                      "1" :cg/DosageMinimalEvidence
                      "0" :cg/DosageNoEvidence
                      "30: Gene associated with autosomal recessive phenotype"
                      :cg/DosageAutosomalRecessive
                      ;; assume moderate evidence for dosage sensitivity unlikely
                      "40: Dosage sensitivity unlikely" :cg/DosageSensitivityUnlikely})

(spec/def ::status #(= "Closed" (:name %)))

(spec/def ::resolutiondate string?)

(spec/def ::resolution #(= "Complete" (:name %)))

(spec/def ::fields (spec/keys :req-un [::resolutiondate
                                       ::status
                                       ::resolution]))

(def cg-prefix "http://dataexchange.clinicalgenome.org/dci/")
(def region-prefix (str cg-prefix "region-"))

(def build-location {:grch38 :customfield_10532
                     :grch37 :customfield_10160})



(defn- format-jira-datetime-string
  "Corrects flaw in JIRA's formatting of datetime strings. By default JIRA does not
  include a colon in the offset, which is incompatible with standard java.util.time
  libraries. This inserts an appropriate offset with a regex"
  [s]
  (s/replace s #"(\d\d)(\d\d)$" "$1:$2"))

(defn- time-str-offset-to-instant [s]
  ;; "2018-03-27T09:55:41.000-0400"
  (->> s
       format-jira-datetime-string
       OffsetDateTime/parse
       Instant/from
       str))

(defn- resolution-date [interp]
  (when-let [resolution-date (get-in interp [:fields :resolutiondate])]
    (time-str-offset-to-instant resolution-date)))

(defn- updated-date [interp]
  (when-let [updated (get-in interp [:fields :updated])]
    (time-str-offset-to-instant updated)))

(defn- gene-iri [curation]
  (when-let [gene (get-in curation [:fields :customfield_10157])]
    (rdf/resource gene)))

(defn region-iri
  ([curation]
   (rdf/resource (str region-prefix (:key curation))))
  ([curation suffix]
   (rdf/resource (str region-prefix (:key curation) suffix))))

(defn- subject-iri [curation]
  (if-let [gene (gene-iri curation)]
    (s/replace gene
               "https://www.ncbi.nlm.nih.gov/gene/"
               "https://identifiers.org/ncbigene:")
    (region-iri curation)))


;; Identifier -- currently generated
;; Label :customfield_10202
;; DefiningFeature
;; IncludedFeature



(defn sequence-location [curation build]
  (when-let [loc-str (get-in curation [:fields (build-location build)])]
    (let [[_ chr start-coord end-coord] (re-find #"(\w+):(.+)[-_–](.+)$" loc-str)
          iri (region-iri curation (name build))
          interval-iri (rdf/blank-node)
          reference-sequence (get-in shared-data/chr-to-ref
                                     [build
                                      (subs chr 3)])
          start (-> start-coord (s/replace #"\D" "") Integer.)
          end (-> end-coord (s/replace #"\D" "") Integer.)]
      [iri [[iri :rdf/type :ga4gh/SequenceLocation]
            [iri :ga4gh/sequenceReference (rdf/resource reference-sequence)]
            [iri :ga4gh/start start]
            [iri :ga4gh/end start]]])))


(defn recurrent-region? [curation]
  (some #(= "Recurrent" %)
        (get-in curation [:fields :labels])))

(defn region-label [curation]
  (get-in curation [:fields :customfield_10202] ""))

(defn location [curation]
  (let [iri (region-iri curation)
        locations (->> (keys build-location)
                       (map #(sequence-location curation %))
                       (remove nil?)
                       (sort-by #(str (first %)))
                       reverse)] ; use latest build as defining location
    (if-not (gene-iri curation) ; only represent location for region curations
      (concat (map (fn [l] [iri :ga4gh/location (first l)]) locations)
              (mapcat second locations)
              [[iri :ga4gh/definingLocation (-> locations first first)]
               [iri :rdfs/label (region-label curation)]
               [iri :rdf/type :cg/DosageRegion]
               [iri :rdf/type :so/SequenceFeature]
               (if (recurrent-region? curation)
                 [iri :rdf/type :cg/RecurrentRegion]
                 [iri :rdf/type :cg/NonRecurrentRegion])]))))

(defn- contribution-iri
  [curation]
  (rdf/resource (str cg-prefix "contribution-" (:key curation)  "-" (updated-date curation))))

(defn- contribution
  [iri curation]
  [[iri :rdf/type :cg/Contribution]
   [iri :cg/agent :cg/GeneDosageCurationExpertPanel]
   [iri :cg/date (resolution-date curation)]
   [iri :cg/role :cg/Approver]])

(defn- assertion-iri [curation dosage]
  (rdf/resource (str cg-prefix (:key curation) "x" dosage "-" (updated-date curation))))

(defn- proposition-iri [curation dosage]
  (rdf/resource (str cg-prefix (:key curation) "x" dosage)))

(def evidence-field-map
  {1 [[:customfield_10183 :customfield_10184]
      [:customfield_10185 :customfield_10186]
      [:customfield_10187 :customfield_10188]
      [:customfield_12231 :customfield_12237]
      [:customfield_12232 :customfield_12238]
      [:customfield_12233 :customfield_12239]]
   3 [[:customfield_10189 :customfield_10190]
      [:customfield_10191 :customfield_10192]
      [:customfield_10193 :customfield_10194]
      [:customfield_12234 :customfield_12240]
      [:customfield_12235 :customfield_12241]
      [:customfield_12236 :customfield_12242]]})

(defn- finding-data [curation dosage]
  (->> (get evidence-field-map dosage)
       (map (fn [row]
         (map #(get-in curation [:fields %]) row)))
       (remove #(nil? (first %)))))

(defn- study-findings [assertion-iri curation dosage]
  (let [findings (finding-data curation dosage)]
    (mapcat (fn [[pmid description]]
              (let [finding-iri (rdf/blank-node)]
                [[assertion-iri :cg/evidence finding-iri]
                 [finding-iri :rdf/type :cg/EvidenceLine]
                 [finding-iri
                  :dc/source
                  (rdf/resource
                   (str "https://identifiers.org/pubmed:"
                        (re-find #"\d+" pmid)))]
                 [finding-iri :dc/description (or description "")]]))
            findings)))

(defn dosage-proposition-object [curation dosage]
  (let [legacy-mondo-field (if (= 1 dosage) :customfield_11631 :customfield_11633)
        legacy-mondo (some->> curation
                              :fields
                              legacy-mondo-field
                              (re-find #"MONDO:\d*")
                              rdf/resource)
        phenotype-field (if (= 1 dosage) :customfield_10200 :customfield_10201)
        phenotype (get-in curation [:fields phenotype-field])]
    ;; Bad IRIs prevent Jena restore. Remove spaces in IRIs
    (or (when phenotype (rdf/resource (s/replace phenotype " " "")))
        legacy-mondo
        :mondo/HereditaryDisease)))

(defn- gene-dosage-variant [iri curation dosage]
  [[iri :rdf/type :geno/FunctionalCopyNumberComplement]
   [iri :geno/has-member-count dosage]
   [iri :geno/has-location (subject-iri curation)]])

(defn- proposition-predicate [curation dosage]
  (let [dosage-assertion-str (if (= 1 dosage)
                               (get-in curation [:fields :customfield_10165 :value])
                               (get-in curation [:fields :customfield_10166 :value]))]
    (if (= "40: Dosage sensitivity unlikely" dosage-assertion-str)
      :geno/BenignForCondition
      :geno/PathogenicForCondition)))

(def dosage->mechanism
  {3 :cg/Triplosensitivity
   1 :cg/Haploinsufficiency})

(defn proposition [curation dosage]
  (let [iri (proposition-iri curation dosage)
        variant-iri (rdf/blank-node)]
    [[iri :rdf/type :cg/GeneticConditionMechanismProposition]
     [iri :cg/feature (rdf/resource (subject-iri curation))]
     [iri :cg/mechanism (dosage->mechanism dosage)]
     [iri :cg/condition (dosage-proposition-object curation dosage)]]))

(defn- dosage-assertion-value [curation dosage]
  (let [assertion-field (if (= 1 dosage) :customfield_10165 :customfield_10166)]
    (get evidence-levels
         (get-in curation [:fields assertion-field :value])
         :cg/NoAssertion)))

(defn- dosage-assertion-description [curation dosage]
  (let [description-field (if (= 1 dosage) :customfield_10198 :customfield_10199)]
    (or (get-in curation [:fields description-field :value]) "")))

;; TODO, this looks like legacy dreck--see if it can be removed
(defn- common-assertion-fields
  [iri curation dosage]
  []
  (concat [[iri :sepio/is-specified-by :sepio/DosageSensitivityEvaluationGuideline]
           [iri :sepio/qualified-contribution (contribution-iri curation)]
           [iri :sepio/has-subject (proposition-iri curation dosage)]
           [iri :dc/description (dosage-assertion-description curation dosage)]]
          (study-findings iri curation dosage)
          (proposition curation dosage)))

(defn- evidence-strength-assertion [curation dosage]
  (let [iri (assertion-iri curation dosage)]
    (concat (common-assertion-fields iri curation dosage)
            [[iri :rdf/type :sepio/EvidenceLevelAssertion]
             [iri :sepio/has-predicate :sepio/HasEvidenceLevel]
             [iri :sepio/has-object (dosage-assertion-value curation dosage)]])))

(defn- scope-assertion
  [curation dosage]
  (let [iri (assertion-iri curation dosage)]
    (concat (common-assertion-fields iri curation dosage)
            [[iri :sepio/has-predicate :sepio/DosageScopeAssertion]
             [iri :sepio/has-object :sepio/GeneAssociatedWithAutosomalRecessivePhenotype]
             [iri :rdf/type :sepio/PropositionScopeAssertion]])))

(defn- base-iri [curation]
  (str cg-prefix (:key curation)))

(defn- report-iri [curation]
  (rdf/resource (str (base-iri curation) "-" (updated-date curation))))

(defn assertion [curation dosage]
  (let [iri (assertion-iri curation dosage)]
    (concat
     [[iri :cg/specifiedBy :cg/DosageSensitivityEvaluationGuideline]
      [iri :cg/contributions (contribution-iri curation)]
      [iri :cg/subject (proposition-iri curation dosage)]
      [iri :dc/description (dosage-assertion-description curation dosage)]
      [iri :rdf/type :cg/EvidenceStrengthAssertion]
      [iri :cg/evidenceStrength (dosage-assertion-value curation dosage)]
      [(report-iri curation) :bfo/has-part iri]]
     (study-findings iri curation dosage)
     (proposition curation dosage))))

(defn gene-dosage-report
  [curation]
  (let [base-iri (str cg-prefix (:key curation))
        report-iri (report-iri curation)
        contribution-iri (contribution-iri curation)
        result (concat [[report-iri :rdf/type :cg/GeneDosageReport]
                        [report-iri :dc/isVersionOf (rdf/resource base-iri)]
                        [report-iri :cg/contributions contribution-iri]]
                       (contribution contribution-iri curation)
                       (assertion curation 1)
                       (assertion curation 3)
                       (location curation))]
    #_(tap> result)
    result))


(defn sequence-location-map [curation build]
  (when-let [loc-str (get-in curation [:fields (build-location build)])]
    (let [[_ chr start-coord end-coord] (re-find #"(\w+):(.+)[-_–](.+)$" loc-str)
          loc {:type :ga4gh/SequenceLocation
               :ga4gh/sequenceReference (get-in shared-data/chr-to-ref
                                     [build
                                      (subs chr 3)])
               :ga4gh/start (-> start-coord (s/replace #"\D" "") Long.)
               :ga4gh/end (-> end-coord (s/replace #"\D" "") Long.)}]
      (assoc loc :iri (id/iri loc)))))


(defn add-dosage-model-fn [event]
  (if (spec/invalid? (spec/conform ::fields (get-in event [::event/data :fields])))
    (assoc event ::spec/invalid true)
    (assoc event
           ::model
           (-> event
               ::event/data
               gene-dosage-report
               rdf/statements->model))))

(def add-dosage-model
  (interceptor/interceptor
   {:name ::add-dosage-report-model
    :enter (fn [e] (add-dosage-model-fn e))}))

(defn write-dosage-model-to-db-fn [event]
  (if-let [model (::model event)]
    (event/store event
                 :api-tdb
                 (base-iri (::event/data event))
                 model)
    event))

(def write-dosage-model-to-db
  (interceptor/interceptor
   {:name ::write-dosage-model-to-db
    :enter (fn [e] (write-dosage-model-to-db-fn e))}))


(defn dosage-region [curation]
  {:type :cg/DosageRegion
   :iri (str (region-iri curation))
   :rdfs/label (region-label curation)
   :ga4gh/location (mapv #(sequence-location-map curation %)
                         (keys build-location))})


;; TODO pickup here to make regions searchable
(defn region->index-doc [region]
  {:iri (:iri region)
   :source (:iri region)
   :symbols [(re-find #"ISCA-\d+" (:iri region))]
   :labels [(:rdfs/label region)]
   :types [(-> region :type rdf/resource str)]})

(defn add-dosage-region-fn [event]
  (if (::model event)
    (let [region (dosage-region (::event/data event))] 
      (-> event
          (assoc ::region region)
          (event/store :object-db [:objects (:iri region)] region)
          (event/store :text-index
                       (:iri region)
                       (region->index-doc region))))
    event))

(def add-dosage-region
  (interceptor/interceptor
   {:name ::add-dosage-region
    :enter (fn [e] (add-dosage-region-fn e))}))

;; this may belong somewhere else at some point soon
(defmethod idx/sequence-feature->sequence-index :cg/DosageRegion [feature]
  (mapv
   (fn [l]
     {:key [:sequences
            :cg/DosageRegion
            (:sequence-reference l)
            (:coordinate l)
            (:iri feature)]
      :value (select-keys feature [:iri])})
   (mapcat idx/location->index-entries (:ga4gh/location feature))))

(defn add-dosage-indexes-fn [event]
  (if (::model event)
    (reduce (fn [e idx] 
              (event/store e
                           :object-db
                           (:key idx)
                           (:value idx)))
            event
            (-> event
                ::region
                idx/sequence-feature->sequence-index))
    event))


;; TODO start here--test this
(def add-dosage-indexes
  (interceptor/interceptor
   {:name ::add-dosage-indexes
    :enter (fn [e] (add-dosage-indexes-fn e))}))

(comment
  (-> genegraph.user/bwrs1
      ::event/data
      dosage-region
      idx/sequence-feature->sequence-index))
