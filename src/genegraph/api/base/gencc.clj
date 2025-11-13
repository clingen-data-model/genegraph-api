(ns genegraph.api.base.gencc
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [clojure.set :as set]
            [clojure.edn :as edn]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.id :as id]
            [io.pedestal.log :as log])
  (:import [java.io PushbackReader]))

"uuid" ;; ugly as sin, use Genegraph hash
"gene_curie" ;; HGNC id for gene
"gene_symbol" ;; ignore
"disease_curie" ;; OMIM or MONDO id for condition, generally MONDO
"disease_title" ;; ignore 
"disease_original_curie" ;; OMIM or MONDO id for condition, generally OMIM
"disease_original_title" ;; ignore 
"classification_curie" ;; unclear what this means -- seems to be reused for multiple classifications
"classification_title" ;; ignore 
"moi_curie" ;; MOI using HPO term
"moi_title" ;; ignore
"submitter_curie" ;; submitter ID
"submitter_title" ;; Compose ID-Label pairs into separate document
"submitted_as_hgnc_id" ;; probably ignore, check to see if ever different from gene_curie
"submitted_as_hgnc_symbol" ;; ignore
"submitted_as_disease_id" ;; same issue with disease original curie
"submitted_as_disease_name" ;; ignore
"submitted_as_moi_id" ;; Now this is getting weird
"submitted_as_moi_name" ;; 
"submitted_as_submitter_id"  ;; again, WTF
"submitted_as_submitter_name"
"submitted_as_classification_id" ;; ????
"submitted_as_classification_name" ;; !!!!
"submitted_as_date" ;; date of classification ? 
"submitted_as_public_report_url" ;; may be an interesting reference to preserve
"submitted_as_notes" ;; notes may be useful to keep 
"submitted_as_pmids" ;; references are also very useful
"submitted_as_assertion_criteria_url" ;; also useful link to assertion criteria, may be worth reifying 
"submitted_as_submission_id" ;; original ID in source database? if exists, may be more useful than any other id for identifying the record.
"submitted_run_date" ;; date published ?


(def literal-attrs
  #{:cg/dateLastEvaluated
    :cg/date})

(defn value->rdf-object [v]
  (if (map? v)
    (rdf/resource (:iri v))
    (rdf/resource v)))

(defn map->statements
  ([m] (map->statements m []))
  ([m statements]
   (let [iri (:iri m)]
     (reduce
      (fn [a [k v]]
        (cond
          (literal-attrs k) (conj a [iri k v])
          (map? v) (map->statements
                    v
                    (conj a [iri k (value->rdf-object v)]))
          (vector? v) (reduce
                       (fn [a1 v1]
                         (let [a2 (conj a1 [iri k (value->rdf-object v1)])]
                           (if (map? v1)
                             (map->statements v1 a2)
                             a2)))
                       a
                       v)
          :default (conj a [iri k (value->rdf-object v)])))
      statements
      (remove
       (fn [[_ v]] (nil? v))
       (set/rename-keys (dissoc m :iri)
                        {:type :rdf/type}))))))
(sort-by first
         (map->statements
          {:type :cg/EvidenceStrengthAssertion,
           :cg/subject
           {:cg/gene "https://identifiers.org/ncbigene:6497",
            :cg/disease "https://omim.org/entry/182212",
            :cg/modeOfInheritance "http://purl.obolibrary.org/obo/HP_0000006",
            :type :cg/GeneValidityProposition,
            :iri "https://genegraph.clingen.app/Bt-5faQkJsE"},
           :cg/evidenceStrength :cg/Definitive,
           :cg/agent "http://genegraph.clingen.app/gencc/000101",
           :dc/source :cg/GenCC,
           :cg/date "2018-03-30",
           :iri "https://genegraph.clingen.app/d0vW1xSkkLY",
           :cg/contributions
           [{:type :cg/Contribution,
             :cg/agent "http://genegraph.clingen.app/gencc/000101",
             :cg/role :cg/Evaluator,
             :cg/date "2018-03-30",
             :cg/contributionTo "https://genegraph.clingen.app/d0vW1xSkkLY",
             :iri "https://genegraph.clingen.app/Wwc2yY07hPk"}
            {:type :cg/Contribution,
             :cg/agent "http://genegraph.clingen.app/gencc/000101",
             :cg/role :cg/Submitter,
             :cg/date "2020-12-24",
             :cg/contributionTo "https://genegraph.clingen.app/d0vW1xSkkLY",
             :iri "https://genegraph.clingen.app/wNV35ZPSUBo"}]}))

(defn row->record
  [[gencc-uuid
    gene
    symbol
    disease
    _
    disease-original-curie
    _
    classification-curie
    classification-title
    moi
    _
    submitter-curie
    submitter-title
    submitted-as-hgnc-id
    _
    submitted-as-disease-id
    _
    submitted-as-moi-id
    _
    submitted-as-submitter-id
    _
    submitted-as-classification-id
    _
    submitted-as-date
    submitted-as-public-report-url
    submitted-as-notes
    submitted-as-pmids
    submitted-as-assertion-criteria-url
    submitted-as-submission-id
    submitted-run-date]]
  {:gencc-uuid gencc-uuid
   :gene gene
   :symbol symbol
   :disease disease
   :disease-original-curie disease-original-curie
   :classification-curie classification-curie
   :classification-title classification-title
   :moi moi
   :submitter-curie submitter-curie
   :submitter-title submitter-title
   :submitted-as-hgnc-id submitted-as-hgnc-id
   :submitted-as-disease-id submitted-as-disease-id
   :submitted-as-moi-id submitted-as-moi-id
   :submitted-as-submitter-id submitted-as-submitter-id
   :submitted-as-classification-id submitted-as-classification-id
   :submitted-as-date submitted-as-date
   :submitted-as-public-report-url submitted-as-public-report-url
   :submitted-as-notes submitted-as-notes
   :submitted-as-pmids submitted-as-pmids
   :submitted-as-assertion-criteria-url submitted-as-assertion-criteria-url
   :submitted-as-submission-id submitted-as-submission-id 
   :submitted-run-date submitted-run-date}
  )

(id/register-type
 {:type :cg/GeneValidityProposition
  :defining-attributes [:cg/gene :cg/disease :cg/modeOfInheritance]})

(id/register-type
 {:type :cg/Contribution
  :defining-attributes [:cg/agent :cg/date :cg/role :cg/contributionTo]})

(id/register-type
 {:type :cg/EvidenceStrengthAssertion
  :defining-attributes [:cg/agent :cg/date :cg/subject :cg/evidenceStrength]})

(id/register-type
 {:type ::GenCCRecord
  :defining-attributes []})


#_(def classification-directions)
(def gencc-labels
  {"GENCC:100004" "Limited",
   "GENCC:100006" "Refuted Evidence",
   "GENCC:100001" "Definitive",
   "GENCC:100002" "Strong",
   "GENCC:100008" "No Known Disease Relationship",
   "GENCC:100005" "Disputed Evidence",
   "GENCC:100003" "Moderate", 
   "GENCC:100009" "Supportive"})

(def gencc-classifications->clingen-classifications
  {"GENCC:100004" :cg/Limited
   "GENCC:100006" :cg/Refuted
   "GENCC:100001" :cg/Definitive
   "GENCC:100002" :cg/Strong
   "GENCC:100008" :cg/NoKnownDiseaseRelationship
   "GENCC:100005" :cg/Disputed
   "GENCC:100003" :cg/Moderate
   "GENCC:100009" :cg/Supportive}) 

(def gencc-submitters
  {"GENCC:000111" "PanelApp Australia",
   "GENCC:000115" "Broad Center for Mendelian Genomics",
   "GENCC:000104" "Genomics England PanelApp",
   "GENCC:000116" "Baylor College of Medicine Research Center",
   "GENCC:000112" "G2P",
   "GENCC:000113" "Franklin by Genoox",
   "GENCC:000105" "Illumina",
   "GENCC:000107" "Laboratory for Molecular Medicine",
   "GENCC:000102" "ClinGen",
   "GENCC:000106" "Invitae",
   "GENCC:000110" "Orphanet",
   "GENCC:000108" "Myriad Women’s Health",
   "GENCC:000101" "Ambry Genetics",
   "GENCC:000114" "King Faisal Specialist Hospital and Research Center"})

(defn field->iri
  "Make a best-effort to convert the value for a field (expressed as a curie)
   into a full URL (expressed as a string)"
  [field]
  (-> field s/trim s/upper-case rdf/resource str))

(defn record->proposition [record hgnc->entrez-map]
  (let [prop {:cg/gene (-> record :gene s/trim hgnc->entrez-map)
              :cg/disease (field->iri (:disease-original-curie record))
              :cg/modeOfInheritance (field->iri (:moi record))
              :type :cg/GeneValidityProposition}
        id (id/iri prop)]
    (assoc prop :iri id)))

(defn submitter [record]
  (field->iri (:submitter-curie record)))

(def roles->record-key
  {:cg/Evaluator :submitted-as-date
   :cg/Submitter :submitted-run-date})

(defn record->contribution [record assertion-iri role]
  (let [contrib {:type :cg/Contribution
                 :cg/agent (submitter record)
                 :cg/role role
                 :cg/date (subs (get record (roles->record-key role)) 0 10)
                 :cg/contributionTo assertion-iri}]
    (assoc contrib :iri (id/iri contrib))))

(defn record->contributions [record assertion-iri]
  (mapv #(record->contribution record assertion-iri %) (keys roles->record-key)))

(defn record->assertion [record hgnc->entrez-map]
  (let [assertion
        {:type :cg/EvidenceStrengthAssertion
         :cg/subject (record->proposition record hgnc->entrez-map)
         :cg/evidenceStrength (-> record
                                :classification-curie
                                gencc-classifications->clingen-classifications)
         :cg/agent (submitter record)
         :dc/source :cg/GenCC
         :cg/date (subs (get record :submitted-as-date) 0 10)}
        iri (id/iri assertion)]
    (assoc assertion
           :iri iri
           :cg/contributions (record->contributions record iri))))


(defn read-hgnc->entrez-map []
  (with-open [r (-> "hgnc-entrez.edn"
                    io/resource
                    io/reader
                    PushbackReader.)]
    (edn/read r)))

(defmethod rdf/as-model :genegraph.api.base/gencc
  [{:keys [source]}]
  (log/info :fn ::rdf/as-model
            :format :genegraph.api.base/gencc)
  (let [hgnc->entrez-map (read-hgnc->entrez-map)]
    (with-open [r (io/reader (storage/->input-stream source))]
      (->> (csv/read-csv r)
           rest
           (map #(-> %
                     row->record
                     (record->assertion hgnc->entrez-map)))
           (remove #(= "https://genegraph.clinicalgenome.org/r/gencc/000102"
                       (:cg/agent %))) ; remove ClinGen ones
           (mapcat map->statements)
           (into [])
           rdf/statements->model))))

(comment
  (def records
    (with-open [r (io/reader (storage/->input-stream {:type :file
                                                      :base "data/base/"
                                                      :path "gencc.csv"}))]
      (->> (csv/read-csv r)
           rest
           (mapv row->record))))

  ;; gene id = sumbitted gene id, minus one record with a trailing space
  ;; moi = submitted moi, minus one term where AR -> XLR
  ;; disease-ids: most non-clingen submitters use OMIM, but is mapped to MONDO in
  ;; GenCC. Only one error exists between submitted-as and original

  (count records)
  (-> records first (record->assertion (read-hgnc->entrez-map)) tap>)

  (let [hgnc->entrez (read-hgnc->entrez-map)]  
    (->> records
         #_(remove #(= (s/trim (:submitter-curie %)) (s/trim (:submitted-as-submitter-id %))))
         #_(map #(select-keys % [:submitter-curie :submitter-title]))
         #_set
         #_(reduce (fn [a v] (assoc a (:submitter-curie v) (:submitter-title v)))
                   {})
         (take 3)
         (map #(record->assertion % hgnc->entrez))
         (into [])
         tap>))

  
  (.size
   (rdf/as-model
    {:format :genegraph.api.base/gencc
     :source
     {:type :file
      :base "data/base/"
      :path "gencc.csv"}}))
  "http://genegraph.clingen.app/gencc/000102"
  (sort-by first
         (map->statements
          {:type :cg/EvidenceStrengthAssertion,
           :cg/subject
           {:cg/gene "https://identifiers.org/ncbigene:6497",
            :cg/disease "https://omim.org/entry/182212",
            :cg/modeOfInheritance "http://purl.obolibrary.org/obo/HP_0000006",
            :type :cg/GeneValidityProposition,
            :iri "https://genegraph.clingen.app/Bt-5faQkJsE"},
           :cg/evidenceStrength :cg/Definitive,
           :cg/agent "http://genegraph.clingen.app/gencc/000101",
           :dc/source :cg/GenCC,
           :cg/date "2018-03-30",
           :iri "https://genegraph.clingen.app/d0vW1xSkkLY",
           :cg/contributions
           [{:type :cg/Contribution,
             :cg/agent "http://genegraph.clingen.app/gencc/000101",
             :cg/role :cg/Evaluator,
             :cg/date "2018-03-30",
             :cg/contributionTo "https://genegraph.clingen.app/d0vW1xSkkLY",
             :iri "https://genegraph.clingen.app/Wwc2yY07hPk"}
            {:type :cg/Contribution,
             :cg/agent "http://genegraph.clingen.app/gencc/000101",
             :cg/role :cg/Submitter,
             :cg/date "2020-12-24",
             :cg/contributionTo "https://genegraph.clingen.app/d0vW1xSkkLY",
             :iri "https://genegraph.clingen.app/wNV35ZPSUBo"}]}))

  
  )
