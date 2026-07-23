(ns genegraph.api.cvc-submission
  (:require [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage :as storage]
            [genegraph.api.hybrid-resource :as hr]
            [charred.api :as charred]
            [clojure.string :as s]
            [clojure.java.io :as io]
            [clojure.stacktrace :as stacktrace])
  (:import [java.time Instant]
           [java.io Writer]))

;; example of CVC submission
(comment
  {"Date Created" "2026-05-27",
   "Is Annotation Outdated" nil,
   "ClinVar Release Date" "2026-05-23",
   "VCV" "VCV000555168",
   "SCV Deleted Release Date" nil,
   "Timestamp" "2026-05-26T15:16:13Z",
   "Submitter ID" "320494",
   "Reason" "Older claim that does not account for recent evidence",
   "Is Annotated SCV Deleted" false,
   "Action" "flagging candidate",
   "Notes" nil,
   "SCV ID" "SCV000795855.2",
   "Variation ID" "555168"}
  )



(defn clinvar-annotations [tdb]
  (let [q (rdf/create-query "
select ?x where {
 ?x a :cg/AssertionAnnotation ;
 :cg/classification :cg/DosageMapConflict }")]
    (q tdb)))

(defn assertion->prop-id [a]
  (re-find #"https://genegraph.clinicalgenome.org/r/ISCA-\d+x\d" (str a)))

(comment
  (do
    (def tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance]))
    (def object-db @(get-in genegraph.user/api-test-app [:storage :object-db :instance]))
    (def hybrid-db {:tdb tdb :object-db object-db}))
  (rdf/tx tdb
    (let [q (rdf/create-query "select ?s where {?s ?p ?o} limit 100")
            f9 (rdf/resource "NCBIGENE:2158" tdb)]
      (q tdb {:o f9})
      #_(rdf/ld-> f9 [:rdf/type])))

  (def old-prop-ids
    (let [tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])
          q (rdf/create-query "
select ?x ?g where {
 ?x a :cg/GeneticConditionMechanismProposition ;
 :cg/feature ?g .
 ?g a :so/GeneWithProteinProduct . }")]
      (rdf/tx tdb
        (->> (q tdb {::rdf/params {:type :table}})
             (mapv (fn [{:keys [x g]}] [(str x) g]))
             (into {})))))

  (spit "/Users/tristan/data/genegraph-neo/old-dosage-prop-features.edn"
        (pr-str old-prop-ids))

  (tap> (take 5 old-prop-ids))

  (get old-prop-ids "https://genegraph.clinicalgenome.org/r/ISCA-25959x1")
  (get "https://genegraph.clinicalgenome.org/r/ISCA-36501x1" old-prop-ids)
  (get "https://genegraph.clinicalgenome.org/r/ISCA-36501x1" old-prop-ids)
  (do
    (defn genes [ann]
      (rdf/tx tdb
        (remove
         nil? 
         (mapv (fn [e]
                 (when-let [p (get old-prop-ids (assertion->prop-id e))]
                   (rdf/ld1-> p [:skos/prefLabel])))
               (rdf/ld-> ann [:cg/evidence])))))

    (defn notes [ann]
      (let [genes (genes ann)
            multiple-genes (< 1 (count genes))]
        (str "This submission was flagged because its interpretation of this variant conflicts "
             "with the ClinGen Gene Dosage Map "
             (if multiple-genes "curations " "curation ")
             "for "
             (if multiple-genes "the following genes: " "the gene ")
             (s/join " " (take 10 genes))
             ".")))

    (defn annotation->submission-record [ann]
      (try
        (let [now (str (Instant/now))
              scv (hr/hybrid-resource (rdf/ld1-> ann [:cg/subject]) hybrid-db)
              variant (hr/hybrid-resource (rdf/ld1-> scv [:cg/subject :cg/variant])
                                          hybrid-db)
              variation-id (re-find #"\d+$" (:iri variant))
              submitter-id (re-find #"\d+$" (:cg/submitter scv))
              clinvar-variant (storage/read object-db [:clinvar-if variation-id])]
          #_(tap> clinvar-variant)
          {"Date Created"  (subs now 0 10)
           "Is Annotation Outdated" nil,
           "ClinVar Release Date" "2026-06-04",
           "VCV" (str (:vcv-id clinvar-variant) "." (:vcv-version clinvar-variant))
           "SCV Deleted Release Date" nil,
           "Timestamp" now
           "Submitter ID" submitter-id
           "Reason" "Conflict with ClinGen Gene Dosage Map",
           "Is Annotated SCV Deleted" false,
           "Action" "flagging candidate",
           "Notes" (notes ann),
           "SCV ID" (str (re-find #"SCV\d+$" (:iri scv)) "." (some-> clinvar-variant
                                                                     :classifications
                                                                     first
                                                                     :Version))
           "Variation ID" variation-id})
        (catch Exception e
          (tap> (hr/hybrid-resource (rdf/ld1-> ann [:cg/subject]) hybrid-db))
          (stacktrace/print-stack-trace e)
          (tap> ann)
          (tap> (rdf/ld-> ann [:cg/evidence]))
          {:record (str ann)
           :state :error
           :error true})))
    
    (let [tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])]
      (rdf/tx tdb
        (->> (clinvar-annotations tdb)
             (take 1)
             (mapv annotation->submission-record)
             #_(mapv #(mapv assertion->prop-id (rdf/ld-> % [:cg/evidence]))))))

    
    (with-open [submission (io/writer "/Users/tristan/Desktop/candidate-cvc-cnv-submission.json")]
      (rdf/tx tdb
        (->> (clinvar-annotations tdb)
             (mapv annotation->submission-record)
             (remove :error)
             (charred/write-json submission))))

    (with-open [submission (io/writer "/Users/tristan/Desktop/candidate-cvc-cnv-submission.ndjson")]
      (rdf/tx tdb
        (->> (clinvar-annotations tdb)
             (mapv annotation->submission-record)
             (remove :error)
             (run! (fn [a]
                     (.write submission (charred/write-json-str a))
                     (.write submission "\n"))))))
    
    )



  (let [tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])]
    (rdf/tx tdb
            (->> (clinvar-annotations tdb)
                 #_(take 5)
                 (into [])
                 tap>
                 #_(mapv #(rdf/ld1-> % [:cg/evidence])))))

  (rdf/tx tdb
    (->> (clinvar-annotations tdb)
         (take 1)
         (mapv annotation->submission-record)
         tap>))
  
  (let [tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])
        q (rdf/create-query "select ?x where 
{ ?x a :cg/EvidenceStrengthAssertion ;
  :cg/subject ?p .
  ?p a :cg/GeneticConditionMechanismProposition . 
  filter not exists { ?p :cg/feature ?f } }")]
    
    (rdf/tx tdb
      (->> (q tdb)
           count
           #_(mapv #(rdf/ld1-> % [:cg/evidence :rdf/type #_:cg/subject #_#_:cg/feature :rdfs/label]))))))
