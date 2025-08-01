(ns genegraph.user
  (:require [genegraph.framework.protocol]
            [genegraph.framework.kafka :as kafka]
            [genegraph.framework.kafka.admin :as kafka-admin]
            [genegraph.framework.event :as event]
            [genegraph.framework.protocol :as p]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage.rdf.jsonld :as jsonld]
            [genegraph.framework.storage.rdf.query :as rdf-query]
            [genegraph.framework.storage.rocksdb :as rocksdb]
            [genegraph.framework.event.store :as event-store]
            [genegraph.api :as api]
            [genegraph.api.protocol :as ap]
            [genegraph.api.dosage :as dosage]
            [genegraph.api.graphql.response-cache :as response-cache]
            [genegraph.api.graphql.schema.conflicts :as conflicts]
            [genegraph.api.clingen-gene-validity :as cgv]
            [portal.api :as portal]
            [clojure.data.json :as json]
            [clojure.data.csv :as csv]
            [io.pedestal.log :as log]
            [io.pedestal.interceptor :as interceptor]
            [hato.client :as hc]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.edn :as edn]
            [clojure.walk :as walk]
            [clojure.spec.alpha :as spec]
            [clojure.string :as string]
            [nextjournal.clerk :as clerk]
            [genegraph.api.assertion-annotation :as ac]
            [genegraph.api.hybrid-resource :as hr]
            [genegraph.api.ga4gh :as ga4gh]
            [genegraph.api.base.gencc :as gencc]
            [genegraph.api.graphql.schema.sequence-annotation :as sa]
            [genegraph.api.lucene :as lucene]
            [clojure.tools.namespace.repl :as repl])
  (:import [ch.qos.logback.classic Logger Level]
           [org.slf4j LoggerFactory]
           [java.time Instant LocalDate LocalDateTime ZoneOffset]
           [java.io PushbackReader]
           [org.apache.jena.query Dataset ARQ QueryExecutionFactory QueryFactory]
           [org.apache.jena.sparql.algebra Algebra]
           [org.apache.jena.rdf.model Model]))

;; Portal
(comment
  (do
    (def p (portal/open))
    (add-tap #'portal/submit))
  (portal/close)
  (portal/clear)
  )

(comment
  (clerk/serve! {:watch-paths ["notebooks" "src"]})
  (clerk/build! {:paths ["notebooks/cnv.clj"]
                 :package :single-file})
  (clerk/build! {:paths ["notebooks/data_exchange.md"]
                 :package :single-file})
  
  )


;; Test app

(defn log-api-event-fn [e]
  (let [data (::event/data e)]
    (log/info :fn ::log-api-event
              :duration (- (:end-time data) (:start-time data))
              :response-size (:response-size data)
              :handled-by (:handled-by data)
              :status (:status data)
              :error-message (:error-message data))
    e))

(def log-api-event
  (interceptor/interceptor
   {:name ::log-api-event
    :enter (fn [e] (log-api-event-fn e))}))

(def read-api-log
  {:name :read-api-log
   :type :processor
   :subscribe :api-log
   :interceptors [log-api-event]})

(def log-clinvar-curation
  (interceptor/interceptor
   {:name :log-clinvar-curation
    :enter (fn [e] (tap> (assoc e ::interceptor ::log-clinvar-curation)) e)}))

(def read-clinvar-curations
  {:name :read-clinvar-curation
   :type :processor
   :subscribe :clinvar-curation
   :backing-store :api-tdb
   :interceptors [ac/process-annotation]})

(def api-test-app-def
  {:type :genegraph-app
   :kafka-clusters {:data-exchange api/data-exchange}
   :topics {:gene-validity-sepio
            {:name :gene-validity-sepio
             :type :simple-queue-topic}
            :fetch-base-events
            {:name :fetch-base-events
             :type :simple-queue-topic}
            :base-data
            {:name :base-data
             :type :simple-queue-topic}
            :dosage
            {:name :dosage
             :type :simple-queue-topic}
            :api-log
            {:name :api-log
             :type :simple-queue-topic}
            :clinvar-curation
            {:name :clinvar-curation
             :type :simple-queue-topic}}
   :storage {:api-tdb (assoc api/api-tdb
                             :load-snapshot false
                             #_#_:snapshot-handle nil
                             :reset-opts {})
             :response-cache-db (assoc api/response-cache-db
                                       :reset-opts {})
             :text-index (assoc api/text-index :reset-opts {})
             #_#_:sequence-feature-db api/sequence-feature-db
             :object-db (assoc api/object-db
                               :load-snapshot false
                               #_#_:snapshot-handle nil
                               :reset-opts {})}
   :processors {:fetch-base-file api/fetch-base-processor
                :import-base-file api/import-base-processor
                :import-gv-curations cgv/import-gv-curations
                :graphql-api (assoc api/graphql-api
                                    ::event/metadata
                                    {::response-cache/skip-response-cache true})
                :graphql-ready api/graphql-ready
                :import-dosage-curations api/import-dosage-curations
                :read-api-log read-api-log
                :read-clinvar-curations read-clinvar-curations}
   :http-servers api/http-server})

(comment
  (def api-test-app (p/init api-test-app-def))
  (p/start api-test-app)
  (p/stop api-test-app)

  (p/reset api-test-app)

  (defn process-dosage [event]
    (try
      (p/process (get-in api-test-app [:processors
                                       :import-dosage-curations])
                 (assoc event
                        ::event/skip-local-effects true
                        ::event/skip-publish-effects true))
      (catch Exception e (assoc event ::error e))))
  
  )

;; Downloading events

(def root-data-dir "/Users/tristan/data/genegraph-neo/")

(defn get-events-from-topic [topic]
  ;; topic->event-file redirects stdout
  ;; need to supress kafka logs for the duration
  (.setLevel
   (LoggerFactory/getLogger Logger/ROOT_LOGGER_NAME) Level/ERROR)
  (kafka/topic->event-file
   (assoc topic
          :type :kafka-reader-topic
          :kafka-cluster api/data-exchange)
   (str root-data-dir
        (:kafka-topic topic)
        "-"
        (LocalDate/now)
        ".edn.gz"))
  (.setLevel (LoggerFactory/getLogger Logger/ROOT_LOGGER_NAME) Level/INFO))

;; Event Writers

(comment
  (time (get-events-from-topic api/gene-validity-sepio-topic))
  (get-events-from-topic api/actionability-topic)
  (time (get-events-from-topic api/gene-validity-complete-topic))
  (get-events-from-topic api/gene-validity-raw-topic)
  (time (get-events-from-topic api/gene-validity-legacy-complete-topic))
  (time (get-events-from-topic api/dosage-topic))

  (+ 1 1)
  (.start 
   (Thread/new (fn []
                 (println "getting topic")
                 (time (get-events-from-topic api/gene-validity-sepio-topic))
                 (println "complete"))))
  (time (get-events-from-topic api/gene-validity-sepio-topic))
  
  (time (get-events-from-topic api/clinvar-curation-topic))
  (+ 1 1)
)


;; restructuring base, adding ClinVar
(comment
 (->> (-> "base.edn" io/resource slurp edn/read-string)
      (filter #(= "https://www.ncbi.nlm.nih.gov/clinvar/"
                  (:name %)))
      (run! #(p/publish (get-in api-test-app
                                [:topics :fetch-base-events])
                        {::event/data %
                         ::event/key (:name %)})))

 (->> (-> "base.edn" io/resource slurp edn/read-string)
      (remove #(= "https://www.ncbi.nlm.nih.gov/clinvar/"
                  (:name %)))
      (run! #(p/publish (get-in api-test-app
                                [:topics :fetch-base-events])
                        {::event/data %
                         ::event/key (:name %)})))

 (->> (-> "base.edn" io/resource slurp edn/read-string)
      #_(remove #(= "https://www.ncbi.nlm.nih.gov/clinvar/"
                    (:name %)))
      (run! #(p/publish (get-in api-test-app
                                [:topics :fetch-base-events])
                        {::event/data %
                         ::event/key (:name %)})))

 (->> (-> "base.edn" io/resource slurp edn/read-string)
      (filter #(= "http://purl.obolibrary.org/obo/mondo.owl"
                  (:name %)))
      (run! #(p/publish (get-in api-test-app
                                [:topics :fetch-base-events])
                        {::event/data %
                         ::event/key (:name %)})))


 (->> (-> "base.edn" io/resource slurp edn/read-string)
      (filter #(= "http://dataexchange.clinicalgenome.org/affiliations"
                  (:name %)))
      (run! #(p/publish (get-in api-test-app
                                [:topics :fetch-base-events])
                        {::event/data %
                         ::event/key (:name %)})))


 (->> (-> "base.edn" io/resource slurp edn/read-string)
      (filter #(= "https://www.genenames.org/"
                  (:name %)))
      (run! #(p/publish (get-in api-test-app
                                [:topics :fetch-base-events])
                        {::event/data %
                         ::event/key (:name %)})))

 (tap>
  (p/process
   (get-in api-test-app [:processors :import-base-file])
   {::event/data
    (assoc (first (filter #(= "https://www.ncbi.nlm.nih.gov/clinvar/"
                              (:name %))
                          (-> "base.edn" io/resource slurp edn/read-string)))
           :source
           {:type :file
            :base "data/base"
            :file "clinvar.xml.gz"})})

  )

 (tap>
  (count (storage/scan @(get-in api-test-app [:storage :object-db :instance])
                       ["clinvar"])))
 (count
  (rocksdb/range-get @(get-in api-test-app [:storage :object-db :instance])
                     {:prefix ["clinvar"]
                      :return :ref}))
 )


(+ 1 1)
;; Dosage modifications

;; gene_dosage_raw-2024-09-18.edn.gz

;; gene_dosage_raw-2024-10-21.edn.gz

;; Should also deal with dosage records throwing exceptions
;; though possibly the work done for this will handle that issue
(comment
  (event-store/with-event-reader [r (str root-data-dir "gene_dosage_raw-2024-11-18.edn.gz")]
    (->> (event-store/event-seq r)
         (take 1)
         (into [])
         tap>))

  (def chr16p13
    (event-store/with-event-reader [r (str root-data-dir "gene_dosage_raw-2024-09-18.edn.gz")]
      (->> (event-store/event-seq r)
           #_(filter #(re-find #"ISCA-37415" (::event/value %)))
           (filter #(= "ISCA-37415" (::event/key %)))
           (into []))))

  (count chr16p13)

  (defn process-dosage [event]
    (try
      (p/process (get-in api-test-app [:processors
                                       :import-dosage-curations])
                 (assoc event
                        ::event/skip-local-effects true
                        ::event/skip-publish-effects true))
      (catch Exception e (assoc event ::error e))))
  
  (event-store/with-event-reader [r (str root-data-dir
                                         "gene_dosage_raw-2024-11-18.edn.gz")]
    (->> (event-store/event-seq r)
         (take 1)
         (map process-dosage)
         (into [])
         tap>)
    #_(->> (event-store/event-seq r)
         (take 1)
         (map process-dosage)
         #_(filter ::error)
         (into [])
         (map ::event/data)
         tap>
         #_(run! #(rdf/pp-model (::dosage/model %)))))
  
  ;; ISCA-37421
  (event-store/with-event-reader
      [r (str root-data-dir "gene_dosage_raw-2024-11-18.edn.gz")]
      (->> (event-store/event-seq r)
           (run! #(p/publish (get-in api-test-app
                                     [:topics :dosage])
                             %))))

  (+ 1 1)
  
  (count errors)

  (mapv ::event/key errors)

  (-> errors
      first
      event/deserialize
      ::event/data
      dosage/gene-dosage-report
      tap>)

  (tap> (mapv process-dosage errors))
  (->> recent-dosage-records
       (map process-dosage)
       (remove ::spec/invalid)
       (take 1)
       (into [])
       #_tap>
       (run! #(rdf/pp-model (:genegraph.api.dosage/model %))))

  (->> chr16p13
       (map process-dosage)
       (remove ::spec/invalid)
       (take-last 1)
       (into [])
       #_tap>
       (run! #(rdf/pp-model (:genegraph.api.dosage/model %))))
  (->> chr16p13
       (map process-dosage)
       (remove ::spec/invalid)
       (take-last 1)
       (into [])
       (map #(-> %
                 ::event/data
                 dosage/location
                 rdf/statements->model
                 rdf/pp-model))

       #_(run! #(rdf/pp-model (:genegraph.api.dosage/model %))))

  #_(get-in chr16p13data [:fields :customfield_10532]))

;; clinvar transform
(comment
  (count
   (rocksdb/range-get @(get-in api-test-app [:storage :object-db :instance])
                      {:prefix ["clinvar"]
                       :return :ref}))

  (->> (rocksdb/range-get @(get-in api-test-app
                                   [:storage :object-db :instance])
                          {:prefix ["clinvar"]
                           :return :ref})
       (map deref)
       (filter (fn [v] (some #(= "conflicting data from submitters"
                                 (:Classification %))
                             (:classifications v))))
       (take 5)
       tap>)
  )

;; clinvar comparision
(comment
  (def larry-clinvar-path
    "/Users/tristan/downloads/bq-results-20241007-004305-1728261823900.json")
  (with-open [r (io/reader larry-clinvar-path)]
    (->> (line-seq r)
         (take 5)
         (map #(json/read-str % :key-fn keyword))
         (into [])
         tap>))
  (time
   (def larry-vars
     (with-open [r (io/reader larry-clinvar-path)]
       (->> (line-seq r)
            (map #(json/read-str % :key-fn keyword))
            (map :variation_id)
            set))))
  
  (time
   (def tristan-vars
     (->> (rocksdb/range-get @(get-in api-test-app [:storage :object-db :instance])
                             {:prefix ["clinvar"]
                              :return :ref})
          (map deref)
          (map :variation-id)
          set)))

  (count larry-vars)
  (count tristan-vars)

  (with-open [r (io/reader larry-clinvar-path)]
    (->> (line-seq r)
         (map #(json/read-str % :key-fn keyword))
         (remove #(tristan-vars (:variation_id %)))
         (take 5)
         (into [])
         tap>))

  (with-open [r (io/reader larry-clinvar-path)]
    (->> (line-seq r)
         (map #(json/read-str % :key-fn keyword))
         (remove #(tristan-vars (:variation_id %)))
         (remove :issue)
         (filter :variant_length)
         (map #(assoc % ::length (Integer/parseInt (:variant_length %))))
         (filter #(< 1000 (::length %)))
         (take 5)
         (into [])
         tap>
         #_count))

  (with-open [r (io/reader larry-clinvar-path)]
    (->> (line-seq r)
         (map #(json/read-str % :key-fn keyword))
         (remove #(tristan-vars (:variation_id %)))
         (map :issue)
         frequencies))

  (with-open [r (io/reader larry-clinvar-path)]
    (->> (line-seq r)
         (map #(json/read-str % :key-fn keyword))
         (map :variation_type)
         frequencies
         tap>))

  (with-open [r (io/reader larry-clinvar-path)]
    (->> (line-seq r)
         (map #(json/read-str % :key-fn keyword))
         #_(remove #(tristan-vars (:variation_id %)))
         (map :variation_id)
         frequencies
         (filter (fn [[_ c]] (< 1 c)))
         count))

  (count tristan-vars)
  (count larry-vars)

  

  (->> (rocksdb/range-get @(get-in api-test-app [:storage :object-db :instance])
                          {:prefix ["clinvar"]
                           :return :ref})
       (map deref)
       (remove #(larry-vars (:variation-id %)))
       (take 5)
       (mapv :variation-id))

  (portal/clear)

  (->> (rocksdb/range-get @(get-in api-test-app [:storage :object-db :instance])
                          {:prefix ["clinvar"]
                           :return :ref})
       (map deref)
       (filter #(tristan-not-larry (:variation-id %)))
       (filter #(= "Duplication" (:variant-type %)))
       #_(map :variant-type)
       #_frequencies
       tap>)

  (def cnv-types
  #{"Deletion"
    "Duplication"
    "copy number gain"
    "copy number loss"})
  
  (defn variation-length [{:keys [location]}]
    (if (seq location)
      (apply max
             (mapv
              (fn [{:keys [display_start display_stop]}]
                (if (and display_start display_stop)
                  (- display_stop display_start)
                  -1))
              location))
      -1))
  
  (time
   (def cnv-sample
     (->> (rocksdb/range-get @(get-in api-test-app [:storage :object-db :instance])
                             {:prefix ["clinvar"]
                              :return :ref})
          (map deref)
          (filter #(and
                    (cnv-types (:variant-type %))
                    (or (:copy-count %)
                        (< 1000 (variation-length %)))))
          (take 5))))

  (count cnv-sample)

  
  (def larry-not-tristan (set/difference larry-vars tristan-vars))
  (count larry-not-tristan)

  (with-open [w (io/writer "/users/tristan/Desktop/larry-not-tristan.json")]
    (json/write (into [] larry-not-tristan) w))
  
  
  (def tristan-not-larry (set/difference tristan-vars larry-vars))
  (count tristan-not-larry)

  )


(comment
  (with-open [r (-> "/Users/tristan/Desktop/erin-report.edn"
                    io/reader
                    PushbackReader.)
              w (io/writer "/Users/tristan/Desktop/gcep-report.csv")]
    (->> (edn/read r)
         (mapv (fn [[gcep curations]]
                 [gcep
                  (get curations "NewCuration" 0)
                  (reduce + (vals (dissoc curations "NewCuration")))]))
         (cons ["GCEP" "New Curations" "Re-curations"])
         (csv/write-csv w)))

  )


;; GFF integration and testing
(comment

  (run!
   #(p/publish (get-in api-test-app [:topics :fetch-base-events]) %)
   (->> "base.edn"
        io/resource
        slurp
        edn/read-string
        (filter #(= :genegraph.api.base/load-gff (:action %)))
        (mapv (fn [e] {::event/data e}))))

  (run!
   #(p/publish (get-in api-test-app [:topics :fetch-base-events]) %)
   (->> "base.edn"
        io/resource
        slurp
        edn/read-string
        (filter #(= :genegraph.api.base/load-gff (:action %)))
        (mapv (fn [e] {::event/data e}))))

  (run!
   #(p/publish (get-in api-test-app [:topics :base-data]) %)
   (->> "base.edn"
        io/resource
        slurp
        edn/read-string
        (filter #(= "https://ncbi.nlm.nih.gov/genomes/GCF_000001405.40_GRCh38.p14_genomic.gff"
                    (:name %)))
        (mapv (fn [e] {::event/data
                       (assoc e
                              :genegraph.api.base/handle
                              {:type :file
                               :base "data/base/"
                               :path "GRCh38.gff.gz"})}))))
  (tap> api-test-app)

  (->> (rocksdb/range-get
        @(get-in api-test-app [:storage :object-db :instance])
        {:start [:sequences
                 :so/Gene
                 "https://identifiers.org/refseq:NC_000001.11"
                 30000]
         :end [:sequences
               :so/Gene
               "https://identifiers.org/refseq:NC_000001.11"
               50500]
         :return :ref})
       (map deref)
       tap>)

  (->> (storage/read
        @(get-in api-test-app [:storage :object-db :instance])
        [:objects "https://identifiers.org/ncbigene:100302278"])
       tap>)

  (run!
   #(p/publish (get-in api-test-app [:topics :base-data]) %)
   (->> "base.edn"
        io/resource
        slurp
        edn/read-string
        (filter #(= "https://www.ncbi.nlm.nih.gov/clinvar/"
                    (:name %)))
        (mapv (fn [e] {::event/data
                       (assoc e
                              :source
                              {:type :file
                               :base "data/base/"
                               :path "clinvar.xml.gz"})}))))

  (let [object-db @(get-in api-test-app [:storage :object-db :instance])
        tdb @(get-in api-test-app [:storage :api-tdb :instance])
        var-query (rdf/create-query "select ?x where { ?x a :cg/CanonicalVariant }")]
    (tap> (storage/read object-db [:objects "https://identifiers.org/clinvar:536"]))
    #_(rdf/tx tdb
      (count (var-query tdb)))
    )

  #_(rdf/statements->model '(["https://identifiers.org/clinvar:536"
                              :cg/CompleteOverlap
                              :cg/Bupkis]))

  
  )



;; Testing dosage queries
(comment
  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        q (rdf/create-query "
select ?x where 
{?x a ?type}
 limit 5
")]
    (rdf/tx tdb
      (->> (q tdb {:type :cg/GeneticConditionMechanismProposition})
           (mapv #(rdf/ld1-> % [:cg/feature])))))

  ;; Deletion variants with complete overlap with haplo 3 gene
  ;; Validated!
  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        q (rdf/create-query "
select ?variant where 
{ ?assertion :cg/evidenceStrength :cg/DosageSufficientEvidence ;
  :cg/subject ?dosageProp .
  ?dosageProp :cg/mechanism :cg/Haploinsufficiency ;
  a :cg/GeneticConditionMechanismProposition ;
  :cg/feature ?feature .
  ?variant :cg/CompleteOverlap ?feature ;
  :ga4gh/copyChange :efo/copy-number-loss .
  ?pathProp :cg/variant ?variant .
  ?pathAssertion :cg/subject ?pathProp ;
  :cg/direction :cg/Refutes .
  FILTER NOT EXISTS { ?pathAssertion :cg/reviewStatus :cg/Flagged }
}
")]
    (rdf/tx tdb
      (->> (q tdb {:type :cg/GeneticConditionMechanismProposition})
           first
)))

    (+ 1 1)
  
  )

;; load resource decriptions
(comment
  "https://genegraph.app/resources"
  (->> (-> "base.edn" io/resource slurp edn/read-string)
       (filter #(= "https://genegraph.app/resources"
                   (:name %)))
       (run! #(p/publish (get-in api-test-app
                                 [:topics :fetch-base-events])
                         {::event/data %
                          ::event/key (:name %)})))

(let [tdb @(get-in api-test-app [:storage :api-tdb :instance])]
    (rdf/tx tdb
      (-> (rdf/resource :cg/Benign tdb)
          (rdf/ld1-> [:rdfs/label]))))
  

  )


;; load clinvar submitters
(comment
  (->> (-> "base.edn" io/resource slurp edn/read-string)
       (filter #(= "https://www.ncbi.nlm.nih.gov/clinvar/submitters"
                   (:name %)))
       (run! #(p/publish (get-in api-test-app
                                 [:topics :fetch-base-events])
                         {::event/data %
                          ::event/key (:name %)})))

  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])]
    (rdf/tx tdb
      (rdf/resource (rdf/blank-node) tdb)
      #_(-> (rdf/resource "https://identifiers.org/clinvar.submitter:1006" tdb)
            (rdf/ld1-> [:rdfs/label]))))
  
  
  )




(comment
  (def path-calls-with-genes
    (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
          object-db @(get-in api-test-app [:storage :object-db :instance])
          q (rdf/create-query "
select ?class ?gene where {
?prop a :cg/VariantPathogenicityProposition .
?prop :cg/variant ?variant .
?variant :ga4gh/copyChange :efo/copy-number-gain .
?variant :cg/CompleteOverlap ?gene .
?class :cg/subject ?prop .
?class a :cg/EvidenceStrengthAssertion .
?class :cg/direction :cg/Supports .
}")]
      (rdf/tx tdb
        (-> (group-by :class
                      (q tdb {::rdf/params {:type :table}}))
            (update-vals #(set (map :gene %)))))))

  (def non-path-calls-with-genes
    (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
          object-db @(get-in api-test-app [:storage :object-db :instance])
          q (rdf/create-query "
select ?class ?gene where {
?prop a :cg/VariantPathogenicityProposition .
?prop :cg/variant ?variant .
?variant :ga4gh/copyChange :efo/copy-number-gain .
?variant :cg/CompleteOverlap ?gene .
?class :cg/subject ?prop .
?class a :cg/EvidenceStrengthAssertion .
FILTER NOT EXISTS { ?class :cg/direction :cg/Supports } .
}")]
      (rdf/tx tdb
        (-> (group-by :class
                      (q tdb {::rdf/params {:type :table}}))
            (update-vals #(set (map :gene %)))))))

  (count path-calls-with-genes)

  (count non-path-calls-with-genes)
  
  (->> non-path-calls-with-genes
       #_(take 10)
       (filter (fn [[_ g1]]
                 (some #(set/superset? g1 %)
                       (vals path-calls-with-genes))))
       count)
  
  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        q (rdf/create-query "
select ?class where {
?class :cg/direction :cg/Supports ;
:cg/subject / :cg/variant / :ga4gh/copyChange :efo/copy-number-loss .
}
")]
    (rdf/tx tdb
      (->> (q tdb)
           first)))
  (+ 1 1)
  (println
   (rdf/create-query "
select ?copyChange where {
?class :cg/direction :cg/Supports ;
:cg/subject / :cg/variant / :ga4gh/copyChange :efo/copy-number-loss .
}
"))
  
  (tap> (+ 1 1))

  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        q (rdf/create-query "
select ?ann where {
?ann a :cg/AssertionAnnotation .
}
")]
    (rdf/tx tdb
      (->> (q tdb)
           #_(run! #(storage/delete tdb (str %)))
           count)))
  
  )
(comment
  (def stage-app (p/init api/graphql-endpoint-def))
  (p/start stage-app)
  (p/stop stage-app)
  (+ 1 1)
  )


;; Troubleshooting 'no comment'
(comment
  ;; SCV004933996
  (event-store/with-event-reader [r (str root-data-dir "ggapi-clinvar-curation-stage-1-2024-12-18.edn.gz")]
    (->> (event-store/event-seq r)
         count))

  
  (event-store/with-event-reader [r (str root-data-dir "ggapi-clinvar-curation-stage-1-2024-12-18.edn.gz")]
    (->> (event-store/event-seq r)
         #_(filter #(re-find #"SCV004933996" (::event/value %)))
         (take 1)
         (mapv event/deserialize)
         tap>))

  (event-store/with-event-reader [r (str root-data-dir "ggapi-clinvar-curation-stage-1-2024-12-18.edn.gz")]
    (->> (event-store/event-seq r)
         (filter #(re-find #"SCV004933996" (::event/value %)))
         (mapv event/deserialize)
         tap>))

  (portal/clear)



  
  )


;; Per Christa -- path assertions 2020-2024 -- 2022-2024
;; email from Erin Riggs 12/18/2024


(comment
  
  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        q (rdf/create-query "
select ?ann where {
?ann a :cg/AssertionAnnotation .
}
")]
    (rdf/tx tdb
      (->> (q tdb)
           #_(run! #(storage/delete tdb (str %)))
           count)))
  )


;; 2025-01-15 integrating dosage regions


(comment
  
  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        q (rdf/create-query "
select ?ann where {
?ann a :cg/AssertionAnnotation .
}
")]
    (rdf/tx tdb
      (->> (q tdb)
           #_(run! #(storage/delete tdb (str %)))
           count)))


  ;; ISCA-37445
  (def bwrs
    (event-store/with-event-reader
        [r (str root-data-dir "gene_dosage_raw-2025-01-15.edn.gz")]
      (->> (event-store/event-seq r)
           (filter #(re-find #"ISCA-37445" (::event/value %)))
           (into []))))

  (def bwrs1 (-> bwrs first event/deserialize))

  (-> bwrs1 process-dosage tap>)

  (count bwrs)
  (->> bwrs
       (take 1)
       (run! process-dosage)
       #_(run! #(-> %
                    process-dosage
                    ::dosage/model)))


  ;;get max dosage region size
  ;; 12MB is max size
  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        q (rdf/create-query "
select ?x where {
?x a :cg/DosageRegion .
}
")]
    (rdf/tx tdb
      (->> (q tdb)
           (mapv #(let [r (hr/hybrid-resource
                           %
                           {:object-db object-db :tdb tdb})]
                    (try
                      (ga4gh/max-size r)
                      (catch Exception e r))))
           (apply max))))
  
  
  (time
   (event-store/with-event-reader
       [r (str root-data-dir "gene_dosage_raw-2025-06-03.edn.gz")]
       (->> (event-store/event-seq r)
            (run! #(p/publish (get-in api-test-app [:topics :dosage]) %)))))

  (+ 1 1)

  
  ;; Building query for region conflict overlaps
  ;; Haploinsufficiency region conflicts
  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        q (rdf/create-query "
select ?va where {
?region a :cg/DosageRegion .
?prop :cg/feature ?region ;
      :cg/mechanism :cg/Haploinsufficiency .
?a :cg/subject ?prop ;
   :cg/evidenceStrength :cg/DosageSufficientEvidence .
?v :cg/CompleteOverlap ?region ;
   :ga4gh/copyChange :efo/copy-number-loss .
?vprop :cg/variant ?v .
?va :cg/subject ?vprop .
filter not exists { ?va :cg/direction :cg/Supports }
}
")]
    (rdf/tx tdb
      (->> (q tdb)
           #_(take 5)
           #_(into [])
           (mapv #(hr/hybrid-resource
                   %
                   {:object-db object-db :tdb tdb}))
           count)))

  ;; Triplosensitivity region conflicts
  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        q (rdf/create-query "
select ?va where {
?region a :cg/DosageRegion .
?prop :cg/feature ?region ;
      :cg/mechanism :cg/Triplosensitivity .
?a :cg/subject ?prop ;
   :cg/evidenceStrength :cg/DosageSufficientEvidence .
?v :cg/CompleteOverlap ?region ;
   :ga4gh/copyChange :efo/copy-number-gain .
?vprop :cg/variant ?v .
?va :cg/subject ?vprop .
filter not exists { ?va :cg/direction :cg/Supports }
}
")]
    (rdf/tx tdb
      (->> (q tdb)
           (take 5)
           #_(into [])
           (mapv #(hr/hybrid-resource
                   %
                   {:object-db object-db :tdb tdb}))
           count)))

  (portal/clear)
  
  
  
  
  )


;; testing new affils service
(comment 
  (def http-client (hc/build-http-client {}))


  (do
    (def test-affils-api "https://affils-test.clinicalgenome.org/api/")
    (def prod-affils-api "https://affils.clinicalgenome.org/api/")
    (def test-affils-api-key "AwCuqYcu.ZMYcwGsPPzNmQekBLi6EMGflmaRte3Cn")
    (def prod-affils-api-key "TfZhEETS.YUpN38StKbRTNJhZByU5A1XBmh6ZKZQ8")
    (def test-affils-list "https://affils-test.clinicalgenome.org/api/affiliations_list/"))
  (-> (hc/get
       test-affils-list
       {:headers {"X-Api-Key" test-affils-api-key}
        :http-client http-client})
      :body
      json/read-str
      tap>)
  
  )


;; load gene curations

(defn dominant-negative? [assertion]
  (if-let [assertion-description (rdf/ld1-> assertion [:dc/description])]
    (->> assertion-description
         string/lower-case
         (re-find #"dominant negative"))
    false))
(count "http://dataexchange.clinicalgenome.org/gci/")
;; https://search.clinicalgenome.org/kb/gene-validity/CGGV:570947a0-3e45-419c-bc22-0fdc60ca6009
(do
  (defn assertion->website-url [assertion]
    (let [[_ uuid] (re-find #"http://dataexchange\.clinicalgenome\.org/gci/(.+)v.+"
                          (str assertion))]
      (str "https://search.clinicalgenome.org/kb/gene-validity/CGGV:"
           uuid)))
  (assertion->website-url "http://dataexchange.clinicalgenome.org/gci/570947a0-3e45-419c-bc22-0fdc60ca6009v1.0"))

(def experimental-evidence
  (rdf/create-query "
select ?ex where {
?assertion :cg/evidence ?ex .
?ex :cg/specifiedBy :cg/GeneValidityOverallExperimentalEvidenceCriteria .
}"))

(defn experimental-evidence-score [assertion]
  (if-let [ex (first (experimental-evidence assertion {:assertion assertion}))]
    (rdf/ld1-> ex [:cg/strengthScore])
    0))

(comment

  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        q (rdf/create-query "
select ?gene where {
?gene a :so/Gene .
}
limit 5")]
    (rdf/tx tdb
      (->> (q tdb)
           (mapv #(rdf/ld-> % [:owl/sameAs]))
           )))

  "1. All curations that are Moderate and above, AD, and have dominant negative in the free text of the evidence summary (I realize you pulled this for me previously but the curation links no longer worked when I went back to refer to it)"

  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        q (rdf/create-query "
select ?assertion where {
?prop a :cg/GeneValidityProposition ;
      :cg/modeOfInheritance :hp/AutosomalDominantInheritance .
?assertion :cg/subject ?prop ;
           :cg/evidenceStrength ?strength .
values ?strength { :cg/Definitive :cg/Strong  :cg/Moderate } 
}
")]
    (rdf/tx tdb
      (with-open [w (io/writer "/users/tristan/Desktop/moderate+-dn.csv")]
        (->> (q tdb)
             (filter dominant-negative?)
             #_(take 5)
             #_(into [])
             #_(mapv #(hr/hybrid-resource
                       %
                       {:object-db object-db :tdb tdb}))
             (mapv (fn [a]
                     [(rdf/ld1-> a
                                 [:cg/subject
                                  :cg/gene
                                  [:owl/sameAs :<]
                                  :skos/prefLabel])
                      (re-find #"\w+$"
                               (str (rdf/ld1-> a [:cg/evidenceStrength])))
                      (experimental-evidence-score a)
                      (assertion->website-url a)]))
             set
             #_(take 5)
             (sort-by first)
             (concat [["Gene" "Classification" "Experimental Evidence Total" "Link"]])
             count
             #_(csv/write-csv w)))))

  "2. All curations that are Strong or Definitive, AD, have dominant negative in the free text, but don't have much experimental evidence. Jonathan suggested maybe 4 points or less, but since there won't be many of these curations anyway, I guess you could consider bucketing them... whatever makes sense to you... I'm trying to pull curations that might have a borderline mechanism so I can stress test the framework."

  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        q (rdf/create-query "
select ?assertion where {
?prop a :cg/GeneValidityProposition ;
      :cg/modeOfInheritance :hp/AutosomalDominantInheritance .
?assertion :cg/subject ?prop ;
           :cg/evidenceStrength ?strength .
values ?strength { :cg/Definitive :cg/Strong }
}
")]
    (rdf/tx tdb
      (with-open [w (io/writer "/users/tristan/Desktop/moderate+-dn.csv")]
        (csv/write-csv w
                       (->> (q tdb)
                            (filter dominant-negative?)
                            #_(take 5)
                            #_(into [])
                            #_(mapv #(hr/hybrid-resource
                                      %
                                      {:object-db object-db :tdb tdb}))
                            (mapv (fn [a]
                                    [(rdf/ld1-> a
                                                [:cg/subject
                                                 :cg/gene
                                                 [:owl/sameAs :<]
                                                 :skos/prefLabel])
                                     (assertion->website-url a)]))
                            set
                            (sort-by first))))))
  
  (event-store/with-event-reader
      [r (str root-data-dir "gg-gvs2-stage-1-2025-01-30.edn.gz")]
      (->> (event-store/event-seq r)
           (take 1)
           #_(map ::event/key)
           #_(map event/deserialize)
           #_(map #(api/has-publish-action (::event/data %)))
           #_(run! #(rdf/pp-model (::event/data %)))
           #_(run! #(p/publish (get-in api-test-app [:topics :gene-validity-sepio]) %))))
  (time
   (event-store/with-event-reader
       [r (str root-data-dir "gg-gvs2-stage-1-2025-02-23.edn.gz")]
     (->> (event-store/event-seq r)
          #_(take 1)
          #_(map event/deserialize)
          #_(map #(api/has-publish-action (::event/data %)))
          #_(run! #(rdf/pp-model (::event/data %)))
          (run! #(p/publish (get-in api-test-app [:topics :gene-validity-sepio]) %)))))
  )


;; exploration building filters
(comment
  (gensym "filter_name")


    (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
          object-db @(get-in api-test-app [:storage :object-db :instance])
          q (rdf/create-query "
select ?assertion where {
?prop a :cg/VariantPathogenicityProposition .
?assertion :cg/subject ?prop .
?prop :cg/variant ?variant .
?variant :ga4gh/copyChange ?copy_change
}
")]
    (rdf/tx tdb
      (->> (q tdb {:copy_change (rdf/resource "http://www.ebi.ac.uk/efo/EFO_0030067")})
           (take 1)
           #_(into [])
           (mapv #(hr/hybrid-resource
                   %
                   {:object-db object-db :tdb tdb}))
           count)))
  )


(rdf/resource "EFO:0030070")

;; Put together list of groups to assemble for ClinVar cleanup trial

(comment
    (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
          object-db @(get-in api-test-app [:storage :object-db :instance])
          hybrid-db {:object-db object-db :tdb tdb}
          query (rdf/create-query "
select ?assertion where {
?annoation a :cg/AssertionAnnotation ;
:cg/subject ?assertion .
}")]
      (rdf/tx tdb
          (->> (query tdb)
               (mapv #(-> (hr/hybrid-resource
                           %
                           hybrid-db)
                          (hr/path-> hybrid-db [:cg/contributions])
                          first
                          (hr/path1-> hybrid-db [:cg/agent])
                          (hr/path1-> (assoc hybrid-db :primitive true)
                                      [:rdfs/label])))
               frequencies
               (sort-by val)
               reverse
               tap>)))
  )

;; Query statistics.
;; Need to figure out how to optimize sub-optimal queries

(comment
  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        query (rdf/create-query "
select ?assertion where {
?annoation a :cg/AssertionAnnotation ;
:cg/subject ?assertion .
}")]
    #_query
    (QueryExecutionFactory/create query))

  )


;; Exploring operations for negation queries
(comment
  (-> (QueryFactory/create "
prefix cg: <http://dataexchange.clinicalgenome.org/terms/>
select (COUNT(?gene) AS ?geneCount) where {
  ?x a cg:GeneValidityProposition ;
  cg:gene ?gene .
} GROUP BY ?gene ")
      Algebra/compile
      str
      println)

    (-> (QueryFactory/create "
prefix cg: <http://dataexchange.clinicalgenome.org/terms/>
select ?x where {
  ?x a cg:EvidenceStrengthAssertion ;
  cg:contributions ?contrib .
  ?contrib cg:role cg:Approver ;
  cg:date ?date .
  filter (?date > \"2020\")
} ")
      Algebra/compile
      str
      println)
  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        q "
prefix cg: <http://dataexchange.clinicalgenome.org/terms/>
select ?x where {
  ?x a cg:CanonicalVariant
{ select ?x (count(?gene) AS ?geneCount)
  where {
  ?x cg:CompleteOverlap ?gene .
  }
  group by ?x
}
filter (?geneCount > 50)
} limit 5"
        qe (rdf/create-query q)]
    (-> (QueryFactory/create q)
        Algebra/compile
        str
        println)
    (rdf/tx tdb
      (tap> (qe tdb)))
    )
  
  )


(comment

  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        q2 (rdf/create-query
            [:project ['feature]
             [:bgp
              ['x :cg/subject 'proposition]
              ['proposition :cg/variant 'variant]
              ['variant :cg/CompleteOverlap 'feature]]])
        q3 (rdf/create-query
            [:project ['feature]
             [:bgp
              ['gv_prop :cg/gene 'feature]
              ['gv_prop :rdf/type :cg/GeneValidityProposition]
              ['gv_assertion :cg/subject 'gv_prop]]])
        q (rdf/create-query "
select ?assertion where {
?prop a :cg/GeneValidityProposition ;
      :cg/modeOfInheritance :hp/AutosomalDominantInheritance .
?assertion :cg/subject ?prop ;
           :cg/evidenceStrength ?strength .
values ?strength { :cg/Definitive :cg/Strong  :cg/Moderate } 
}
")]
    (rdf/tx tdb
      (with-open [w (io/writer "/users/tristan/Desktop/moderate+-dn.csv")]
        (->> (q2 tdb)
             first))))

 )


(comment
  ;; Request from Erin:
  "Tristan, how difficult would it be for you to create a report for us with the following gene-disease validity information?
 
For any curation with a classification of Limited, Disputed, or Refuted, could we have a spreadsheet with the following:
Gene
Disease
MOI
GCEP
Classification
Final Approval Date
Date of First Report (this should be a unique data point from the GCI – we are asked to check which of the publications this is, but let us know if you don’t have this)
Total points
Genetic Evidence points
Experimental Evidence points
 
Additionally, are you able to easily tell if any of these have ever been recurated?  We thought you might be able to with the backfilled versioning information you are working on, but if that part isn’t ready at the moment, disregard.
 
Thanks,
Erin"

  (def example-query (rdf/create-query "
select ?assertion where {
?prop a :cg/GeneValidityProposition ;
      :cg/modeOfInheritance :hp/AutosomalDominantInheritance .
?assertion :cg/subject ?prop ;
           :cg/evidenceStrength ?strength .
values ?strength { :cg/Definitive :cg/Strong  :cg/Moderate } 
}
"))

  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        q (rdf/create-query [:project ['x]
                             [:filter
                              [:in 'classification :cg/Refuted :cg/Disputed :cg/Limited]
                              [:bgp
                               ['x :cg/evidenceStrength 'classification]
                               ['x :rdf/type :cg/EvidenceStrengthAssertion]
                               ['x :cg/subject 'prop]
                               ['prop :rdf/type :cg/GeneValidityProposition]]]])
        approval (rdf/create-query [:project ['x]
                                    [:bgp
                                     ['assertion :cg/contributions 'x]
                                     ['x :cg/role :cg/Approver]]])
        genetic-evidence (rdf/create-query [:project ['x]
                                    [:bgp
                                     ['assertion :cg/evidence 'x]
                                     ['x
                                      :cg/specifiedBy
                                      :cg/GeneValidityOverallGeneticEvidenceCriteria]]])
        experimental-evidence (rdf/create-query [:project ['x]
                                            [:bgp
                                             ['assertion :cg/evidence 'x]
                                             ['x
                                              :cg/specifiedBy
                                              :cg/GeneValidityOverallExperimentalEvidenceCriteria]]])
        citations (rdf/create-query "
select ?x where {
?assertion :cg/evidence * / :dc/source ?x
}
")
        resources (rdf/create-query "
select ?x where {
  ?x a :dc/BibliographicResource .
}")
        first-publication (fn [a]
                            (->> #_(citations tdb {:assertion a})
                                 (storage/read tdb (str (rdf/ld1-> a [:cg/subject])))
                                 resources
                                 (mapv (fn [p] {:publication (str p)
                                                :date (rdf/ld1-> p [:dc/date])}))
                                 (remove #(nil? (:date %)))
                                 (sort-by :date)
                                 first))
        ->row (fn [a] [(rdf/ld1-> a [:cg/subject
                                     :cg/gene
                                     [:owl/sameAs :<]
                                     :skos/prefLabel])
                       (rdf/ld1-> a [:cg/subject
                                     :cg/disease
                                     :rdfs/label])
                       (rdf/ld1-> a [:cg/subject
                                     :cg/modeOfInheritance
                                     :rdfs/label])
                       (-> (rdf/ld1-> a [:cg/evidenceStrength])
                           rdf/->kw
                           name)
                       (rdf/ld1-> (first (approval tdb {:assertion a}))
                                  [:cg/agent
                                   :rdfs/label])
                       (rdf/ld1-> (first (approval tdb {:assertion a}))
                                  [:cg/date])
                       (:publication (first-publication a))
                       (:date (first-publication a))
                       (rdf/ld1-> a [:cg/strengthScore])
                       (rdf/ld1-> (first (genetic-evidence tdb {:assertion a}))
                                  [:cg/strengthScore])
                       (rdf/ld1-> (first (experimental-evidence tdb {:assertion a}))
                                  [:cg/strengthScore])
                       (rdf/ld1-> a [:cg/version])])
        q2  (rdf/create-query "
select ?x where { ?x a :cg/GeneValidityProposition } limit 1")]
    (rdf/tx tdb
      #_(->> (q2 tdb)
           #_count
           (take 1)
           (run! #(rdf/pp-model (storage/read tdb (str %)))))
      (->> (storage/read
           tdb
           "http://dataexchange.clinicalgenome.org/gci/5734978b-bdf9-43e3-baf6-c50b83c15abc")
           rdf/pp-model
           )
      #_(with-open [w (io/writer "/users/tristan/Desktop/limited-.csv")]
        (->> (q tdb)
             #_(take 1)
             (mapv ->row)
             (cons ["gene"
                    "disease"
                    "moi"
                    "classification"
                    "gcep"
                    "approval date"
                    "first publication"
                    "publication date"
                    "overall score"
                    "genetic evidence score"
                    "experimental strength score"
                    "version"])
             (csv/write-csv w)))))
  

  )


(comment

  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        hybrid-db {:tdb tdb :object-db object-db}
        protein-coding-gene-query (rdf/create-query
            [:project ['x]
             [:bgp
              ['x :rdf/type :so/GeneWithProteinProduct]]])]
    (rdf/tx tdb
      (->> (protein-coding-gene-query tdb {::rdf/params {:limit 5}})
           (mapv #(hr/hybrid-resource % hybrid-db))
           first
           tap>)))

 )

(def genes-query
  (rdf/create-query
   [:project ['x]
    [:bgp
     ['x :rdf/type :so/Gene]]]))

(defn hgnc->entrez
  "Generate a map from HGNC IDs (formatted HGNC:1234) to Entrez URLs."
  [tdb]
  (let [qr (genes-query tdb)]
    (zipmap
     (mapv #(some->> (rdf/ld-> % [:owl/sameAs])
                     (map (fn [r]
                            (re-find
                             #"https://identifiers.org/hgnc:(\d+)"
                             (str r))))
                     (remove nil?)
                     first
                     second
                     (str "HGNC:"))
           qr)
     (mapv str qr))))

;; Integrating GENCC
(comment

  (->> (-> "base.edn" io/resource slurp edn/read-string)
       (filter #(= "https://genegraph.app/resources"
                   (:name %)))
       (run! #(p/publish (get-in api-test-app
                                 [:topics :fetch-base-events])
                         {::event/data %
                          ::event/key (:name %)})))

(->> (-> "base.edn" io/resource slurp edn/read-string)
     (filter #(= "https://thegencc.org/"
                 (:name %)))
     (run! #(p/publish (get-in api-test-app
                               [:topics :fetch-base-events])
                       {::event/data %
                        ::event/key (:name %)})))

  (run!
   #(p/publish (get-in api-test-app [:topics :base-data]) %)
   (->> "base.edn"
        io/resource
        slurp
        edn/read-string
        (filter #(= "https://thegencc.org/"
                    (:name %)))
        (mapv (fn [e] {::event/data
                       (assoc e
                              :source
                              {:type :file
                               :base "data/base/"
                               :path "gencc.csv"})}))))

  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        hybrid-db {:tdb tdb :object-db object-db}
        gencc-query (rdf/create-query
                     [:project ['x]
                      [:bgp
                       ['x :dc/source :cg/GenCC]
                       ['x :cg/subject 'prop]]])]
    (rdf/tx tdb
      (->> (gencc-query tdb #_{::rdf/params {:limit 5}})
           #_(mapv #(hr/hybrid-resource % hybrid-db))
           #_(mapv #(rdf/ld-> % [:cg/contributions]))
           count
           )))


    
  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])]
    (rdf/tx tdb
      (with-open [w (io/writer "data/hgnc-entrez.edn")]
        (binding [*out* w]
          (clojure.pprint/pprint
           (hgnc->entrez tdb))))))

  
  )

;; extracting classes; names for data model
(comment
  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        q (rdf/create-query "select ?class where {
?x a ?class .
}
")]
    (rdf/tx tdb
      (->> (q tdb)
           (map rdf/->kw)
           (remove #(= "cg" (namespace %)))
           sort
           (into [])
           clojure.pprint/pprint)))


  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        q (rdf/create-query "select ?class ?label where {
?x a ?class .
}
")]
    (rdf/tx tdb
      (->> (q tdb)
           (mapv (fn [r] [(rdf/->kw r) (rdf/ld1-> r [:rdfs/label])]))
           (remove #(= "cg" (namespace (first %))))
           (sort-by first)
           (into [])
           clojure.pprint/pprint)))

  ;; discovered error with many gene validity curations
  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        q (rdf/create-query "select ?x where {
?x a <http://purl.obolibrary.org/obo/SEPIO_0004116> .
}
")]
    (rdf/tx tdb
      (->> (q tdb)
           count)))

  
  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        q (rdf/create-query "select ?x where {
?x a :cg/MolecularSequenceObservation .
}
")]
    (rdf/tx tdb
      (->> (q tdb)
           count)))

  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        q (rdf/create-query "select ?x where {
?fa a :dc/BibliographicResource ;
?x ?o .
}
")]
    (rdf/tx tdb
      (->> (q tdb)
           (mapv rdf/->kw))))

    (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        q (rdf/create-query "select ?x where {
?x a :cg/FamilyCosegregation ;
:cg/sequencingMethod ?o .
}
")]
    (rdf/tx tdb
      (->> (q tdb)
           (mapv rdf/->kw)
           count)))

  :cg/SequencingMethod

  [:rdf/type :rdfs/label :dc/source :dc/description :cg/demonstrates :cg/method :cg/statisticalSignificanceType :cg/statisticalSignificanceValueType :cg/caseCohort :cg/controlCohort :cg/statisticalSignificanceValue :cg/lowerConfidenceLimit :cg/upperConfidenceLimit :cg/pValue]
  )


;; Classes in ClinGen namespace

;; I think i'm overcomplicating things, let's just have one schema

[
 :cg/GeneticConditionMechanismProposition
 :cg/GeneDosageReport ;; exclude -- or refactor to report
 :cg/EvidenceStrengthAssertion 
 :cg/Contribution 
 :cg/EvidenceLine     ;; add to base 
 :cg/Agent            ;; Change to Prov? ;; added to base
 :cg/CanonicalVariant ;; Refactor around CatVRS?
 :cg/VariantPathogenicityProposition
 :cg/AssertionAnnotation 
 :cg/GeneValidityProposition
 :cg/Observation ;; Maybe want to use just Observation, FHIR style? Let's do that
 :cg/Proband
 :cg/FunctionalAlteration
 :cg/Affiliation ;; Can keep, but 
 :cg/Finding
 :cg/FamilyCosegregation
 :cg/Family
 :cg/Cohort
 :cg/UnscoreableEvidence ;; I don't like this, but may need better reason to change.
 :cg/VariantFunctionalImpactEvidence
 ]

;; Other classes that need to be represented in GraphQL
;; CanonicalLocation should be figured out
[[:dc/AgentClass "Agent Class"] ; don't know where this is used
 [:dc/BibliographicResource "Bibliographic Resource"] ; need
 [:ga4gh/CanonicalLocation nil] ; need, but need to think about
 [:ga4gh/SequenceLocation nil] ; need -- incorporate from ga4gh schema
 [:ga4gh/VariationDescriptor nil] ; obsolete, but need for now
 [:oboinowl/Subset nil] ; no
 [:owl/AllDifferent "AllDifferent"]
 [:owl/AllDisjointClasses "AllDisjointClasses"]
 [:owl/AnnotationProperty "AnnotationProperty"]
 [:owl/AsymmetricProperty "AsymmetricProperty"]
 [:owl/Axiom "Axiom"]
 [:owl/Class "Class"]
 [:owl/DatatypeProperty "DatatypeProperty"]
 [:owl/FunctionalProperty "FunctionalProperty"]
 [:owl/InverseFunctionalProperty "InverseFunctionalProperty"]
 [:owl/IrreflexiveProperty "IrreflexiveProperty"]
 [:owl/NamedIndividual "NamedIndividual"]
 [:owl/ObjectProperty "ObjectProperty"]
 [:owl/Ontology "Ontology"]
 [:owl/OntologyProperty "OntologyProperty"]
 [:owl/Restriction "Restriction"]
 [:owl/SymmetricProperty "SymmetricProperty"]
 [:owl/TransitiveProperty "TransitiveProperty"]
 [:rdf/List "List"]
 [:rdf/Property "Property"] ;; May need? 
 [:rdfs/Class "Class"] ;; need 
 [:rdfs/Datatype "Datatype"]

 ;; Found error in GV transform that left the last couple SEPIO types in there
 [:so/Gene "gene"]
 [:so/GeneWithProteinProduct "protein_coding_gene"]
 [:so/SequenceFeature "sequence_feature"] ;; This is the class to use for GraphQL
 [:void/Dataset nil]] ;; May be useful to use this one

(str (rdf/resource :sepio/VariantEvidenceItem))


(comment
  (let [model (-> "/Users/tristan/code/genegraph-data-model/resources/base.edn"
           slurp
           edn/read-string)]
    (set/difference
     (->> model
          :classes
          vals
          (mapcat :properties)
          set)
     (->> model
          :properties
          keys
          set)))
  )

(comment
  (let [t2
        [{:component :search-dialog}
         {:component :search-dialog}
         {:component :search-result}
         {:component :search-result}
         {:component :assertion,
          :iri "https://identifiers.org/clinvar.submission:SCV000080221"}
         {:component :search-result}
         {:component :assertion,
          :iri "https://identifiers.org/clinvar.submission:SCV000266614"}
         {:component :search-result}
         {:component :assertion,
          :iri "https://identifiers.org/clinvar.submission:SCV000266614"}
         {:component :search-result}
         {:component :assertion,
          :iri "https://identifiers.org/clinvar.submission:SCV000266635"}
         {:component :search-result}
         {:component :assertion,
          :iri "https://identifiers.org/clinvar.submission:SCV000081347"}
         {:component :search-result}
         {:component :assertion,
          :iri "https://identifiers.org/clinvar.submission:SCV000081347"}
         {:component :search-result}
         {:component :assertion,
          :iri "https://identifiers.org/clinvar.submission:SCV000080221"}]]
    (take-while #(not= % {:component :search-result}) t2))

  )

;; Experimentation with importing new gene validity
;; 
(comment

  (defn import-gv-curation [e]
    (p/process (get-in api-test-app [:processors :import-gv-curations])
               (assoc e ::event/completion-promise (promise))))
  (time
   (def revisions
     (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene-validity-sepio-2025-05-30.edn.gz"]
       (->> (event-store/event-seq r)
            #_(take 100)
            (mapv #(-> %
                       (assoc ::event/skip-local-effects true)
                       event/deserialize
                       cgv/main-record-iri))
            frequencies))))
  (->> revisions
       (sort-by val)
       reverse
       (take 20))
  
  ;; store all records in import set
  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene-validity-sepio-2025-05-30.edn.gz"]
    (->> (event-store/event-seq r)
         (run! import-gv-curation)))

  ;; delete all records in import set
  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene-validity-sepio-2025-05-30.edn.gz"]
    (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
          object-db @(get-in api-test-app [:storage :object-db :instance])
          hybrid-db {:tdb tdb :object-db object-db}]
      (rdf/tx tdb
        (->> (event-store/event-seq r)
             (map event/deserialize)
             (run! #(do (storage/delete tdb (cgv/assertion-iri %))
                        (storage/delete tdb (cgv/main-record-iri %))
                        (storage/delete object-db [:models (cgv/assertion-iri %)])
                        (storage/delete object-db [:models (cgv/main-record-iri %)])))))))

  (+ 1 1)

  (def example-set
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene-validity-sepio-2025-05-30.edn.gz"]
      (->> (event-store/event-seq r)
           (filterv #(= (-> % event/deserialize cgv/main-record-iri)
                        "http://dataexchange.clinicalgenome.org/gci/1bb8bc84-fe02-4a05-92a0-c0aacf897b6e")))))

  (count example-set)

  ;; import all records in example set. guard against race condition with sleep
  (run! #(do
           (import-gv-curation %)
           (println "import 1")
           (Thread/sleep 1000))
        example-set)

  ;; import all, screw race conditions
  (run! import-gv-curation example-set)

  ;; delete all records in example set
  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        hybrid-db {:tdb tdb :object-db object-db}
        main-record (-> example-set first event/deserialize cgv/main-record-iri)]
    (rdf/tx tdb
      (->> example-set
           (map #(-> %
                     event/deserialize
                     cgv/assertion-iri))
           (run! #(do (storage/delete tdb %)
                      (storage/delete object-db [:models %]))))
      (storage/delete tdb main-record)
      (storage/delete object-db [:models main-record])))

  ;; test to see if all historic records are included
  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        hybrid-db {:tdb tdb :object-db object-db}]
    (rdf/tx tdb
      (->> example-set
           (mapv #(-> %
                      event/deserialize
                      cgv/assertion-iri
                      (rdf/resource tdb)
                      (rdf/ld1-> [:rdf/type]))))))

  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        hybrid-db {:tdb tdb :object-db object-db}]
    (rdf/tx tdb
      (->> example-set
           (map #(-> %
                     event/deserialize
                     cgv/assertion-iri))
           (map #(storage/read tdb %))
           first
           rdf/pp-model)))




  
  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/abcd1-events2.edn.gz"]
    (->> (event-store/event-seq r)
         (take 1)
         #_(map #(-> %
                     event/deserialize
                     cgv/replace-hgnc-with-ncbi-gene-fn
                     ::event/data))
         (map #(assoc % ::event/skip-local-effects true))
         
         ))

  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gv-sepio-2025-07-02.edn.gz"]
    (->> (event-store/event-seq r)
         (filter #(re-find #"founder" (::event/value %)))
         (take 1)
         (map event/deserialize)
         (run! #(rdf/pp-model (::event/data %)))))

  ;; Q2 Reporting
  
  (def q2
    (let
        [end-epoch-milli   (-> (LocalDateTime/of 2025 7 1 0 0) (.toInstant ZoneOffset/UTC) .toEpochMilli)
         start-epoch-milli (-> (LocalDateTime/of 2025 4 1 0 0) (.toInstant ZoneOffset/UTC) .toEpochMilli)]
        (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gv-sepio-2025-07-02.edn.gz"]
          (->> (event-store/event-seq r)
               #_(take 1)
               (map event/deserialize)
               (filter #(and (< start-epoch-milli (::event/timestamp %))
                             (< (::event/timestamp %) end-epoch-milli)))
               (into [])
               #_(map #(-> %
                           event/deserialize
                           cgv/replace-hgnc-with-ncbi-gene-fn
                           ::event/data))
               #_count
               #_(map #(assoc % ::event/skip-local-effects true))
             
               ))))
  (def atp8a2
    (let
        [end-epoch-milli   (-> (LocalDateTime/of 2025 7 1 0 0) (.toInstant ZoneOffset/UTC) .toEpochMilli)
         start-epoch-milli (-> (LocalDateTime/of 2025 4 1 0 0) (.toInstant ZoneOffset/UTC) .toEpochMilli)]
      (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gv-sepio-2025-07-02.edn.gz"]
        (->> (event-store/event-seq r)
             #_(take 1)
             (filter #(re-find #"e6919cb8-703a-4f80-932b-2238f7bc08d6" (::event/value %)))
             (map event/deserialize)
             #_(filter #(and (< start-epoch-milli (::event/timestamp %))
                             (< (::event/timestamp %) end-epoch-milli)))
             (into [])
             #_(map #(-> %
                         event/deserialize
                         cgv/replace-hgnc-with-ncbi-gene-fn
                         ::event/data))
             #_count
             #_(map #(assoc % ::event/skip-local-effects true))
             
             ))))

  (-> atp8a2 first ::event/timestamp Instant/ofEpochMilli)



  (def dgke
    (let
        [end-epoch-milli   (-> (LocalDateTime/of 2025 7 1 0 0) (.toInstant ZoneOffset/UTC) .toEpochMilli)
         start-epoch-milli (-> (LocalDateTime/of 2025 4 1 0 0) (.toInstant ZoneOffset/UTC) .toEpochMilli)]
      (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gv-sepio-2025-07-02.edn.gz"]
        (->> (event-store/event-seq r)
             #_(take 1)
             (filter #(re-find #"99d5772a-c32d-4f71-a67c-8820d13e703c" (::event/value %)))
             (map event/deserialize)
             #_(filter #(and (< start-epoch-milli (::event/timestamp %))
                             (< (::event/timestamp %) end-epoch-milli)))
             (into [])
             #_(map #(-> %
                         event/deserialize
                         cgv/replace-hgnc-with-ncbi-gene-fn
                         ::event/data))
             #_count
             #_(map #(assoc % ::event/skip-local-effects true))
             
             ))))

  #_(->> dgke 
       (mapv #(-> % ::event/timestamp Instant/ofEpochMilli str)))

  (-> dgke last ::event/data rdf/pp-model)

  (defn add-affilation [c]
    (let [affiliation-query (rdf/create-query "
select ?a where { ?activity :cg/agent ?a ; :cg/role :cg/Approver }
")]
      (assoc c ::affiliation (-> c ::event/data affiliation-query first str))))

  (defn add-secondary-approver [c]
    (let [secondary-affiliation-query (rdf/create-query "
select ?a where { ?activity :cg/agent ?a ; :cg/role :cg/SecondaryContributor }
")]
      (assoc c ::secondary-approver (-> c ::event/data secondary-affiliation-query first str))))

  (defn add-gene [c]
    (let [affiliation-query (rdf/create-query "
select ?a where { ?prop :cg/gene ?a }
")]
      (assoc c ::gene (-> c ::event/data affiliation-query first str))))
  
  (defn curation-reasons-set [c]
    (let [curation-reasons-query (rdf/create-query "
select ?x where { ?a :cg/curationReasons ?x }
")]
      (set (map rdf/->kw (-> c ::event/data curation-reasons-query)))))
  
  (defn is-recuration [c]
    (let [recuration-reasons
          #{:cg/RecurationCommunityRequest
            :cg/RecurationDiscrepancyResolution
            :cg/RecurationErrorAffectingScoreorClassification
            :cg/RecurationTiming
            :cg/RecurationNewEvidence}]
      (seq
       (set/intersection
        (::curation-reasons c)
        recuration-reasons))))

  (defn is-new-curation [c]
    (let [recuration-reasons
          #{:cg/NewCuration}]
      (seq
       (set/intersection
        (::curation-reasons c)
        recuration-reasons))))

  (defn recuration-counts [events]
    (reduce (fn [a e]
              (cond
                (::new-curation e) (update a ::new-curations inc)
                (::recuration e) (update a ::recurations inc)
                :default a))
            {::recurations 0
             ::new-curations 0}
            events))

  (defn iri->label [iri]
    (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
          object-db @(get-in api-test-app [:storage :object-db :instance])
          hybrid-db {:tdb tdb :object-db object-db}]
      (rdf/tx tdb
        (-> (rdf/resource iri tdb)
            (rdf/ld1-> [:rdfs/label])))))

  (defn recurations-by-affiliation [events]
    (-> (->> events
             (map #(assoc % ::curation-reasons (curation-reasons-set %)))
             (map #(assoc % ::new-curation (is-new-curation %)
                          ::recuration (is-recuration %)))
             (filter #(or (::new-curation %) (::recuration %)))
             (map add-affilation)
             (map add-secondary-approver)
             (map add-gene)
             (map #(select-keys % [::affiliation ::new-curation ::recuration ::gene]))
             (group-by ::affiliation))
        #_(update-vals recuration-counts)
        (update-keys iri->label)))

  (defn recurations-by-secondary-affiliation [events]
    (-> (->> events
             (map #(assoc % ::curation-reasons (curation-reasons-set %)))
             (map #(assoc % ::new-curation (is-new-curation %)
                          ::recuration (is-recuration %)))
             (filter #(or (::new-curation %) (::recuration %)))
             (map add-affilation)
             (map add-secondary-approver)
             (filter ::secondary-approver)
             (map add-gene)
             (map #(select-keys % [::secondary-approver ::new-curation ::recuration ::gene]))
             (group-by ::secondary-approver))
        (update-vals recuration-counts)
        (update-keys iri->label)))

  (tap> (recurations-by-secondary-affiliation q2))
  (tap> (recurations-by-secondary-affiliation dgke))
  (tap> (recurations-by-affiliation q2))

  (->> dgke
       (mapv add-secondary-approver)
       (mapv ::secondary-approver))
  
  (with-open [w (io/writer "/Users/tristan/Desktop/gcep-secondary-report-q2.csv")]
    (csv/write-csv
     w
     (->> (recurations-by-secondary-affiliation q2)
          (map (fn [[k v]] [k (::new-curations v) (::recurations v)]))
          (cons ["GCEP" "New Curations" "Recurations"])))) 

"https://genegraph.clinicalgenome.org/r/agent/10081"

  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        hybrid-db {:tdb tdb :object-db object-db}]
    (rdf/tx tdb
      (-> (rdf/resource "https://genegraph.clinicalgenome.org/r/agent/10081" tdb)
          (rdf/ld1-> [:rdfs/label]))))
  
  (->> dgke
       (map #(assoc % ::curation-reasons (curation-reasons-set %)))
       (map #(assoc % ::new-curation (is-new-curation %)
                    ::recuration (is-recuration %)))
       (filter #(or (::new-curation %) (::recuration %)))
       (map add-affilation)
       (map #(select-keys % [::affiliation ::new-curation ::recuration]))
       (group-by ::affiliation))


  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        hybrid-db {:tdb tdb :object-db object-db}]
    (rdf/tx tdb
      #_(->> (storage/read object-db [:models "http://dataexchange.clinicalgenome.org/gci/55ca8d81-f718-428e-ab59-75f7a9182d08v1.0"])
             type)
      (-> (storage/read tdb "http://dataexchange.clinicalgenome.org/gci/55ca8d81-f718-428e-ab59-75f7a9182d08")
          (cgv/construct-minimized-assertion-query {:newAssertion (rdf/resource "http://dataexchange.clinicalgenome.org/gci/55ca8d81-f718-428e-ab59-75f7a9182d08v2.0")})
          rdf/pp-model)))
  
  )


(comment

  ;; working out Erin's queries

  "
Hi Tristan – our requests for a recuration spreadsheet (summarizing our 5/19 gene curation small call).
 
This information is requested for discussion on the June 11 gene curation large working group call.  Ideally, we would like this information by June 2 so the small group can begin preparing a presentation with the data.
 
Group of genes to focus on: Genes with a classification of LIMITED that are >3 years past the classification date (~460 per our previous discussion).
 
Tab 1 of the spreadsheet: Eligible Limiteds that HAVE undergone recuration.  The columns would include:
Gene
Disease
MOI
GCEP
Date of 1st Publication (there will only be one of these)
A repeating set of columns for every available classification (1st, 2nd, 3rd, etc.):
Classification
Date
Total points
Genetic points
Experimental points
 
Tab 2 of the spreadsheet: Eligible Limiteds that HAVE NOT undergone recuration.  The columns would include:
Gene
Disease
MOI
GCEP
Date of 1st Publication (there will only be one of these)
A single set of columns since there is theoretically only one curation available:
Classification
Date
Total points
Genetic points
Experimental points
"
  (def main-id-query
    (rdf/create-query "
select ?x where {
?assertion a :cg/EvidenceStrengthAssertion ;
 :cg/subject / a :cg/GeneValidityProposition ;
 :dc/isVersionOf ?x .
}
"))
  (def curation-versions
    (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
          object-db @(get-in api-test-app [:storage :object-db :instance])
          hybrid-db {:tdb tdb :object-db object-db}
          main-id-query
          (rdf/create-query "
select ?assertion where {
?assertion a :cg/EvidenceStrengthAssertion ;
 :cg/subject ?prop ;
 :dc/isVersionOf ?x .
?prop a :cg/GeneValidityProposition .
}
")
          approval-query (rdf/create-query "
select ?contrib where {
?a :cg/contributions ?contrib .
?contrib :cg/role :cg/Approver .
}
")
          experimental-evidence-query (rdf/create-query "
select ?el where {
?el :cg/specifiedBy :cg/GeneValidityOverallExperimentalEvidenceCriteria .
}
")
          genetic-evidence-query (rdf/create-query "
select ?el where {
?el :cg/specifiedBy :cg/GeneValidityOverallGeneticEvidenceCriteria .
}
")
          pubs-query (rdf/create-query "
select ?pub where {
?pub a :dc/BibliographicResource .
}
")]
      (rdf/tx tdb
        (->> (main-id-query tdb)
             (mapv (fn [a]
                     (let [m (storage/read object-db [:models (str a)])]
                       {:gene (rdf/ld1-> a [:cg/subject :cg/gene :skos/prefLabel])
                        :disease (rdf/ld1-> a [:cg/subject :cg/disease :rdfs/label])
                        :moi (rdf/ld1-> a [:cg/subject :cg/modeOfInheritance :rdfs/label])
                        :gcep (some-> (approval-query tdb {:a a})
                                      first
                                      (rdf/ld1-> [:cg/agent :rdfs/label]))
                        :classification (rdf/->kw (rdf/ld1-> a [:cg/evidenceStrength]))
                        :version (rdf/ld1-> a [:cg/version])
                        :date (or (rdf/ld1-> a [:dc/dateAccepted])
                                  (-> (approval-query tdb {:a a})
                                      first
                                      (rdf/ld1-> [:cg/date])))
                        :total-points (rdf/ld1-> a [:cg/strengthScore])
                        :experimental-points (some-> (experimental-evidence-query m)
                                                     first
                                                     (rdf/ld1-> [:cg/strengthScore]))
                        :genetic-points (some-> (genetic-evidence-query m)
                                                first
                                                (rdf/ld1-> [:cg/strengthScore]))
                        :earliest-publication (->> (pubs-query m)
                                                   (mapv #(rdf/ld1-> % [:dc/date]))
                                                   sort
                                                   first)
                        :iri (rdf/curie a)
                        :version-of (rdf/curie (rdf/ld1-> a [:dc/isVersionOf]))})))))))

  (defn latest-version [curations]
    (->> curations (sort-by :version) reverse first))
  
  (defn latest-major-versions [curations]
    (->> curations
         (group-by #(subs (:version %) 0 1))
         vals
         (mapv latest-version)))

  (defn recuration-column [versions]
    (let [latest (latest-version versions)]
      (concat
       [(:gene latest)
        (:disease latest)
        (:moi latest)
        (:earliest-publication latest)]
       (mapcat
        (fn [v]
          [(name (:classification v))
           (subs (:date v) 0 10)
           (:total-points v)
           (:genetic-points v)
           (:experimental-points v)])
        versions))))

  
  (with-open [w (io/writer "/Users/tristan/Desktop/recurated.csv")]
    (->> curation-versions
         (group-by :version-of)
         (filter #(< 1 (count (val %))))
         (filter (fn [[_ curations]]
                   (some #(not= "1" (subs (:version %) 0 1)) curations)))
         vals
         (map latest-major-versions)
         (map recuration-column)
         (csv/write-csv w)))

  (with-open [w (io/writer "/Users/tristan/Desktop/not-recurated.csv")]
    (->> curation-versions
         (group-by :version-of)
         #_(filter #(< 1 (count (val %))))
         (remove (fn [[_ curations]]
                   (some #(not= "1" (subs (:version %) 0 1)) curations)))
         vals
         (map latest-major-versions)
         (map recuration-column)
         (csv/write-csv w)))


  (tap> curation-versions)

  (+ 1 1)

  )

;; estimate how many curations we can flag
(comment
  (event-store/with-event-reader [r (str root-data-dir "ggapi-clinvar-curation-stage-1-2025-06-02.edn.gz")]
    (->> (event-store/event-seq r)
         (run! #(p/publish (get-in api-test-app [:topics :clinvar-curation]) %))))

    (event-store/with-event-reader [r (str root-data-dir "ggapi-clinvar-curation-stage-1-2025-06-02.edn.gz")]
    (->> (event-store/event-seq r)
         (mapv event/deserialize)
         (remove #(get-in % [::event/data :classification]))
         first
         tap>))

    (event-store/with-event-reader [r (str root-data-dir "ggapi-clinvar-curation-stage-1-2025-06-02.edn.gz")]
      (->> (event-store/event-seq r)
           (mapv event/deserialize)
           (mapv #(get-in % [::event/data :classification]))
           set
           tap>))

    (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
          object-db @(get-in api-test-app [:storage :object-db :instance])
          hybrid-db {:tdb tdb :object-db object-db}
          q (rdf/create-query "select ?x where { ?x a :cg/AssertionAnnotation }")]
      (rdf/tx tdb
        (->> (q tdb)
             (take 5)
             (mapv #(rdf/ld1-> % [:dc/description]))
             tap>)))
  )

;; testing version relationships
(comment
  
  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        hybrid-db {:tdb tdb :object-db object-db}
        q (rdf/create-query "
select ?x where {
 ?x a :cg/EvidenceStrengthAssertion ;
 :prov/wasInvalidatedBy ?otherx
}
")]
    (rdf/tx tdb
      (->> (q tdb)
           count)))
 )

;; troubleshooting issues with assertions on features
(comment
  "https://identifiers.org/ncbigene:215"

    (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
          object-db @(get-in api-test-app [:storage :object-db :instance])
          hybrid-db {:tdb tdb :object-db object-db}
          f (rdf/resource "https://identifiers.org/ncbigene:215")
          test-query (rdf/create-query "
select ?x where {
?prop :cg/gene | :cg/feature | :cg/subject ?feature .
?prop :rdf/type ?proposition_type .
?x :cg/subject ?prop .
}")]
    (rdf/tx tdb
      (->> (test-query tdb {:feature f})
           count))))


(comment
  (rdf/resource "GENCC:000110")
  (rdf/resource)"http://www.orpha.net/ORDO/Orphanet_"
  (rdf/resource "http://www.orpha.net/ORDO/Orphanet_43")
  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        hybrid-db {:tdb tdb :object-db object-db}
        abcd1 (rdf/resource "https://identifiers.org/ncbigene:215")
        orphanet (rdf/resource "GENCC:000110")
        test-query (rdf/create-query "
select ?disease where {
?prop :cg/gene | :cg/feature | :cg/subject ?feature .
?prop :rdf/type ?proposition_type ;
:cg/disease ?disease .
?x :cg/subject ?prop .
}")]
    (rdf/tx tdb
      (test-query tdb {:feature abcd1}))
    #_(rdf/tx tdb
        (-> (rdf/resource "OMIM:300100" tdb)
            (rdf/ld1-> [[:skos/exactMatch :<]])
            (rdf/ld-> [:skos/exactMatch]))))
  )

(comment

  (do (defn kw->iri-id [k]
        (-> k str (string/replace #":" "") (string/replace #"/" "_")))
      (defn iri-id->kw [id]
        (apply keyword (string/split id #"_")))
      (iri-id->kw (kw->iri-id :cg/Agent)))

  
  )





(comment
  (->> schema-in-progress
       :classes
       (remove :dc/description)
       keys
       (map name)
       (drop 5)
       (into []))

  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        hybrid-db {:tdb tdb :object-db object-db}]
    (rdf/tx tdb
      (->> schema-in-progress
           :properties
           keys
           (filter #(= "dc" (namespace %)))
           #_(take 5)
           (map (fn [t] [t {:description (-> t (rdf/resource tdb) (rdf/ld1-> [:rdfs/comment]))}]))
           (into []))))
  )



(comment


;;   Tristan, I thought a bit more about our “analysis over time” conversation on the Gene Curation Small Group call. 
 
;; It looks like most of our curations have “first reported” dates in the 2000s.  There are definitely some earlier than this, but I would argue that these will throw off our analyses because we aren’t really expecting tons of new data to emerge prior to that time just due to limitations in testing capabilities.
 
;; So, if we focused our analyses on only those curations with “first reported” years 2000-2025, I’m envisioning a spreadsheet with  the following:
;; Standard identity fields (gene, disease, MOI)
;; Year of first report (expect 2000 and later)
;; Then, a column for each year 2000-2025 with the number of points documented in the curation in that year.  I’m thinking just total points to make it easier to deal with.
;; Original Total Points
;; Original Classification
;; Original Classification Date
;; Total points, classifications, and dates on any subsequent recurations
 
;; I realize this will make for a sheet with a bunch of 0s (since obviously not every curation will have a start year in 2000), but I think this might be a good way to normalize the information?  So like if a curation was first reported in 2022, it would have 0s in all columns up to 2022, then it would have say 3 points from papers published in 2022, 1 point from papers published in 2023, 3 points from papers published in 2024, etc.
 
;; Does that seem feasible?  We can also discuss further if that doesn’t make sense 😊

;; -Erin


  (comment
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gv-sepio-2025-07-02.edn.gz"]
      (->> (event-store/event-seq r)
           #_(filter #(re-find #"founder" (::event/value %)))
           (take 1)
           (map event/deserialize)
           (run! #(rdf/pp-model (::event/data %)))))

    )

  (def example
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gv-sepio-2025-07-02.edn.gz"]
      (->> (event-store/event-seq r)
           #_(filter #(re-find #"founder" (::event/value %)))
           (take 1)
           (map event/deserialize)
           first))))

(comment
  (do

    (defn unpublish-event? [event]
      (let [q (rdf/create-query "
select ?x where {
 ?x :cg/role ?role
 filter(?role IN ( :cg/Unpublisher , :cg/UnpublisherRole ))
}")]
        (-> event ::event/data q seq)))

    (defn publish-event? [event]
      (let [q (rdf/create-query "
select ?x where { ?x :cg/role :cg/Publisher }")]
        (-> event ::event/data q seq)))

  
    (defn points-per-paper [paper]
      (let [q (rdf/create-query "
select ?ev where 
{ ?el :cg/evidence ?ev .
  ?ev :dc/source ?p .
}")]
        (q paper {:p paper})))

    (defn add-evidence-items [{:keys [paper] :as m}]
      (let [q (rdf/create-query "
select ?ev where 
{ ?ev :dc/source ?p .}")]
        (assoc m :evidence-items (q paper {:p paper}))))

    (defn add-evidence-lines [{:keys [paper] :as m}]
      (let [q (rdf/create-query "
select ?el where 
{ ?ev :dc/source ?p .
  ?el :cg/evidence ?ev .}")]
        (assoc m :evidence-lines (q paper {:p paper}))))

    (defn add-date [{:keys [paper] :as m}]
      (assoc m :date (rdf/ld1-> paper [:dc/date])))

    (defn add-scores [{:keys [evidence-lines] :as m}]
      (let [scores (filter number?
                           (mapv #(rdf/ld1-> % [:cg/strengthScore])
                                 evidence-lines))]
        (assoc m
               :scores scores
               :total-scores (reduce + scores))))

    (defn add-curation-info [e]
      (let [q (rdf/create-query "
select ?x where { ?x a :cg/EvidenceStrengthAssertion }")
            gcep-q (rdf/create-query "
select ?gcep where { ?act :cg/agent ?gcep ; :cg/role :cg/Approver}")
            approval-q (rdf/create-query "
select ?act where { ?act :cg/agent ?gcep ; :cg/role :cg/Approver}")
            assertion (first (q (::event/data e)))
            prop (rdf/ld1-> assertion [:cg/subject])
            tdb @(get-in api-test-app [:storage :api-tdb :instance])]
        (rdf/tx tdb
          (assoc e
                 ::version (rdf/ld1-> assertion [:cg/version])
                 ::gene (rdf/ld1-> (rdf/resource (str (rdf/ld1-> prop [:cg/gene])) tdb)
                                   [[:owl/sameAs :<] :skos/prefLabel])
                 ::disease (rdf/ld1-> (rdf/resource (str (rdf/ld1-> prop [:cg/disease])) tdb)
                                      [:rdfs/label])
                 ::moi (rdf/ld1-> (rdf/resource (str (rdf/ld1-> prop [:cg/modeOfInheritance])) tdb)
                                  [:rdfs/label])
                 ::gcep (rdf/ld1-> (rdf/resource (str (first (gcep-q (::event/data e)))) tdb)
                                   [:rdfs/label])
                 ::approval-date (rdf/ld1-> (first (approval-q (::event/data e))) [:cg/date])
                 ::classification (rdf/->kw (rdf/ld1-> assertion [:cg/evidenceStrength]))
                 ::record-id (-> assertion (rdf/ld1-> [:dc/isVersionOf]) rdf/->kw)
                 ::total-points (rdf/ld1-> assertion [:cg/strengthScore])))))
  
    (defn add-papers [e]
      (let [papers-query (rdf/create-query "
select ?p where { ?p a :dc/BibliographicResource }")]
        (assoc e
               ::papers
               (->> (map (fn [p] {:paper p})
                         (papers-query (::event/data e)))
                    (map add-evidence-items)
                    (map add-evidence-lines)
                    (map add-scores)
                    (map add-date)
                    (mapv #(-> (update-in % [:paper] str)
                               (select-keys [:paper :total-scores :date])))))))





    #_(defn compose-aggregate [m]
        (assoc (select-keys m [:date :total-scores])))

    #_(-> example
          add-curation-info
          add-papers
          (select-keys [::version
                        ::gene
                        ::disease
                        ::moi
                        ::gcep
                        ::approval-date
                        ::classification
                        ::papers])
          tap>)
  
    #_(-> example
          add-papers
          ::papers
          tap>))

  )


#_(let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
      q (rdf/create-query "
select ?x where { ?x a :cg/Affiliation } limit 5")]
  (rdf/tx tdb
    (q tdb)))

  ;; 0. https://genegraph.clinicalgenome.org/r/agent/10138
  ;; 1. https://genegraph.clinicalgenome.org/r/agent/50160
  ;; 2. https://genegraph.clinicalgenome.org/r/agent/10120
  ;; 3. https://genegraph.clinicalgenome.org/r/agent/40022
  ;; 4. https://genegraph.clinicalgenome.org/r/agent/10069
  ;;    https://genegraph.clinicalgenome.org/r/agent/10021

;; check for how many have a single variant -- search for founder variants
;; scored more than zero
;; note segregrations present 


(defn add-earliest-paper-date [e]
  (assoc e
         ::earliest-paper-date
         (->> (::papers e)
              (map #(-> %
                        :date
                        (subs 0 4)
                        Integer/parseInt))
              sort
              first)))

(defn add-segregation-score [event]
  (assoc
   event
   ::segregation-score
   (let [q (rdf/create-query "
select ?el where 
{ ?el :cg/specifiedBy :cg/GeneValiditySegregationEvidencCriteria } ")]
     (if-let [seg (-> event ::event/data q first)]
       (rdf/ld1-> seg [:cg/strengthScore])
       0))))

(defn most-recent-event [event-seq]
  (->> event-seq
       (sort-by ::event/timestamp)
       reverse
       first))

(defn add-points-by-year
  "Sequence of points per paper per year"
  [event]
  (let [papers-by-year (group-by #(Integer/parseInt (subs (:date %) 0 4))
                                 (::papers event))]
    (assoc event
           ::points-by-year
           (mapv (fn [year]
                   (reduce +
                           (map :total-scores
                                (get papers-by-year year))))
                 (range 2000 2026)))))

(def header-row
  ["last version"
   "gene"
   "disease"
   "moi"
   "gcep"
   "approval date"
   "classification"
   "earliest publication"
   "total points"
   "segregation points"
   "2000"
   "2001"
   "2002"
   "2003"
   "2004"
   "2005"
   "2006"
   "2007"
   "2008"
   "2009"
   "2010"
   "2011"
   "2012"
   "2013"
   "2014"
   "2015"
   "2016"
   "2017"
   "2018"
   "2019"
   "2020"
   "2021"
   "2022"
   "2023"
   "2024"
   "2025"
   "version infos"])

(defn event->column [e]
  (concat [(::version e)
           (::gene e)
           (::disease e)
           (::moi e)
           (::gcep e)
           (if-let [d (::approval-date e)]
             (subs d 0 10)
             "no approval date")
           (some-> e ::classification name)
           (::earliest-paper-date e)
           (::total-points e)
           (::segregation-score e)]
          (::points-by-year e)))

(defn event-timestamp->iso-date [event]
  (-> event ::event/timestamp Instant/ofEpochMilli str (subs 0 10)))

(defn events->summary-column [events]
  (mapcat
   (fn [e]
     [(::version e)
      (event-timestamp->iso-date e)
      (name (::classification e))
      (::total-points e)])
   (sort-by ::event/timestamp events)))

(defn add-score-difference [event]
  (assoc event
         ::score-difference
         (- (::total-points event)
            (reduce + (map :total-scores (::papers event))))))

#_(-> nonevent-example ::event/data rdf/pp-model)
(comment
  
"75516cff-17fd-47bd-8873-862b66741de2"

(def mgme1
  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gv-sepio-2025-07-02-fixed.edn.gz"]
    (->> (event-store/event-seq r)
         (filter #(re-find #"75516cff-17fd-47bd-8873-862b66741de2" (::event/value %)))
         (map event/deserialize)
         (filterv publish-event?))))

(-> mgme1
    first
    ::event/data
    rdf/pp-model
)

(event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gv-sepio-2025-07-02.edn.gz"]
  (->> (event-store/event-seq r)
       (filter #(re-find #"0204e276-fa45-4756-a380-eb494f5237f8" (::event/value)))
       (map event/deserialize)
       (filter publish-event?)
       (run! #(rdf/pp-model (::event/data %)))))
  
(def event-versions
  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gv-sepio-2025-07-02-fixed3.edn.gz"]
    (->> (event-store/event-seq r)
         #_(take 10)
         (map event/deserialize)
         (filter publish-event?)
         (map add-curation-info)
         (map add-papers)
         (map add-earliest-paper-date)
         (map add-points-by-year)
         (map add-score-difference)
         (map add-segregation-score)
         (mapv #(select-keys %
                             [::version
                              ::gene
                              ::disease
                              ::moi
                              ::gcep
                              ::approval-date
                              ::classification
                              ::papers
                              ::record-id
                              ::earliest-paper-date
                              ::points-by-year
                              ::total-points
                              ::segregation-score
                              ::score-difference
                              ::event/timestamp])))))



(->> event-versions
     (filterv #(< 0.2 (::score-difference %)))
     (remove #(< 0.1 (::segregation-score %)))
     (sort-by ::score-difference)
     reverse
     tap>)


(def big-problems
  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gv-sepio-2025-07-02-fixed3.edn.gz"]
    (->> (event-store/event-seq r)
         #_(take 10)
         (map event/deserialize)
         (filter publish-event?)
         (map add-curation-info)
         (map add-papers)
         (map add-earliest-paper-date)
         (map add-points-by-year)
         (map add-score-difference)
         (filterv #(< 5 (::score-difference %)))
         #_(mapv #(select-keys %
                               [::version
                                ::gene
                                ::disease
                                ::moi
                                ::gcep
                                ::approval-date
                                ::classification
                                ::papers
                                ::record-id
                                ::earliest-paper-date
                                ::points-by-year
                                ::total-points
                                ::score-difference
                                ::event/timestamp])))))


(count big-problems)
(-> big-problems
    second
    ::event/data
    rdf/pp-model)

(->> event-versions
     (filter #(< 1 (::score-difference %)))
     (take 1)
     tap>)

(->> event-versions
     (filter #(< 5 (::score-difference %)))
     #_(take 1)
     tap>)

(->> event-versions
     (filter #(= "MGME1" (::gene %)))
     (map ::score-difference))

(do

  (->> event-versions
       (filter #(= "MGME1" (::gene %)))
       (mapv add-score-difference)
       tap>))

  (with-open [w (io/writer "/Users/tristan/Desktop/gene-curation-points-by-year.csv")]
    (csv/write-csv
     w
     (->> event-versions
          (filter #(and (::earliest-paper-date %) (< 1999 (::earliest-paper-date %))))
          (group-by ::record-id)
          vals
          (map #(concat
                 (event->column (most-recent-event %))
                 (events->summary-column %)))
          (cons header-row))))
  
  (count event-versions)
  (->> event-versions
       #_(take 10)
       (map add-earliest-paper-date)
       (map ::earliest-paper-date)
       frequencies
       (sort-by key)
       tap>)

  (->> event-versions
       (filter #(= "2001" (::earliest-paper-date %)))
       (mapv add-points-by-year)
       (take 10)
       tap>)

  )

;; indexing genes
(comment

  (do (p/process
      (get-in api-test-app [:processors :import-base-file])
      {::event/data
       (assoc (first (filter #(= "https://www.genenames.org/"
                                 (:name %))
                             (-> "base.edn" io/resource slurp edn/read-string)))
              :source
              {:type :file
               :base "data/base/"
               :path "hgnc.json"})})
      (println "done! "))

  (+ 1 1 )

  (def api-test-app (p/init api-test-app-def))
  (p/start api-test-app)
  (p/stop api-test-app)
  (do
    (defn document-iri [doc]
      (-> doc (.getField "iri") .stringValue))
    
    (defn score-doc->m [score-doc stored-fields]
      {:iri (document-iri (.document stored-fields (.doc score-doc) #{"iri"}))
       :score (.score score-doc)})
    
    (defn lucene-search [{:keys [searcher-manager
                                 symbol-parser
                                 label-parser
                                 description-parser]}
                         {:keys [field query max-results]}]
      (let [parser (case field
                     :symbol symbol-parser
                     :label label-parser
                     :description description-parser)
            searcher (.acquire searcher-manager)]
        (->> (.search searcher
                      (.parse parser query)
                      (or max-results 100))
             .scoreDocs
             count
             #_(mapv #(score-doc->m % (.storedFields searcher))))))

    (let [lc @(get-in api-test-app [:storage :text-index :instance])]
      (lucene-search lc {:field :symbol :query "SMAD2"}))

    #_(let [lc @(get-in api-test-app [:storage :text-index :instance])]
      (-> lc
          :writer
          .hasUncommittedChanges))
 

    #_(let [lc @(get-in api-test-app [:storage :text-index :instance])]
      (-> lc
          :searcher-manager
          .acquire
          
          .getIndexReader
          .numDocs
          ))

    #_(let [lc @(get-in api-test-app [:storage :text-index :instance])
          searcher (.acquire (:searcher-manager lc))]
      (mapv #(.document (.storedFields searcher) (.doc %))
            (take 10
                  (.scoreDocs
                   (.search searcher (org.apache.lucene.search.MatchAllDocsQuery.) 100))))))

  
  (let [lc @(get-in api-test-app [:storage :text-index :instance])]
    (-> lc :writer .commit)
    (-> lc :searcher-manager .maybeRefresh))
  (let [lc @(get-in api-test-app [:storage :text-index :instance])]
      (-> lc
          :writer
          .hasUncommittedChanges))

  (storage/as-handle
   {:type :file
    :base "data/base/"
    :de "hgnc.json"})
  (-> {:type :file
       :base "data/base/"
       :path "GRCh38.gff.gz"}
      storage/as-handle
      .getAbsolutePath)
  )
