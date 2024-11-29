(ns genegraph.user
  (:require [genegraph.framework.protocol]
            [genegraph.framework.kafka :as kafka]
            [genegraph.framework.kafka.admin :as kafka-admin]
            [genegraph.framework.event :as event]
            [genegraph.framework.protocol :as p]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage.rdf.jsonld :as jsonld]
            [genegraph.framework.storage.rocksdb :as rocksdb]
            [genegraph.framework.event.store :as event-store]
            [genegraph.api :as api]
            [genegraph.api.protocol :as ap]
            [genegraph.api.dosage :as dosage]
            [genegraph.api.graphql.response-cache :as response-cache]
            [genegraph.api.graphql.schema.conflicts :as conflicts]
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
            [nextjournal.clerk :as clerk]
            [genegraph.api.assertion-annotation :as ac])
  (:import [ch.qos.logback.classic Logger Level]
           [org.slf4j LoggerFactory]
           [java.time Instant LocalDate]
           [java.io PushbackReader]))

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
  (clerk/build! {:paths ["notebooks/clinvar.clj"]
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
   :storage {:api-tdb (assoc api/api-tdb :load-snapshot false)
             :response-cache-db api/response-cache-db
             #_#_:sequence-feature-db api/sequence-feature-db
             :object-db (assoc api/object-db :load-snapshot false)}
   :processors {:fetch-base-file api/fetch-base-processor
                :import-base-file api/import-base-processor
                :import-gv-curations api/import-gv-curations
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

  (get-events-from-topic api/actionability-topic)
  (time (get-events-from-topic api/gene-validity-complete-topic))
  (get-events-from-topic api/gene-validity-raw-topic)
  (time (get-events-from-topic api/gene-validity-legacy-complete-topic))
  (time (get-events-from-topic api/dosage-topic))
  (+ 1 1)

  (time (get-events-from-topic api/gene-validity-sepio-topic))

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
       (filter #(= "http://www.ebi.ac.uk/efo"
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
             :file "clinvar.xml.gz"})}))
  
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
  
  (def errors
    (event-store/with-event-reader [r (str root-data-dir "gene_dosage_raw-2024-09-27.edn.gz")]
      (->> (event-store/event-seq r)
           #_(take 100)
           (map process-dosage)
           (filter ::error)
           (into []))))

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
