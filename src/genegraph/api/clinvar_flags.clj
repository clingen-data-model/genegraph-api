(ns genegraph.api.clinvar-flags
  "Read the SCV identifiers in the ClinGen flagged-submission reports produced by
  ClinVar (one file per submitting organization) and look up the current
  classification (clinical significance) of each submission using the NCBI
  eutils API.

  The general flow is:

  1. esearch db=clinvar with a batch of SCV accessions -> ClinVar variation ids
  2. efetch db=clinvar rettype=vcv for those variation ids -> VCV XML
  3. pull every ClinicalAssertion out of the returned records, keyed by SCV

  Since a VCV record carries all of its submissions, one efetch generally
  resolves several of the SCVs we care about at once."
  (:require [clojure.data.csv :as csv]
            [clojure.data.xml :as xml]
            [clojure.data.zip.xml :as xml-zip]
            [clojure.zip :as zip]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hato.client :as hc]
            [io.pedestal.log :as log]))

;; # Reading the flag files

(def flag-file-columns
  {"SCV" :scv
   "Reason" :reason
   "Notes" :notes
   "Curation date" :curation-date})

(defn- column->key [column]
  (or (flag-file-columns column)
      (-> column str/trim str/lower-case (str/replace #"\s+" "-") keyword)))

(defn- filename->org-id [filename]
  (second (re-find #"OrgID_(\d+)" filename)))

(defn split-scv
  "Split an SCV accession with an optional version into
  {:accession \"SCV000586031\" :version 1}. The eutils search is done on the
  unversioned accession; the version is retained so the flagged version can be
  compared to the current one."
  [scv]
  (let [[_ accession version] (re-find #"(SCV\d+)(?:\.(\d+))?" (str scv))]
    (when accession
      {:accession accession
       :version (some-> version parse-long)})))

(defn read-flag-file
  "Read one tab-delimited ClinGen flagged-SCV report into a seq of maps."
  [file]
  (with-open [r (io/reader file)]
    (let [[header & rows] (csv/read-csv r :separator \tab)
          ks (mapv column->key header)
          source (.getName (io/file file))]
      (mapv (fn [row]
              (let [m (zipmap ks row)]
                (merge m
                       (split-scv (:scv m))
                       {:source-file source
                        :flagged-org-id (filename->org-id source)})))
            (remove #(every? str/blank? %) rows)))))

(defn read-flag-files
  "Read every flag report in a directory (defaults to all .txt files)."
  [dir]
  (->> (file-seq (io/file dir))
       (filter #(and (.isFile %) (str/ends-with? (.getName %) ".txt")))
       sort
       (mapcat read-flag-file)
       vec))

;; # eutils client

(def eutils-base-url "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/")

(defonce http-client
  (delay (hc/build-http-client {:connect-timeout 10000
                                :redirect-policy :always})))

(defn api-key
  "NCBI api key, if one is available. With a key eutils allows 10 requests per
  second, without it 3."
  []
  (System/getenv "NCBI_API_KEY"))

(defn- request-delay-ms []
  (if (api-key) 110 350))

(defn eutils-get
  "GET an eutils endpoint, retrying a few times on failure. Returns the response
  body as a string."
  [endpoint params]
  (let [url (str eutils-base-url endpoint)
        query (cond-> (assoc params :tool "genegraph" :email "tristann@gmail.com")
                (api-key) (assoc :api_key (api-key)))]
    (loop [attempt 1]
      (let [result (try
                     (:body (hc/get url
                                    {:http-client @http-client
                                     :query-params query
                                     :throw-exceptions true}))
                     (catch Exception e
                       (log/warn :fn ::eutils-get
                                 :endpoint endpoint
                                 :attempt attempt
                                 :exception (.getMessage e))
                       (when (< attempt 4)
                         (Thread/sleep (* attempt 1000))
                         ::retry)))]
        (if (= ::retry result)
          (recur (inc attempt))
          result)))))

(defn esearch-ids
  "Run an esearch against db=clinvar, returning the matching uids (which for
  ClinVar are variation ids)."
  [term]
  (-> (eutils-get "esearch.fcgi"
                  {:db "clinvar" :retmax "1000" :term term})
      xml/parse-str
      zip/xml-zip
      (xml-zip/xml-> :IdList :Id xml-zip/text)
      vec))

(defn scvs->variation-ids
  "Look up the ClinVar variation ids for a batch of (unversioned) SCV
  accessions. Note that the ids come back unordered and without any indication
  of which SCV they matched; the mapping back to SCV happens when the fetched
  records are parsed."
  [accessions]
  (when (seq accessions)
    (esearch-ids (str/join " OR " accessions))))

(defn fetch-variation-records
  "efetch the VCV records for a batch of variation ids, returning parsed XML."
  [variation-ids]
  (when (seq variation-ids)
    (-> (eutils-get "efetch.fcgi"
                    {:db "clinvar"
                     :rettype "vcv"
                     :is_variationid ""
                     :from_esearch "true"
                     :id (str/join "," variation-ids)})
        xml/parse-str)))

;; # Parsing submissions out of the VCV records

(def classification-elements
  "ClinVar reports the classification under an element named for the kind of
  claim being made."
  [:GermlineClassification
   :SomaticClinicalImpact
   :OncogenicityClassification])

(defn- classification [assertion-node]
  (some (fn [element]
          (when-let [v (xml-zip/xml1-> assertion-node
                                       :Classification
                                       element
                                       xml-zip/text)]
            {:classification v
             :classification-type (name element)}))
        classification-elements))

(defn clinical-assertions
  "Every ClinicalAssertion node in a VariationArchive. Classified and included
  records nest the assertion list differently."
  [variation-node]
  (concat
   (xml-zip/xml-> variation-node
                  :ClassifiedRecord :ClinicalAssertionList :ClinicalAssertion)
   (xml-zip/xml-> variation-node
                  :IncludedRecord :ClinicalAssertionList :ClinicalAssertion)))

(defn variation-node->submissions
  "Turn one VariationArchive node into a seq of submission maps, one per SCV."
  [variation-node]
  (let [variation {:variation-id (xml-zip/attr variation-node :VariationID)
                   :vcv-accession (xml-zip/attr variation-node :Accession)
                   :variation-name (xml-zip/attr variation-node :VariationName)
                   :variation-type (xml-zip/attr variation-node :VariationType)
                   :vcv-record-status (xml-zip/xml1-> variation-node
                                                      :RecordStatus
                                                      xml-zip/text)}]
    (mapv (fn [assertion]
            (let [accession-node (xml-zip/xml1-> assertion :ClinVarAccession)]
              (merge variation
                     (classification assertion)
                     {:accession (xml-zip/attr accession-node :Accession)
                      :current-version (some-> (xml-zip/attr accession-node :Version)
                                               parse-long)
                      :submitter-name (xml-zip/attr accession-node :SubmitterName)
                      :org-id (xml-zip/attr accession-node :OrgID)
                      :date-updated (xml-zip/attr accession-node :DateUpdated)
                      :review-status (xml-zip/xml1-> assertion
                                                     :Classification
                                                     :ReviewStatus
                                                     xml-zip/text)
                      :date-last-evaluated (xml-zip/xml1-> assertion
                                                           :Classification
                                                           (xml-zip/attr :DateLastEvaluated))
                      :record-status (xml-zip/xml1-> assertion
                                                     :RecordStatus
                                                     xml-zip/text)})))
          (clinical-assertions variation-node))))

(defn result-set->submissions
  "Parse an efetch ClinVarResult-Set into submission maps."
  [xml-node]
  (->> (xml-zip/xml-> (zip/xml-zip xml-node) :VariationArchive)
       (mapcat variation-node->submissions)))

;; # Putting it together

(def default-batch-size 50)

(defn fetch-submissions
  "Look up every SCV accession in accessions, returning a map of unversioned SCV
  accession -> current submission record. Accessions with no ClinVar record
  simply won't appear in the map."
  ([accessions] (fetch-submissions accessions {}))
  ([accessions {:keys [batch-size] :or {batch-size default-batch-size}}]
   (let [wanted (set accessions)]
     (->> (partition-all batch-size (distinct accessions))
          (mapcat (fn [batch]
                    (log/info :fn ::fetch-submissions :batch (count batch))
                    (let [ids (scvs->variation-ids batch)]
                      (Thread/sleep (request-delay-ms))
                      (let [submissions (some-> (fetch-variation-records ids)
                                                result-set->submissions)]
                        (Thread/sleep (request-delay-ms))
                        submissions))))
          (filter #(wanted (:accession %)))
          (map (juxt :accession identity))
          (into {})))))

(defn flags-with-current-classification
  "Read the flag reports in dir and annotate each row with the current state of
  the submission in ClinVar. Rows whose SCV no longer resolves are marked
  :status :not-found; rows where the current version differs from the flagged
  one are marked :updated, otherwise :current."
  ([dir] (flags-with-current-classification dir {}))
  ([dir opts]
   (let [flags (read-flag-files dir)
         submissions (fetch-submissions (keep :accession flags) opts)]
     (mapv (fn [{:keys [accession version] :as flag}]
             (if-let [submission (submissions accession)]
               (merge flag
                      (dissoc submission :accession)
                      {:flagged-version version
                       :status (if (and version
                                        (:current-version submission)
                                        (not= version (:current-version submission)))
                                 :updated
                                 :current)})
               (assoc flag :flagged-version version :status :not-found)))
           flags))))

(def report-columns
  [:scv :status :classification :review-status :date-last-evaluated
   :flagged-version :current-version :submitter-name :org-id
   :variation-id :vcv-accession :variation-name :reason :source-file])

(defn write-report
  "Write the annotated flag rows out as a tab-delimited file."
  [rows file]
  (with-open [w (io/writer file)]
    (csv/write-csv w
                   (cons (mapv name report-columns)
                         (mapv (fn [row]
                                 (mapv #(str (get row %)) report-columns))
                               rows))
                   :separator \tab)))

(defn classification-summary
  "Counts of the current classification for a set of annotated rows."
  [rows]
  (->> rows
       (map #(or (:classification %) (name (:status %))))
       frequencies
       (sort-by val >)))

(comment

  ;; Reading the flag files

  (def flag-dir "/Users/tristan/data/clinvar-flags")

  (def flags (read-flag-files flag-dir))

  (count flags) ;; => 58

  (first flags)
  ;; {:scv "SCV000586031.1"
  ;;  :reason "Conflict with ClinGen Gene Dosage Map"
  ;;  :notes "This submission was flagged because ..."
  ;;  :curation-date "2026-06-17"
  ;;  :accession "SCV000586031"
  ;;  :version 1
  ;;  :source-file "ClinGen_curated_SCVs_for_OrgID_505237 (1).txt"
  ;;  :flagged-org-id "505237"}

  (frequencies (map :flagged-org-id flags))

  ;; Talking to eutils directly

  (scvs->variation-ids ["SCV000586031" "SCV000584451"])
  ;; => ["443355" "441776"]

  (-> (fetch-variation-records ["443355"])
      result-set->submissions
      first)
  ;; {:variation-id "443355"
  ;;  :vcv-accession "VCV000443355"
  ;;  :variation-name "GRCh37/hg19 16p11.2(chr16:30607048-31117069)x1"
  ;;  :variation-type "copy number loss"
  ;;  :vcv-record-status "current"
  ;;  :classification "Uncertain significance"
  ;;  :classification-type "GermlineClassification"
  ;;  :accession "SCV000586031"
  ;;  :current-version 1
  ;;  :submitter-name "ISCA site 1"
  ;;  :org-id "505237"
  ;;  :review-status "no assertion criteria provided"
  ;;  :date-last-evaluated "2014-10-22"
  ;;  :record-status "current"}

  ;; Looking up a handful of SCVs by accession

  (fetch-submissions ["SCV000586031" "SCV000584451"])

  ;; The whole job: read every flag file and annotate with current state.
  ;; Takes a couple of requests per 50 SCVs, throttled to stay inside the
  ;; eutils rate limit. Set NCBI_API_KEY in the environment to go faster.

  (def annotated (flags-with-current-classification flag-dir))

  (classification-summary annotated)
  ;; => (["Uncertain significance" 43]
  ;;     ["Likely benign" 11]
  ;;     ["Benign" 3]
  ;;     ["Benign/Likely benign" 1])

  (->> annotated (filter #(= :not-found (:status %))) (map :scv))

  (->> annotated (filter #(= :updated (:status %)))
       (map (juxt :scv :flagged-version :current-version :classification)))

  ;; Submissions ClinVar no longer counts toward the aggregate record
  (->> annotated (remove #(= "current" (:record-status %))) (map :scv))

  (write-report annotated "/Users/tristan/data/clinvar-flags/current-classifications.tsv")

  )
