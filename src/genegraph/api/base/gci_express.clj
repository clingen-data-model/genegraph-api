(ns genegraph.api.base.gci-express
  (:require [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage :as storage]
            [clojure.data.json :as json]
            [clojure.java.io :as io]))

(def gci-express-root "http://dataexchange.clinicalgenome.org/gci-express/")
(def affiliation-root "http://dataexchange.clinicalgenome.org/agent/")

(defn json-content [report]
  (if (< 0 (count (:scoreJsonSerialized report)))
    (:scoreJsonSerialized report)
    (:scoreJsonSerializedSop5 report)))

(defn json-content-node [report iri]
  [[iri :rdf/type :cnt/ContentAsText]
   [iri :cnt/chars (json-content report)]])

(defn validity-proposition [report iri]
  (let [gene (rdf/resource (str "https://www.ncbi.nlm.nih.gov/gene/"
                                (:entrez_id report)))
        parsed-json (json/read-str (json-content report) :key-fn keyword)
        moi-string (or (-> parsed-json :data :ModeOfInheritance)
                       (-> parsed-json :scoreJson :ModeOfInheritance))
        moi (->> moi-string
                 (re-find #"\(HP:(\d+)\)")
                 second
                 (str "http://purl.obolibrary.org/obo/HP_")
                 rdf/resource)]
    [[iri :rdf/type :sepio/GeneValidityProposition]
     [iri :sepio/has-subject gene]
     [iri :sepio/has-predicate :ro/IsCausalGermlineMutationIn]
     [iri :sepio/has-object (rdf/resource (-> report :conditions :MONDO :iri))]
     [iri :sepio/has-qualifier moi]]))

(defn contribution [report iri]
  [[iri :bfo/realizes :sepio/ApproverRole]
   [iri :sepio/has-agent (rdf/resource (str affiliation-root
                                        (-> report :affiliation :id)))]
   [iri :sepio/activity-date (:dateISO8601 report)]])

(def evidence-level-label-to-concept
  {"Definitive" :sepio/DefinitiveEvidence
   "Limited" :sepio/LimitedEvidence
   "Moderate" :sepio/ModerateEvidence
   "No Reported Evidence" :sepio/NoEvidence
   "Strong*" :sepio/StrongEvidence
   "Contradictory (disputed)" :sepio/DisputingEvidence
   "Strong" :sepio/StrongEvidence
   "Contradictory (refuted)" :sepio/Refuted
   "Refuted" :sepio/Refuted
   "Disputed" :sepio/DisputingEvidence})

(defn sop-version-gci-e [report]
  (if (< 0 (count (:scoreJsonSerialized report)))
    :sepio/ClinGenGeneValidityEvaluationCriteriaSOP4
    :sepio/ClinGenGeneValidityEvaluationCriteriaSOP5))

(defn evidence-level-assertion [report iri id]
  (let [prop-iri (rdf/resource (str gci-express-root "proposition_" id))
        contribution-iri (rdf/blank-node)]
    (concat [[iri :rdf/type :sepio/GeneValidityEvidenceLevelAssertion]
             [iri :sepio/has-subject prop-iri]
             [iri :sepio/has-predicate :sepio/HasEvidenceLevel]
             [iri :sepio/has-object (evidence-level-label-to-concept
                                     (-> report :scores vals first :label))]
             [iri :sepio/qualified-contribution contribution-iri]
             [iri :sepio/is-specified-by (sop-version-gci-e report)]
             [iri :dc/has-format (sop-version-gci-e report)]]
            (validity-proposition report prop-iri)
            (contribution report contribution-iri))))

(defn gci-express-report-to-triples [report]
  (let [content (second report)
        id (-> report first name)
        iri (str gci-express-root "report_" id)
        content-id (rdf/blank-node)
        assertion-id (rdf/resource (str gci-express-root "assertion_" id))]
    (concat [[iri :rdf/type :sepio/GeneValidityReport] 
             [iri :rdfs/label (:title content)]
             [iri :bfo/has-part content-id]
             [iri :bfo/has-part assertion-id]
             [iri :dc/source :cg/GeneCurationExpress]]
            (evidence-level-assertion content assertion-id id)
            (json-content-node content content-id))))

(def same-as-query
  (rdf/create-query "select ?x where { ?x :owl/sameAs ?y }"))

(defn replace-hgnc-id-with-entrez [db triples]
  (map (fn [[s p o]]
         (if (re-find #"^((HGNC|hgnc):)?\d{1,5}$" o)
           [s p (first (same-as-query db {:y (rdf/resource o)}))]
           [s p o]))))

(defmethod rdf/as-model :genegraph.api.base/gci-express
  [{:keys [source]}]
  (with-open [r (io/reader (storage/->input-stream source))]
    (->> (json/read r :key-fn keyword)
         (mapcat gci-express-report-to-triples)
         rdf/statements->model)))



(comment
  (with-open [r (io/reader "/users/tristan/data/genegraph-neo/gci-express-with-entrez-ids.json")]
    (->> (json/read r :key-fn keyword)
         (take 1)
         (mapcat gci-express-report-to-triples)
         rdf/statements->model))

  (rdf/as-model {:format :genegraph.api.base/gci-express
                 :source {:type :file
                          :base "/users/tristan/data/genegraph-neo/"
                          :path "gci-express-with-entrez-ids.json"}})
  )

;; (defmethod transform-doc :gci-express [doc-def]
;;   (let [raw-report (or (:document doc-def) (slurp (src-path doc-def)))
;;         report-json (json/parse-string raw-report true)]
;;     (rdf/statements-to-model (mapcat gci-express-report-to-triples report-json))))


;; (defmethod add-model :gci-express [event]
;;   (assoc event
;;          :genegraph.database.query/model
;;          (rdf/statements-to-model (gci-express-report-to-triples
;;                                  (:genegraph.sink.event/value event)))))
