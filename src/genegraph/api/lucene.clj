(ns genegraph.api.lucene
  "Extension to support Apache Lucene using Genegraph framework storage mechanisms."
  (:require [genegraph.framework.storage :as storage]
            [genegraph.framework.protocol :as p])
  (:import [org.apache.lucene.store FSDirectory Directory]
           [java.nio.file Path Paths]
           [org.apache.lucene.analysis Analyzer]
           [org.apache.lucene.analysis.standard StandardAnalyzer]
           [org.apache.lucene.index IndexReader
            IndexWriter IndexWriterConfig IndexWriterConfig$OpenMode
            IndexableField IndexableFieldType DirectoryReader]
           [org.apache.lucene.search IndexSearcher TopDocs ScoreDoc]
           [org.apache.lucene.index StoredFields]
           [org.apache.lucene.queryparser.classic QueryParser]
           [org.apache.lucene.document Document Field KeywordField KnnFloatVectorField LongField TextField Field$Store StoredValue StoredValue$Type]
           [org.apache.lucene.util IOUtils]))

;; Need to figure out creation & backup


(defrecord LuceneInstance [name
                           type
                           path
                           state
                           instance]

  p/Lifecycle
  (start [this])
  (stop [this])


  p/Resetable
  (reset [this]))

(defmethod p/init :lucene [db-def]
  )


(comment


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

  (defn document-iri [doc]
    (-> doc (.getField "iri") .stringValue))
  
  (defn score-doc->m [score-doc stored-fields]
    {:iri (document-iri (.document stored-fields (.doc score-doc) #{"iri"}))
     :score (.score score-doc)})

  (let [q (.parse parser "minor")
        fields (.storedFields indexSearcher)]
    (mapv #(score-doc->m % fields)
          (.scoreDocs (.search indexSearcher q 100))))

  
  
  )
