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
            [genegraph.api.base.clinvar :as clinvar]
            [genegraph.api.graphql.schema.sequence-annotation :as sa]
            [genegraph.api.lucene :as lucene]
            [genegraph.api.filter :as filters]
            [clojure.tools.namespace.repl :as repl]
            [genegraph.api.shared-data :as shared-data]
            [genegraph.api.sequence-index :as idx]
            [genegraph.api.gpm :as gpm]
            [genegraph.api.base.gene :as hgnc-gene])
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
              :error-message (:error-message data)
              :user-email (:user-email data))
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
             :type :simple-queue-topic}
            :gpm-person-events
            {:name :gpm-person-events
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
                :read-clinvar-curations read-clinvar-curations
                :import-gpm-people api/import-gpm-people}
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

;; Reload base data


(def base-order
  ["http://www.w3.org/2004/02/skos/core"
   "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
   "http://www.w3.org/2011/content#"
   "https://www.w3.org/2002/07/owl"
   "http://www.w3.org/2000/01/rdf-schema#"
   "http://purl.obolibrary.org/obo/so.owl"
   "http://purl.obolibrary.org/obo/mondo.owl"
   "http://purl.obolibrary.org/obo/hp.owl"
   "https://genegraph.app/resources"
   "https://www.genenames.org/"
   "https://ncbi.nlm.nih.gov/genomes/GCF_000001405.40_GRCh38.p14_genomic.gff"
   "https://ncbi.nlm.nih.gov/genomes/GCF_000001405.25_GRCh37.p13_genomic.gff"
   "https://affils.clinicalgenome.org/"
   "https://www.ncbi.nlm.nih.gov/clinvar/submitters"
   "http://dataexchange.clinicalgenome.org/gci-express"
   "https://thegencc.org/"
   "https://www.ncbi.nlm.nih.gov/clinvar/"])
#_"https://omim.org/genemap"



(comment

  ;; to update
  ;; gsutil cp -r gs://genegraph-base/ /Users/tristan/data/
  
  (->> "base.edn"
       io/resource
       slurp
       edn/read-string
       (filterv #(= "https://www.ncbi.nlm.nih.gov/clinvar/"
                    #_"https://genegraph.app/resources"
                    (:name %)))
       (mapv #(assoc % :source {:type :file
                                :base "/Users/tristan/data/genegraph-base/"
                                :path (:target %)}))
       (mapv (fn [x] {::event/data x}))
       (run! #(p/publish (get-in api-test-app [:topics :base-data]) %)))

  (->> "base.edn"
       io/resource
       slurp
       edn/read-string
       (filterv #(= "https://genegraph.app/resources" (:name %)))
       (mapv (fn [x] {:type :file
                      :base "/Users/tristan/data/genegraph-base/"
                      :path (:target x)})))
  (def genes-json 
    (-> "/Users/tristan/data/genegraph-base/hgnc.json"
        slurp
        (json/read-str :key-fn keyword)))

  (->> (filter :entrez_id (get-in genes-json [:response :docs]))
       count)
  
  (def base-event-map
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gg-base-2025-10-21.edn.gz"]
      (->> (event-store/event-seq r)
           (mapv event/deserialize)
           (reduce (fn [a e] (assoc a (get-in e [::event/data :name]) e)) {})
           #_(filterv #(= #_"https://ncbi.nlm.nih.gov/genomes/GCF_000001405.25_GRCh37.p13_genomic.gff"
                          #_"https://ncbi.nlm.nih.gov/genomes/GCF_000001405.40_GRCh38.p14_genomic.gff"
                          #_"http://purl.obolibrary.org/obo/so.owl"
                          #_"https://www.ncbi.nlm.nih.gov/clinvar/"
                          "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                          (get-in % [::event/data :name])))

           #_(mapv #(get-in % [::event/data :name]))
           #_(take 1)
           #_(into [])
           #_set
           #_(run! #(p/publish (get-in api-test-app [:topics :base-data]) %)))))
  

  (clojure.pprint/pprint base-event-map)
  
  (->> base-order
       (mapv base-event-map)
       (mapv event/deserialize)
       (run! #(p/publish (get-in api-test-app [:topics :base-data]) %))
       

       )

  (mapv #(-> % base-event-map event/deserialize ::event/data (dissoc :source))
        base-order)


  

  (tap> (get base-event-map "https://www.genenames.org/"))

  (p/publish (get-in api-test-app [:topics :base-data])
             (get base-event-map "https://www.genenames.org/"))

  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        q (rdf/create-query "
select ?x where
{ ?x a ?type . }
 limit 5")]
    (rdf/tx tdb
      (->> (q tdb {:type :owl/Class})
           count
           #_(mapv #(rdf/ld1-> % [:rdf/type])))))
  
  (def hgnc
    (with-open [r (-> (get base-event-map "https://www.genenames.org/")
                      event/deserialize
                      (get-in [::event/data :source])
                      storage/as-handle
                      io/reader)]
      (json/read r :key-fn keyword)))

  (tap> (hgnc-gene/genes-as-triple hgnc))

  (tap> hgnc)

  (-> base-event-map
      (update-vals event/deserialize)
      tap>)
  (p/publish (get-in api-test-app [:topics :base-data])
             (get base-event-map "https://www.ncbi.nlm.nih.gov/clinvar/"))

  (get base-event-map "https://www.ncbi.nlm.nih.gov/clinvar/")
  )

;; this bit is obsolete, delete after review
(comment
  (with-open [r (-> "base.edn" io/resource io/reader PushbackReader.)]
    (->> (edn/read r)
         (filter #(= "https://www.genenames.org/"
                     (:name %)))
         (mapv (fn [e] {::event/data
                        (assoc e
                               :source
                               {:type :file
                                :base "data/base/"
                                :path (:target e)})}))
         (run! #(p/publish (get-in api-test-app [:topics :base-data]) %))))

  ;; rename to https://genegraph.clinicalgenome.org/resources at some point
  (with-open [r (-> "base.edn" io/resource io/reader PushbackReader.)]
    (->> (edn/read r)
         (filter #(= "https://genegraph.app/resources"
                     (:name %)))
         (mapv (fn [e] {::event/data
                        (assoc e
                               :source
                               {:type :file
                                :base "data/base/"
                                :path (:target e)})}))
         (run! #(p/publish (get-in api-test-app [:topics :base-data]) %))))

  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        hybrid-db {:tdb tdb :object-db object-db}
        q (rdf/create-query "
select ?x where {
?x a :rdfs/Class .
}")]
    (rdf/tx tdb
      (->>(q tdb)
          count)))
  
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

  
  
  )

(def root-data-dir "/Users/tristan/data/genegraph-neo/")

;; reload clingen gene validity
(comment
  (time
   (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene-validity-sepio-2025-10-01.edn.gz"]
     (->> (event-store/event-seq r)
          #_(mapv ::event/key)
          #_(take 1)
          (run! #(p/publish (get-in api-test-app [:topics :gene-validity-sepio])
                            (assoc % ::event/completion-promise (promise)))))))
  )

;; reload gene dosage
(comment
  (time
   (event-store/with-event-reader [r (str root-data-dir
                                          "gene_dosage_raw-2025-10-21.edn.gz")]
     (->> (event-store/event-seq r)
          #_(mapv ::event/key)
          #_(take 1)
          (run! #(p/publish (get-in api-test-app [:topics :dosage])
                            (assoc % ::event/completion-promise (promise)))))))

  (+ 1 1)
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

  (time (get-events-from-topic api/variant-interpretation-topic))
  (time (get-events-from-topic api/gpm-general-events-topic))
  (time (get-events-from-topic api/gpm-person-events-topic))
  (time (get-events-from-topic api/gt-precuration-events-topic))
  (time (get-events-from-topic api/gene-validity-legacy-topic))
  (time (get-events-from-topic api/base-data-topic))
  
  
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


 (p/process
  (get-in api-test-app [:processors :import-base-file])
  {::event/data
   (assoc (first (filter #(= "https://affils.clinicalgenome.org/"
                             (:name %))
                         (-> "base.edn" io/resource slurp edn/read-string)))
          :source
          {:type :file
           :base "data/base"
           :path "affils.json"})})

 (with-open [r (-> {:type :file
                    :base "data/base/"
                    :path "affils.json"}
                   storage/->input-stream
                   io/reader)]
   (->> (json/read r :key-fn keyword)
        #_(map #(get-in % [:subgroups :gcep]))
        #_(remove nil?)
        (into [])))

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
    (def test-affils-list "https://affils-test.clinicalgenome.org/api/affiliations_list/")
    (def prod-affils-list "https://affils.clinicalgenome.org/api/affiliations_list/"))
  (-> (hc/get
       test-affils-list
       {:headers {"X-Api-Key" test-affils-api-key}
        :http-client http-client})
      :body
      json/read-str
      tap>)

  (-> (hc/get
       prod-affils-list
       {:headers {"X-Api-Key" prod-affils-api-key}
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


  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gv-sepio-2025-07-02-fixed3.edn.gz"]
    (->> (event-store/event-seq r)
         #_(filter #(re-find #"founder" (::event/value %)))
         (take 1)
         (map event/deserialize)
         (run! #(rdf/pp-model (::event/data %)))))


  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gv-sepio-2025-07-02-fixed3.edn.gz"]
    (->> (event-store/event-seq r)
         #_(filter #(re-find #"founder" (::event/value %)))
         (take 1)
         count))

  

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

  (def doc
   (-> (p/process
        (get-in api-test-app [:processors :import-base-file])
        {::event/data
         (assoc (first (filter #(= "https://www.genenames.org/"
                                   (:name %))
                               (-> "base.edn" io/resource slurp edn/read-string)))
                :source
                {:type :file
                 :base "data/base/"
                 :path "hgnc.json"})})
              :genegraph.api.base.gene/document))
  
  (tap> doc)

  (filter :entrez_id (get-in doc [:response :docs]))
  
  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        hybrid-db {:tdb tdb :object-db object-db}
        q (rdf/create-query "select ?x where { ?x a ?type }")]
    (rdf/tx tdb
      (rdf/pp-model (storage/read tdb "https://www.genenames.org/"))
      #_(->> (q tdb {:type :so/Gene })
             count)
      #_(-> (rdf/resource "https://identifiers.org/ncbigene:1" tdb)
            (rdf/ld-> [:rdf/type]))))




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

  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        hybrid-db {:tdb tdb :object-db object-db}
        q (rdf/create-query "select ?x where { ?x a ?type }")]
    (rdf/tx tdb
      #_(->> (q tdb {:type :so/Gene })
             count)
      (-> (rdf/resource "https://identifiers.org/ncbigene:1" tdb)
          (rdf/ld-> [:rdf/type]))))
  )



;; staging code for composing graphql queries

(def fragments
  {"SequenceFeature"
   {3 {:fragment "{
  assertions {
    curie
    iri
    evidenceStrength {
      curie
    }
    subject {
      __typename
      type {
        curie
        label
      }
      ...GeneValidityProposition1
      ...GeneticConditionMechanismProposition1
    }
    contributions {
      role {
        curie
      }
      date
      agent {
        curie
        label
      }
    }
  }
}"
       :dependencies #{{:typename "GeneValidityProposition" :detail-level  1}
                       {:typename "GeneticConditionMechanismProposition" :detail-level  1}}}}
   "GeneValidityProposition"
   {1 {:fragment "{
  modeOfInheritance {
    curie
    label
  }
  disease {
    curie
    label
  }
}"}}
   "GeneticConditionMechanismProposition"
   {1 {:fragment "{
  mechanism {
    curie
  }
  condition {
    label
    curie
  }
}"}}
   "Resource"
   {1 {:fragment "{
  __typename
  iri
  curie
  label
  type {
    curie
    label
  }
}"}}})

(defn fragment-str [{:keys [typename detail-level]}]
  (str "\nfragment "
       typename
       detail-level
       " on "
       typename
       " "
       (get-in fragments [typename detail-level :fragment])
       "\n"))

(def base-resource-query
  "query ($iri: String) {
  resource(iri: $iri) {
    ...Resource1
    ...SequenceFeature3
  }
}")

(defn sub-dependencies [new-deps existing-deps]
  (let [deps (set/union new-deps existing-deps)
        sub-deps (set/difference
                  (apply
                   set/union
                   (map #(get-in fragments [(:typename %)
                                            (:detail-level %)
                                            :dependencies])
                        new-deps))
                  deps)]
    (if (seq sub-deps)
      (sub-dependencies sub-deps deps)
      deps)))

(defn query->direct-dependencies [query]
  (->> (re-seq #"([A-Za-z]+)(\d)" query)
       (map (fn [[_ t l]] {:typename t
                           :detail-level (Integer/parseInt l)}))
       set))

(defn query->dependencies [query]
  (sub-dependencies
   (query->direct-dependencies query)
   #{}))

(defn compile-query [query]
  (str
   query
   (reduce
    str
    (map fragment-str (query->dependencies query)))))

(println (compile-query base-resource-query))

(defn add-direct-dependencies [typename detail-level]
  (->> (re-seq #"([A-Za-z]+)(\d)" (get-in fragments [typename detail-level]))
       (map (fn [[_ t l]] [t (Integer/parseInt l)]))
       set))

;; (defn direct-dependencies [typename detail-level]
;;   (->> (re-seq #"([A-Za-z]+)(\d)" (get-in fragments [typename detail-level]))
;;        (map (fn [[_ t l]] [t (Integer/parseInt l)]))
;;        set))

;; (defn all-dependencies
;;   ([typename detail-level] (all-dependencies typename detail-level #{}))
;;   ([typename detail-level existing-deps]
;;    (let [direct (direct-dependencies typename detail-level)
;;          all (set/union direct existing-deps)]
;;      (apply set/union
;;             all
;;             (map (fn [[t1 d1]]
;;                    (all-dependencies t1 d1 all))
;;                  (set/difference direct existing-deps))))))

;; (all-dependencies "SequenceFeature" 3)

;; (all-dependencies "GeneValidityProposition" 1)
(comment
  (println (fragment->str "SequenceFeature" 3))
  )

;; updating gene validity curations
(comment
  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        hybrid-db {:tdb tdb :object-db object-db}
        q (rdf/create-query "select ?x where { ?x a ?type }")]
    (rdf/tx tdb
      #_(->> (q tdb {:type :cg/Affiliation })
           first
           str)))

  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        hybrid-db {:tdb tdb :object-db object-db}
        q (rdf/create-query "select ?x where { ?x a ?type }")]
    (rdf/tx tdb
      #_(->> (q tdb {:type :so/Gene })
             count)
      (-> (rdf/resource #_"https://identifiers.org/ncbigene:6842"
                        "http://purl.obolibrary.org/obo/MONDO_0015280"
                        tdb)
          (rdf/ld-> [[:cg/disease :<] [:cg/subject :<]]))))

  (let [q (rdf/create-query "select ?x where { ?x :cg/changes ?c } ")]
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gv-sepio-2025-07-29.edn.gz"]
      (->> (event-store/event-seq r)
           count)))
  (time
   (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gv-sepio-2025-07-29.edn.gz"]
     (->> (event-store/event-seq r)
          #_(mapv ::event/key)
          #_(take 1)
          (run! #(p/publish (get-in api-test-app [:topics :gene-validity-sepio])
                            (assoc % ::event/completion-promise (promise)))))))
  (->>  (set/difference (set old-keys) (set new-keys))
        (into [])
        (spit "/users/tristan/Desktop/missingrecords.edn"))

  (+ 1 1)



  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gv-sepio-2025-07-29.edn.gz"]
    (->> (event-store/event-seq r)
         (take 1)
         (map event/deserialize)
         (run! #(rdf/pp-model (::event/data %)))))

  (get-in api-test-app [:topics :gene-validity-sepio])

  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        hybrid-db {:tdb tdb :object-db object-db}
        q (rdf/create-query "
select ?y where {
?x :prov/wasInvalidatedBy ?y .
?y :cg/subject ?prop .
filter not exists { ?y :prov/wasInvalidatedBy ?z . }
}")]
    (rdf/tx tdb
      (->> (q tdb)
          #_count
          (take 20)
          (mapv #(rdf/ld1-> % [:cg/subject :cg/gene :skos/prefLabel])))))

    (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        hybrid-db {:tdb tdb :object-db object-db}
        q (rdf/create-query "
select ?x where {
?x :cg/changes ?y .
}")]
    (rdf/tx tdb
      (->> (q tdb)
           count
           #_(take 20)
           #_(mapv #(rdf/ld1-> % [:cg/subject :cg/gene :skos/prefLabel])))))
  
  )


;; Investigationg Vibhor's complaints

(comment

;; cggv_00140591-caa8-4d47-b4ca-3f0577b16d73v2.1.json

;;   1) Handling of dc:source within Proband Data
;; The dc:source attribute is essential for transforming the data into other formats for referencing purposes. However, we have noticed variations in how this attribute is represented:

;; *
;; In some cases, dc:source is not directly present at the proband level but is instead embedded within the variant/allele attributes, where in other cases this field is attached to an evidenceLine above the description of the proband.
;; *
;; Additionally, the format of the dc:source reference varies. Sometimes it points to an object at least somewhere else in the file (for example,

  (def json-base "/Users/tristan/Downloads/gene-validity-jsonld-latest-4/")
  
  (with-open [r (io/reader
                 (str json-base
                      "cggv_00140591-caa8-4d47-b4ca-3f0577b16d73v2.1.json"))]
    (-> r
        (json/read :key-fn keyword)
        tap>))

  ;; alleleOrigin gives ID field rather than direct IRI reference in
  ;; JSON-LD

  ;; proband maximum score for AR variants seems not to include strengthScore


;; 2) Relationship Between Variants and Alleles
;; While attempting to capture the variants and alleles for a given proband, we observed that the structural relationship between them is inconsistent. In some files, an allele appears as a child of a variant, whereas in others it is represented as a sibling.
;; For example:

;;   *
;; Child of Variant:
;; File: cggv_0057caed-b37d-41d7-bd79-1e9e19b3c1efv1.0.json
;; Proband: cggv:329e6a01-1310-465b-9621-69b41858c914

  (with-open [r (io/reader
                 (str json-base
                      "cggv_0057caed-b37d-41d7-bd79-1e9e19b3c1efv1.0.json"))]
    (-> r
        (json/read :key-fn keyword)
        tap>))
  
;; (This proband also presents the dc:source issue where dc:source is a direct URI: https://nam12.safelinks.protection.outlook.com/?url=https%3A%2F%2Fpubmed.ncbi.nlm.nih.gov%2F7728151&data=05%7C02%7C%7Cbe35ee5dc1504c59deae08ddcf87445b%7C58b3d54f16c942d3af081fcabd095666%7C1%7C0%7C638894902063555441%7CUnknown%7CTWFpbGZsb3d8eyJFbXB0eU1hcGkiOnRydWUsIlYiOiIwLjAuMDAwMCIsIlAiOiJXaW4zMiIsIkFOIjoiTWFpbCIsIldUIjoyfQ%3D%3D%7C0%7C%7C%7C&sdata=w8iShqKvDPw2jN7RVfw4d9Li6RcgH66X8ktx5qfiEIo%3D&reserved=0)
;;   *
;; Sibling of Variant:
;; File: cggv_00140591-caa8-4d47-b4ca-3f0577b16d73v2.1.json
;; Proband: cggv:e04f5d2d-93a6-4265-91ef-6ce478fadc3e

  (with-open [r (io/reader
                 (str json-base
                      "cggv_00140591-caa8-4d47-b4ca-3f0577b16d73v2.1.json"))]
    (-> r
        (json/read :key-fn keyword)
        (json/write-str :indent 2)
        println))
  
  ;; how do I pretty print json output with clojure data.json?

  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        hybrid-db {:tdb tdb :object-db object-db}
        q (rdf/create-query "
select ?x where {
?x a :cg/GeneValidityProposition .
}")]
    (rdf/tx tdb
      (-> (q tdb)
          first
          (rdf/ld1-> [:cg/gene :skos/prefLabel]))))

  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        hybrid-db {:tdb tdb :object-db object-db}
        q (rdf/create-query "
select ?x where {
?x a :cg/GeneValidityProposition .
}")]
    (rdf/tx tdb
      (-> (rdf/resource :cg/Benign tdb)
          (rdf/ld1-> [:rdfs/label]))))

  "CG:GeneValidityCriteria11"


  
  )


;; importing recurrent regions
;; No longer needed; bringing in recurrent regions from
;; dosage import
(comment

  (def recurrent-region-cnvs
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_dosage_raw-2025-08-13.edn.gz"]
      (->> (event-store/event-seq r)
           (map event/deserialize)
           (filter #(and (re-find #"Recurrent" (::event/value %))
                         (seq (get-in % [::event/data :fields :labels]))))
           (into []))))


  (def recurrent-regions
    (->> recurrent-region-cnvs
         (mapv process-dosage)
         (remove ::spec/invalid)
         (into [])))

  (count recurrent-regions)

  (tap> (last recurrent-regions))
  
  (def region1
    (->> recurrent-region-cnvs
         (mapv process-dosage)
         (remove ::spec/invalid)
         first
         #_(map keys)
         #_(run! #(rdf/pp-model (::dosage/model %)))))

  (->> recurrent-region-cnvs
       (mapv process-dosage)
       (remove ::spec/invalid)
       (take-last 1)
       (run! #(rdf/pp-model (::dosage/model %)))
       #_(mapv #(dissoc % ::dosage/model))
       #_tap>)

  (-> region1
      ::dosage/region)

  (defn max-coord [coord-or-range]
  (if (vector? coord-or-range)
    (second coord-or-range)
    coord-or-range))

(defn min-coord [coord-or-range]
  (if (vector? coord-or-range)
    (first coord-or-range)
    coord-or-range))

;; question for group, how to handle outer overlaps
(defn outer-overlap? [loc1 loc2]
    (if (some vector? [(:ga4gh/start loc1)
                       (:ga4gh/start loc2)
                       (:ga4gh/end loc1)
                       (:ga4gh/end loc2)])
      (let [loc1-start (min-coord (:ga4gh/start loc1))
            loc2-start (min-coord (:ga4gh/start loc2))
            loc1-end (max-coord (:ga4gh/end loc1))
            loc2-end (max-coord (:ga4gh/end loc2))
            all-coords [loc1-start loc2-start loc1-end loc2-end]]
        (if (or (some nil? all-coords)
                (< loc1-end loc2-start)
                (< loc2-end loc1-start))
          :cg/NoOverlap
          :cg/OuterOverlap))
      :cg/NoOverlap))



(defn overlap-type [loc1 loc2]
  (let [loc1-start (max-coord (:ga4gh/start loc1))
        loc2-start (max-coord (:ga4gh/start loc2))
        loc1-end (min-coord (:ga4gh/end loc1))
        loc2-end (min-coord (:ga4gh/end loc2))
        all-coords [loc1-start loc2-start loc1-end loc2-end]]
    (cond
      (some nil? all-coords) :cg/NoOverlap
      (and (< loc1-start loc2-start)
           (< loc2-end loc1-end)) :cg/CompleteOverlap
      (or (and (< loc1-start loc2-start)
               (< loc2-start loc1-end))
          (and (< loc2-end loc1-end)
               (< loc2-start loc2-end))) :cg/PartialOverlap
      :default (outer-overlap? loc1 loc2))))
  
  (defn gene-overlaps-for-location [db location]
    (->> (mapcat
          #(rocksdb/range-get
            db
            (idx/location->search-params location %))
          [:so/Gene])
         (map :iri)
         set))


  (defn protein-coding-gene? [gene tdb]
    (let [q (rdf/create-query "select ?g where { ?g a :so/GeneWithProteinProduct } ")]
      (seq (q tdb {:g (rdf/resource gene)}))))r

  (let [object-db @(get-in api-test-app [:storage :object-db :instance])
        tdb @(get-in api-test-app [:storage :api-tdb :instance])]
    (mapv #(gene-overlaps-for-location object-db %)
          (get-in region1 [::dosage/region :ga4gh/location])))

  
  (def last-regions
    (vals
     (reduce (fn [a r] (assoc a (:iri r) r))
             {}
             (map ::dosage/region recurrent-regions))))

  (-> recurrent-regions first ::dosage/model rdf/pp-model)


  ;; 9 regions have different sets of protein coding genes
  ;; depending on what build they're mapped on
  (def asymetric-regions
    (let [object-db @(get-in api-test-app [:storage :object-db :instance])
          tdb @(get-in api-test-app [:storage :api-tdb :instance])]
      (rdf/tx tdb
        (into []
              (remove 
               (fn [r]
                 (apply =
                        (mapv (fn [l] (count (filterv #(protein-coding-gene? % tdb)
                                                      (gene-overlaps-for-location object-db l))))
                              (get-in r [:ga4gh/location]))))
               last-regions)))))


  (let [object-db @(get-in api-test-app [:storage :object-db :instance])
        tdb @(get-in api-test-app [:storage :api-tdb :instance])]
    (tap>
     (rdf/tx tdb
       (->> asymetric-regions
            (mapv
             (fn [r]
               (mapv (fn [l] (filterv #(protein-coding-gene? % tdb)
                                  (gene-overlaps-for-location object-db l)))
                     (get-in r [:ga4gh/location]))))
            (mapv (fn [[s1 s2]]
                    [(set/difference (set s1) (set s2))
                     (set/difference (set s2) (set s1))]))))))

  
  (tap> (get-in region1 [::dosage/region :ga4gh/location]))

  (tap> asymetric-regions)
  )

;; Identify gold standard CNV evaluations from ClinVar CNV data set
(comment

  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        hybrid-db {:tdb tdb :object-db object-db}
        q (rdf/create-query "
select ?contrib where {
?x a :cg/EvidenceStrengthAssertion ;
:cg/contributions ?contrib ;
:cg/subject ?prop .
?prop a :cg/VariantPathogenicityProposition .
} limit 5")]
    (rdf/tx tdb
      (->> (q tdb {:agent (rdf/resource "CVAGENT:500031")})
           (mapv #(rdf/ld1-> % [:cg/agent])))))

  

  (rdf/resource "CVAGENT:500031")
  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        hybrid-db {:tdb tdb :object-db object-db}
        q (filters/compile-filter-query
           [:bgp ['x :rdf/type :cg/EvidenceStrengthAssertion]]
           [{:filter :proposition_type
             :argument "CG:VariantPathogenicityProposition"}
            {:filter :copy_change
             :argument "EFO:0030067"}
            {:filter :partial_overlap_with_feature_set
             :argument "CG:HaploinsufficiencyFeatures"
             :operation :not_exists}
            {:filter :complete_overlap_with_feature_set
             :argument "CG:HaploinsufficiencyFeatures"
             :operation :not_exists}
            #_{:filter :gene_count_min
             :argument "CG:Genes35"
             :operation :not_exists}
            {:filter :assertion_direction
             :argument :cg/Supports}
            #_{:filter :assertion_direction
             :argument :cg/Refutes}
            {:filter :submitter
             :argument "CVAGENT:500031"
             :operation :not_exists}
            {:filter :date_evaluated_min
             :argument "2020"}
            {:filter :complete_overlap_with_feature_set
             :argument "CG:ProteinCodingGenes"}])]
    (rdf/tx tdb
      #_(println q)
      (->> (q tdb)
           #_(take 20)
           #_(into [])
           count
           #_(mapv #(hr/hybrid-resource % hybrid-db)))))


  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        hybrid-db {:tdb tdb :object-db object-db}
        q (filters/compile-filter-query
           [:bgp ['x :rdf/type :cg/EvidenceStrengthAssertion]]
           [{:filter :proposition_type
             :argument "CG:VariantPathogenicityProposition"}])]
    (rdf/tx tdb
      #_(println q)
      (->> (q tdb)
           #_(take 20)
           #_(into [])
           count
           #_(mapv #(hr/hybrid-resource % hybrid-db)))))


  (-> candidates first tap>)
  )

;; resolving search issues -- resolved
(comment
 (let [s @(get-in api-test-app [:storage :text-index :instance])]
   (->> (lucene/search s {:field :label :query "recurrent"})
        #_(take 1)
        (map :iri))
   #_(lucene/search s {:field :symbol :query "ISCA-46285"})
)
 )


;;

(comment
  (time
   (get-events-from-topic
    {:name :precuration-events
     :type :kafka-reader-topic
     :serialization :json
     :create-producer true
     :kafka-cluster :data-exchange
     :kafka-topic "gt-precuration-events"
     :kafka-topic-config {}}))
  (def zeb2-gt
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gt-precuration-events-2025-08-26.edn.gz"]
      (->> (event-store/event-seq r)
           (filterv #(re-find #"ZEB2" (::event/value %)))
           (mapv event/deserialize))))


  (def pln-gt
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gt-precuration-events-2025-08-26.edn.gz"]
      (->> (event-store/event-seq r)
           (filterv #(re-find #"PLN" (::event/value %)))
           (mapv event/deserialize))))

  (def med12-gt
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gt-precuration-events-2025-08-26.edn.gz"]
      (->> (event-store/event-seq r)
           (filterv #(re-find #"MED12" (::event/value %)))
           (mapv event/deserialize))))

  (->> med12-gt
       (remove #(re-find #"MED12L" (::event/value %)))
       tap>
       #_(mapv #(get-in % [::event/data :data :gdm_uuid]))
       #_set)

  (tap> med12-gt)
  
  (+ 1 1 )
  (->> )
  ;;CGGV:assertion_cb06ff0d-1cc6-494c-9ce5-f7cb26f34620-2018-05-23T220000.000Z
  )

(comment
  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        hybrid-db {:tdb tdb :object-db object-db}
        q (filters/compile-filter-query
           [:bgp ['x :rdf/type :cg/EvidenceStrengthAssertion]]
           [{:filter :proposition_type
             :argument "CG:GeneValidityProposition"}])]
    (rdf/tx tdb
      #_(println q)
      (->> (q tdb)
           (take 20)
           (into [])
           tap>
           #_(mapv #(hr/hybrid-resource % hybrid-db)))))

  ;; valdiate website legacy id working in current transform
  ;; TLDR, it's not, need to fix
  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        hybrid-db {:tdb tdb :object-db object-db}
        q (rdf/create-query "select ?id where { ?x :cg/websiteLegacyID ?id }")]
    (rdf/tx tdb
      #_(println q)
      (->> (q tdb)
           (take 20)
           (into [])
           
           #_(mapv #(hr/hybrid-resource % hybrid-db)))))
  )


(comment
  "http://dataexchange.clinicalgenome.org/dci/region-ISCA-37409"
  (do
    (defn overlapping-genes-set [v]
      (let [q (rdf/create-query "
select ?g where {
?v :cg/CompleteOverlap | :cg/PartialOverlap  ?g .
?g a :so/GeneWithProteinProduct .
}")]
        (set (q v {:v v})))
      #_(->> (rdf/ld-> v [:cg/CompleteOverlap])
           (concat (rdf/ld-> v
                             [:cg/PartialOverlap]))
           set))

    (defn same-region-by-genes? [gs v]
      (let [ogs (overlapping-genes-set v)]
        (= gs ogs)))
    
    (defn variants-defined-by-feature [context args value]
      (let [candidates-query (rdf/create-query "
select ?x where {
  ?r :cg/CompleteOverlap ?g .
  ?g a :so/GeneWithProteinProduct .
  ?x :cg/CompleteOverlap ?g
}")
            overlapping-genes (overlapping-genes-set value)
            candidate-variants (candidates-query value {:r value})]
        #_(tap> overlapping-genes)
        (->> (candidates-query value {:r value})
             (filterv #(same-region-by-genes? overlapping-genes %))
             #_(take 1)
             #_(mapv overlapping-genes-set))))
    
    (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
          object-db @(get-in api-test-app [:storage :object-db :instance])
          hybrid-db {:tdb tdb :object-db object-db}
          r (rdf/resource
               "http://dataexchange.clinicalgenome.org/dci/region-ISCA-37409"
               tdb)]
      (rdf/tx tdb
        (variants-defined-by-feature hybrid-db nil r))))
  )


(comment
  (def snta1
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-09-11.edn.gz"]
      (->> (event-store/event-seq r)
           (filterv #(re-find #"9b1ccee8" (::event/value %)))
           last
           event/deserialize)))

  (tap> snta1)

  )

(comment
  (with-open [r (io/reader (str "/Users/tristan/Downloads/gene-validity/https_/genegraph.clinicalgenome.org/r/" "f332bc55-8ea7-40bc-b28b-2149f6ce0a25v1.3.json"))]
    (-> (json/read r) tap>))
  )


(comment
)


;; I looked into this.  The reason the details page cannot be displayed for this assertion is because genegraph is not populating the legacy_json field when gene_validity_assertion() is called on record:
;; CGGV:assertion_dd577817-d668-413c-95f9-d3e08a8cf5d3-2020-12-15T002719.834Z
;; This is an unrecoverable error, hence the "Sorry" message.  

;; Tristan, can you investigate this and fix?  Thanks!
(comment
  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity-2025-09-29.edn.gz"]
    (->> (event-store/event-seq r)
         (filter #(re-find #"097e646b-467f-4c08-95d6-958ed324562b" #_"dd577817-d668-413c-95f9-d3e08a8cf5d3" (::event/value %)))
         #_last
         #_event/deserialize
         count))

  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2025-09-11.edn.gz"]
    (->> (event-store/event-seq r)
         (filter #(re-find #"dd577817-d668-413c-95f9-d3e08a8cf5d3" (::event/value %)))
         #_last
         #_event/deserialize
         count))
  )


;; Quarterly productivity query
(comment
  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        object-db @(get-in api-test-app [:storage :object-db :instance])
        hybrid-db {:tdb tdb :object-db object-db}
        q (rdf/create-query "
select ?x where {
 ?x a :cg/EvidenceStrengthAssertion ;
  :dc/isVersionOf ?v ;
  :cg/contributions ?contrib ;
  :cg/subject ?s .
  ?s a :cg/GeneValidityProposition .
  ?contrib :cg/role :cg/Publisher ;
  :cg/date ?date .
  FILTER(STR(?date) > \"2025-07-01\")
  FILTER(STR(?date) < \"2025-10-01\")
 }")
        role-query (rdf/create-query "
select ?agent where {
?assertion :cg/contributions ?contrib .
?contrib :cg/role ?role ;
:cg/agent ?agent .
}")
        previous-verisons-query (rdf/create-query "
select ?c where {
  ?c1 :dc/isVersionOf ?v .
  ?c :dc/isVersionOf ?v .
  ?c :dc/dateSubmitted ?date .
  FILTER(STR(?date) > \"2025-07-01\")
  FILTER(STR(?date) < \"2025-10-01\")
}
")
        has-original-version-published (fn [c]
                                         (some
                                          #(re-find #"\.0$" (rdf/ld1-> % [:cg/version]))
                                          (previous-verisons-query c {:c1 c})))
        curations (rdf/tx tdb
                    (->> (q tdb)
                         (filterv (fn [c]
                                    (or
                                     (re-find #"\.0$" (rdf/ld1-> c [:cg/version]))
                                     (has-original-version-published c))))))
        contributions (rdf/tx tdb
                        (mapv (fn [a] {:approver (some-> (role-query tdb {:assertion a :role :cg/Approver})
                                                         first
                                                         (rdf/ld1-> [:rdfs/label]))
                                       :secondary-contributor (some-> (role-query tdb {:assertion a :role :cg/SecondaryContributor})
                                                                      first
                                                                      (rdf/ld1-> [:rdfs/label]))
                                       :curation (str a)})
                              curations))
        approvers (update-vals (frequencies (mapv :approver contributions)) (fn [v] {:approvals v}))
        secondary-contributors (update-vals (frequencies (mapv :secondary-contributor contributions)) (fn [v] {:secondary-contributions v}))
        all-contributions (dissoc (merge-with merge approvers secondary-contributors) nil)]
    (tap> (filter #(= "Myriad Women's Health"
                      (:secondary-contributor %))  contributions))
    #_(with-open [w (io/writer "/users/tristan/Desktop/q3-gene-curation-report.csv")]
        (csv/write-csv
         w
         (concat[["GCEP" "Primary Approvals" "Secondary Contributions"]]
                (mapv (fn [[k m]] [k (:approvals m) (:secondary-contributions m)]) all-contributions)))))


  
  )

(comment
  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])]
    (rdf/tx tdb
      (->> (rdf/resource #_"GG:a0a9ec11-ef90-4095-9c9e-696eabd0395b""GG:c4831487-68ed-4667-95e7-2f1805817dafv1.0")
           str
           (storage/read tdb)
           rdf/pp-model)))
  )

(comment
  (-> i"/Users/tristan/Downloads/gene-validity-jsonld-latest/gg_a0a9ec11-ef90-4095-9c9e-696eabd0395bv2.1.json"
      slurp
      json/read-str
      tap>)

  )


(comment
  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])]
    (rdf/tx tdb
      (-> (rdf/resource "NCBIGENE:2664" tdb)
          (rdf/ld1-> [:rdf/type]))))

  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])]
    (rdf/tx tdb
      (-> (storage/read tdb "https://www.genenames.org/")
          .size)))
  )


(comment
  "ggapi-clinvar-curation-stage-1"
  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/ggapi-clinvar-curation-stage-1-2025-10-03.edn.gz"]
    (->> (event-store/event-seq r)
         count))
  )


(comment
  (-> "/Users/tristan/data/validity/gg_a0a9ec11-ef90-4095-9c9e-696eabd0395bv2.1.json"
      slurp
      json/read-str
      tap>)

  (-> "/Users/tristan/data/validity/gg_f53cae06-e5d6-4e85-9233-7490cc242418v1.0.json"
      slurp
      json/read-str
      tap>)
  )

(comment
  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gg-base-2025-10-07.edn.gz"]
    (->> (event-store/event-seq r)
         (filterv #(= #_"https://ncbi.nlm.nih.gov/genomes/GCF_000001405.25_GRCh37.p13_genomic.gff"
                      #_"https://ncbi.nlm.nih.gov/genomes/GCF_000001405.40_GRCh38.p14_genomic.gff"
                      #_"http://purl.obolibrary.org/obo/so.owl"
                      "https://www.ncbi.nlm.nih.gov/clinvar/"
                      (::event/key %)))
         (take 1)
         (run! #(p/publish (get-in api-test-app [:topics :base-data]) %))))

  (tap> api-test-app)

  )


(comment
  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gpm-person-events-2025-09-17.edn.gz"]
    (->> (event-store/event-seq r)
         #_(take 1)
         (map event/deserialize)
         (map #(get-in % [::event/data :data :person :profile_photo]))
         frequencies

         ))

  (tap> api-test-app)
  (defn process-gpm-person [e]
    (p/process (get-in api-test-app [:processors :import-gpm-people])
               (assoc e
                      ::event/skip-local-effects true
                      ::event/skip-publish-effects true)))

  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gpm-person-events-2025-09-17.edn.gz"]
    (->> (event-store/event-seq r)
         (run! #(p/publish (get-in api-test-app [:topics :gpm-person-events]) %))))


  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        q (rdf/create-query "
select ?x where
{ ?x :schema/email ?email ;
     :dc/source :cg/GPM . }")]
    (rdf/tx tdb
      (-> (q tdb {:email "clmartin1@geisinger.edu"})
          count)))
  
  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gpm-person-events-2025-09-17.edn.gz"]
    (->> (event-store/event-seq r)
         (map #(assoc ::event/skip-local-effects true
                      ::event/skip-publish-effects true))
         (map event/deserialize)
         (map #(get-in % [::event/data :data :person :profile_photo]))
         frequencies

         ))
  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gpm-person-events-2025-09-17.edn.gz"]
    (->> (event-store/event-seq r)
         (map #(assoc ::event/skip-local-effects true
                      ::event/skip-publish-effects true))
         (map event/deserialize)
         (map #(get-in % [::event/data :data :person :profile_photo]))
         frequencies

         ))
  
  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gpm-person-events-2025-09-17.edn.gz"]
    (->> (event-store/event-seq r)
         #_(take 1)
         (map event/deserialize)
         (map #(get-in % [::event/data :data :person :profile_photo]))
         frequencies

         ))

  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gpm-general-events-2025-09-17.edn.gz"]
    (->> (event-store/event-seq r)
         #_(take-last 5)
         (map event/deserialize)
         (map #(get-in % [::event/data :event_type]))
         frequencies
         tap>))

  (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gt-precuration-events-2025-09-17.edn.gz"]
    (->> (event-store/event-seq r)
         #_(take-last 5)
         (map event/deserialize)
         (map #(get-in % [::event/data :event_type]))
         frequencies
         tap>))
  )

;; looking into gene overlap issues
;; I think there aren't any--things seem good from here
(comment
  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        q (rdf/create-query "
select ?x where
{ ?x :cg/CompleteOverlap ?feature . }
 limit 5")]
    (rdf/tx tdb
      (->> (q tdb)
           (mapv #(rdf/ld1-> % [:rdf/type])))))
  )


;; working on Dosage regions now


(comment
  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        q (rdf/create-query "
select ?x where
{ ?x a ?type . }
 limit 5")]
    (rdf/tx tdb
      (->> (q tdb {:type :cg/GeneticConditionMechanismProposition})
           count
           #_(mapv #(rdf/ld1-> % [:rdf/type])))))

  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])
        q (rdf/create-query "
select ?x where
{ ?x a ?type . }
 limit 5")]
    (rdf/tx tdb
      (->> (q tdb {:type :so/GeneWithProteinProduct})
           count
           #_(mapv #(rdf/ld1-> % [:rdf/type])))))

  ;; HGNC not loading?
  
  ;; issue with gene overlaps in ClinVar
  ;; :genegraph.api.dosage/add-gene-overlaps - Wrong number of args (0) passed to: clojure.core/min

  (let [tdb @(get-in api-test-app [:storage :api-tdb :instance])]
    (org.apache.jena.tdb2.DatabaseMgr/location (.asDatasetGraph tdb)))

 )
