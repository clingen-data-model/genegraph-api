(ns genegraph.api.lucene
  "Extension to support Apache Lucene using Genegraph framework storage mechanisms."
  (:require [genegraph.framework.storage :as storage]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.protocol :as p]
            [clojure.java.io :as io])
  (:import [java.util.concurrent ArrayBlockingQueue TimeUnit]
           [org.apache.lucene.store FSDirectory Directory]
           [java.nio.file Path Paths]
           [org.apache.lucene.analysis Analyzer]
           [org.apache.lucene.analysis.standard StandardAnalyzer]
           [org.apache.lucene.analysis.core KeywordAnalyzer]
           [org.apache.lucene.index IndexReader
            IndexWriter IndexWriterConfig IndexWriterConfig$OpenMode
            IndexableField IndexableFieldType DirectoryReader]
           [org.apache.lucene.search IndexSearcher TopDocs ScoreDoc SearcherManager BooleanQuery BooleanQuery$Builder BooleanClause$Occur TermQuery]
           [org.apache.lucene.index StoredFields Term]
           [org.apache.lucene.queryparser.classic QueryParser]
           [org.apache.lucene.queryparser.simple SimpleQueryParser]
           [org.apache.commons.io FileUtils]
           [org.apache.lucene.index VectorSimilarityFunction]
           [org.apache.lucene.document
            Document
            Field
            KeywordField
            KnnFloatVectorField
            LongField
            DoubleField
            TextField
            Field$Store
            StoredValue
            StoredValue$Type]
           [org.apache.lucene.util IOUtils]
           [com.google.genai Models Client]
           [com.google.genai.types
            EmbedContentConfig
            EmbedContentConfig$Builder
            EmbedContentResponse]))

;; gemini-embedding-001 has too many dimensions
;; lucene only supports 1024
(def embedding-model
  #_"gemini-embedding-001" 
  "text-embedding-005")

(defn document-iri [doc]
  (-> doc (.getField "iri") .stringValue))

(defn retrieve-or-create-embedding [object-db ai-client text]
  ;; The actual embeddings vector is buried deeply in
  ;; Optionals and Arrays. Follow the happy path to get the
  ;; array of floats.
  (into-array
   Float/TYPE
   (-> ai-client                         ; Client
       .models                           ; Models
       (.embedContent embedding-model
                      text
                      (.build (EmbedContentConfig/builder))) ; EmbedContentResponse
       .embeddings                                           ; Optional
       .get                                                  ; List 
       first                             ; ContentEmbedding
       .values                           ; Optional 
       .get)))

(defn add-text-embedding-vectors [object-db ai-client doc]
  (assoc doc
         :embedding-vectors
         (mapv #(retrieve-or-create-embedding object-db ai-client %)
               (:embeddings doc))))

;; Consider validation with spec
;; right now assuming happy path--should hopefully throw
;; exception if anything is up

(defn ->lucene-document [m]
  (let [doc (Document.)]
    (.add doc (KeywordField. "iri" (:iri m) Field$Store/YES))
    (when (:source m)
      (.add doc (KeywordField. "source" (:source m) Field$Store/YES)))
    (doseq [kw (:symbols m)] (.add doc (KeywordField. "symbol" kw Field$Store/YES)))
    (doseq [kw (:types m)] (.add doc (KeywordField. "type" kw Field$Store/YES)))
    (doseq [l (:labels m)] (.add doc (TextField. "label" l Field$Store/YES)))
    (doseq [d (:descriptions m)] (.add doc (TextField. "description" d Field$Store/YES)))
    (doseq [v (:embedding-vectors m)] (.add doc
                                            (KnnFloatVectorField.
                                             "embeddings"
                                             v
                                             VectorSimilarityFunction/DOT_PRODUCT)))
    doc))


#_(type (-> @genegraph.user/p first first))

(defprotocol Searchable
  (search [this params]))

(defn score-doc->m [score-doc stored-fields]
  {:iri (document-iri (.document stored-fields (.doc score-doc) #{"iri"}))
   :score (.score score-doc)})

(defn parsed-query
  [{:keys [symbol-parser label-parser description-parser]
    :as lucene-instance}
   {:keys [field query] :as search-params}]
  (println "parsed-query " field)
  (let [parser (case field
                 :symbol symbol-parser
                 :label label-parser
                 :description description-parser)]
    (.parse parser query)))

(defn type-filtered-query
  [lucene-instance search-params]
  (println (-> search-params :type rdf/resource str))
  (-> (BooleanQuery$Builder.)
      (.add (parsed-query lucene-instance search-params)
            BooleanClause$Occur/MUST)
      (.add (TermQuery.
             (Term. "type"
                    (-> search-params :type rdf/resource str)
                    #_"http://purl.obolibrary.org/obo/SO_0001217"
                    #_"https://genegraph.clinicalgenome.org/terms/Affiliation"))
            BooleanClause$Occur/MUST)
      .build))

;; TODO think about the architecture of this
;; in the context of adding embeddings queries
(defn embeddings-query
  [lucene-instance search-params]
  (KnnFloatVectorField/newVectorQuery
   "embeddings"
   (retrieve-or-create-embedding nil (:google-ai-client lucene-instance) (:query search-params))
   5))

(defn ->query
  [lucene-instance search-params]
  (if (= :embeddings (:field search-params))
    (embeddings-query lucene-instance search-params)
    (if (:type search-params)
      (type-filtered-query lucene-instance search-params)
      (parsed-query lucene-instance search-params))))

(defn execute-search
  [{:keys [searcher-manager] :as lucene-instance} search-params]
  (let [searcher (.acquire searcher-manager)
        query (->query lucene-instance search-params)]
    (->> (.search (.acquire searcher-manager)
                  query
                  (:limit search-params 100))
         .scoreDocs
         (mapv #(score-doc->m % (.storedFields searcher))))))

(defrecord LuceneInstance [writer
                           searcher-manager
                           symbol-parser
                           label-parser
                           description-parser
                           needs-commit-q
                           state
                           google-ai-client]
  
  storage/IndexedWrite
  (storage/write [this k v]
    (.updateDocument writer
                     (Term. "iri" k)
                     (->lucene-document
                      (add-text-embedding-vectors nil
                                                  google-ai-client
                                                  v)))
    (.offer needs-commit-q k))
  (storage/write [this k v commit-promise]
    (storage/write this k v)
    (deliver commit-promise true))

  Searchable
  (search [this search-params]
    (execute-search this search-params)))

(defn create-google-ai-client [{:keys [project location]}]
  (if (and project location)
    (-> (Client/builder)
        (.project project)
        (.location location)
        (.vertexAI true)
        (.build))
    nil))

#_(def c
  (create-google-ai-client
   {:project "clingen-dx"
    :location "us-east1"}))

(defrecord LuceneContainer [name
                            type
                            path
                            state
                            instance]

  storage/HasInstance
  (storage/instance [_] @instance)

  p/Lifecycle
  (start [this]
    (let [fsd (FSDirectory/open (Paths/get path (make-array String 0)))
          writer (IndexWriter.
                  fsd
                  (doto (IndexWriterConfig. (StandardAnalyzer.))
                    (.setOpenMode IndexWriterConfig$OpenMode/CREATE_OR_APPEND)))]
      (reset! state :running)
      ;; populate with something in case directory is empty
      ;; searchermanager needs an index of something to start
      (.updateDocument writer
                       (Term. "lucenetestdoc" "lucenetestdoc")
                       (doto (Document.)
                         (.add (KeywordField.
                                "lucenetestdoc"
                                "lucenetestdoc"
                                Field$Store/YES))))
      (.commit writer)
      (reset! instance
              (map->LuceneInstance
               {:writer writer
                :searcher-manager (SearcherManager. fsd nil)
                :symbol-parser (SimpleQueryParser. (KeywordAnalyzer.) "symbol")
                :label-parser (SimpleQueryParser. (StandardAnalyzer.) "label")
                :description-parser (SimpleQueryParser. (StandardAnalyzer.)
                                                        "description")
                :needs-commit-q (ArrayBlockingQueue. 2)
                :state state
                :google-ai-client (create-google-ai-client this)}))
      (Thread/startVirtualThread
       (fn []
         (let [{:keys [writer searcher-manager needs-commit-q]} @instance]
           (while (= :running @state)
             (when (.poll needs-commit-q 1 TimeUnit/SECONDS)
               (.commit writer)
               (.maybeRefresh searcher-manager))))))))
  
  (stop [this]
    (reset! state :stopped)
    (.close (:searcher-manager @instance))
    (.close (:writer @instance)))

  p/Resetable
  (reset [this]
    (when-let [opts (:reset-opts this)]
      (when (and (:destroy-snapshot opts) (:snapshot-handle this))
        (-> this :snapshot-handle storage/as-handle storage/delete-handle))
      (FileUtils/deleteDirectory (io/file path)))))

(defmethod p/init :lucene [db-def]
  (map->LuceneContainer
   (merge
    db-def
    {:instance (atom nil)
     :state (atom :stopped)})))


(comment
  (TermQuery. (Term. "label" "autism"))

  (comment ())

  (def lc
    (p/init
     {:type :lucene
      :name :test-lucene
      :path "/Users/tristan/data/lucene-test/"
      :reset-opts {}}))

  (p/start lc)
  (p/stop lc)
  (p/reset lc)



  (def ex1
    {:iri "https://genegraph.clinicalgenome.org/r/ZEB2"
     :source "genenames.org"
     :symbols ["ZEB2" "ZFHX1B"]
     :labels ["zinc finger E-box binding homeobox 2" "zinc finger homeobox 1b"]})

  (println (str (->lucene-document ex1)))

  (def ex2
    {:iri "https://genegraph.clinicalgenome.org/r/BRCA2"
     :source "genenames.org"
     :symbols ["BRCA2"
               "BROVCA2"
               "FACD"
               "FAD"
               "FAD1"
               "FANCD"
               "FANCD1"
               "GLM3"
               "PNCA2"
               "XRCC11"]
     :labels ["BRCA2 DNA repair associated" "Breast cancer type 2 susceptibility protein"]})

  (def ex3
    {:iri "https://genegraph.clinicalgenome.org/r/BRCA1"
     :source "genenames.org"
     :symbols ["BRCA1"]
     :labels ["BRCA1 DNA repair associated" "Breast cancer type 1 susceptibility protein"]})

  (storage/write @(:instance lc)
                 "https://genegraph.clinicalgenome.org/r/ZEB2"
                 ex1)

  (storage/write @(:instance lc)
                 "https://genegraph.clinicalgenome.org/r/BRCA2"
                 ex2)

  (storage/write @(:instance lc)
                 "https://genegraph.clinicalgenome.org/r/BRCA1"
                 ex3)
  (-> @(:instance lc) :writer .commit)
  (-> @(:instance lc) :searcher .maybeRefresh)
  

  ;; explain why the following code offers no search results despite the document with a matching
  ;; field existing in the lucene index. the field being searched is a keyword field.
  (let [sm (-> @(:instance lc) :searcher)
        s (.acquire sm)
        result (.search s (.parse (SimpleQueryParser. (KeywordAnalyzer.) "symbol") "ZEB2") 100)]
    (.release sm s)
    (.totalHits result))

(.parse (SimpleQueryParser. anal "label") "spending* limit finder")

  
  ;; writing
  (def dir "/Users/tristan/data/lucene-test")
  (def fsd
    (FSDirectory/open
     (Paths/get dir (make-array String 0))))

  (def anal (StandardAnalyzer.))

  (def iwc (IndexWriterConfig. anal))

  (.setOpenMode iwc IndexWriterConfig$OpenMode/CREATE_OR_APPEND)

  (def writer (IndexWriter. fsd iwc))

  (defn document-field-key-value [field]
    (let [stored-value (.storedValue field)]
      [(.name field)
       (case (.name (.getType stored-value))
         "BINARY" (.getBinaryValue stored-value)
         "DOUBLE" (.getDoubleValue stored-value)
         "FLOAT" (.getFloatValue stored-value)
         "INTEGER" (.getIntValue stored-value)
         "LONG" (.getLongValue stored-value)
         "STRING" (.getStringValue stored-value)
         (.name (.getType stored-value)))]))


  
  (->> (doto (Document.)
         (.add (KeywordField.
                "iri"
                "https://genegraph.clinicalgenome.org/test"
                Field$Store/YES))
         (.add (TextField.
                "dc:description"
                "I am the very model of a modern major general."
                Field$Store/YES)))
       .getFields
       (mapv document-field-key-value))

  (let [d (doto (Document.)
            (.add (KeywordField.
                   "iri"
                   "https://genegraph.clinicalgenome.org/r/test2"
                   Field$Store/YES))
            (.add (TextField.
                   "dc:description"
                   "I am the very model of a modern minor general."
                   Field$Store/YES))
            (.add (TextField.
                   "dc:description"
                   "and many interesting facts about the length of the hypotenuse."
                   Field$Store/YES)))]
    (.addDocument writer d))



  (.commit writer)
  ;; there is also (.forceMerge writer 1) -- docs suggest this is an expensive
  ;; operation
 

  ;; reading
  (def reader (DirectoryReader/open fsd))
  (def indexSearcher (IndexSearcher. reader))
  (def parser (QueryParser. "dc:description" anal))

  (.numDocs reader)

  
  
  (defn score-doc->m [score-doc stored-fields]
    {:iri (document-iri (.document stored-fields (.doc score-doc) #{"iri"}))
     :score (.score score-doc)})

  (let [q (.parse parser "minor")
        fields (.storedFields indexSearcher)]
    (mapv #(score-doc->m % fields)
          (.scoreDocs (.search indexSearcher q 100))))

  (def client
    (-> (Client/builder)
        (.project "clingen-dx")
        (.location "us-east1")
        (.vertexAI true)
        (.build)))
  (.close client)
  
  (time
   (-> client
       .models
       (.embedContent "gemini-embedding-001"
                      ["I am the very model of a modern Major-General,"]
                      (.build (EmbedContentConfig/builder)))))

  ["I am the very model of a modern Major-General,"
   "I've information vegetable, animal, and mineral,"
   "I know the kings of England, and I quote the fights historical"
   "From Marathon to Waterloo, in order categorical"
   "I'm very well acquainted, too, with matters mathematical,"
   "I understand equations, both the About binomial theorem simple and quadratical,"
   "I'm teeming with a lot o' news,"
   "With many cheerful facts about the square of the hypotenuse."]
  
  ;; create an example of a text embedding using the google-genai java api

  (def q (ArrayBlockingQueue. 2))
  (def run-q-test (atom true))

  (reset! run-q-test false)
  (reset! run-q-test true)
  
  (Thread/startVirtualThread
   (fn []
     (while @run-q-test
       (try
         (if-let [r (.poll q 1 TimeUnit/SECONDS)]
           (println r)
           (println "nothing yet"))
         (catch Exception e (.printStackTrace e))))))
  (dotimes [i 5]
    (.offer q (str "try me again " i)))
  (println (.poll q 500 TimeUnit/MILLISECONDS))
  
  )

