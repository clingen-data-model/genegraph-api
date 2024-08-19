(ns genegraph.gv-comparision
  (:require [genegraph.framework.app :as app]
            [genegraph.framework.event :as event]
            [genegraph.framework.protocol :as p]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.event.store :as event-store]
            [genegraph.gene-validity.graphql.response-cache :as response-cache]
            [genegraph.gene-validity :as gv]

            [genegraph.framework.storage.rdf.query :as rdf-query]
            [clojure.java.io :as io]

            
            [portal.api :as portal])
  (:import [org.apache.jena.query Dataset ReadWrite TxnType]
           [org.apache.jena.rdf.model Model Resource ResourceFactory
            ModelFactory]))


(def comparision-app-def
  {:type :genegraph-app
   :topics {:gene-validity-complete
            {:name :gene-validity-complete
             :type :simple-queue-topic}
            :gene-validity-sepio
            {:name :gene-validity-sepio
             :type :simple-queue-topic}
            :fetch-base-events
            {:name :fetch-base-events
             :type :simple-queue-topic}
            :base-data
            {:name :base-data
             :type :simple-queue-topic}
            :actionability
            {:name :actionability
             :type :simple-queue-topic}
            :dosage
            {:name :dosage
             :type :simple-queue-topic}
            :gene-validity-legacy-complete
            {:name :gene-validity-legacy-complete
             :type :simple-queue-topic}
            :api-log
            {:name :api-log
             :type :simple-queue-topic}}
   :storage {:old-tdb {:type :rdf
                       :name :old-tdb
                       :path "/Users/tristan/data/genegraph-comparision/tdb"}
             :gv-tdb {:type :rdf
                       :name :gv-tdb
                       :snapshot-handle
                       {:type :file
                        :base "/Users/tristan/data/genegraph-comparision/"
                        :path "gv-tdb-v10.nq.gz"}
                       :load-snapshot true
                       :path "/Users/tristan/data/genegraph-comparision/tdb-new"}
             :gene-validity-version-store {:name :gene-validity-version-store
                                           :type :rocksdb
                                           :path "/Users/tristan/data/genegraph-comparision/version-store"}}
   :processors {:gene-validity-transform gv/transform-processor
                :fetch-base-file gv/fetch-base-processor
                :import-base-file gv/import-base-processor
                :import-gv-curations gv/import-gv-curations
                :graphql-api (assoc gv/graphql-api
                                    ::event/metadata
                                    {::response-cache/skip-response-cache true})
                :import-actionability-curations gv/import-actionability-curations
                :import-dosage-curations gv/import-dosage-curations
                :import-gene-validity-legacy-report gv/gene-validity-legacy-report-processor}
   :http-servers gv/gv-http-server})

(def gv-prop-query
  (rdf/create-query "select ?x where { ?x a :sepio/GeneValidityProposition } limit 10000"))

(def gv-assertion-query
  (rdf/create-query "select ?x where { ?x a :sepio/GeneValidityEvidenceLevelAssertion } "))

(comment
  (def p (portal/open))
  (add-tap #'portal/submit)
  (portal/close)
  (portal/clear)
  )

(comment
  (def comp-app (p/init comparision-app-def))
  (p/start comp-app)
  (p/stop comp-app)
  (let [tdb @(get-in comp-app [:storage :old-tdb :instance])]
    (rdf/tx tdb
      (let [graphs (mapv str (gv-prop-query tdb))]
        (rdf/pp-model (storage/read tdb (first graphs))))))

  (let [tdb-old @(get-in comp-app [:storage :old-tdb :instance])
        tdb-new @(get-in comp-app [:storage :gv-tdb :instance])]
    (rdf/tx tdb-old
      (rdf/tx tdb-new
        (.size (.getUnionModel tdb-new)))))

  (let [tdb-old @(get-in comp-app [:storage :old-tdb :instance])
        tdb-new @(get-in comp-app [:storage :gv-tdb :instance])]
    (rdf/tx tdb-old
      (rdf/tx tdb-new
        (mapv (fn [n]
                (try
                  (println "---" n "---")
                  (rdf/pp-model
                   (.difference
                    (storage/read tdb-old n)
                    (storage/read tdb-new n)))
                  (catch Exception e :error)))
              (mapv str (gv-prop-query tdb-old))))))

  (let [tdb-old @(get-in comp-app [:storage :old-tdb :instance])
        tdb-new @(get-in comp-app [:storage :gv-tdb :instance])]
    (rdf/tx tdb-old
      (rdf/tx tdb-new
        (->> (gv-prop-query tdb-new)
             (map str)
             (map (fn [n]
                    (try
                      (let [m1 (storage/read tdb-old n)
                            m2 (storage/read tdb-new n)
                            d1 (.difference m1 m2)
                            d2 (.difference m2 m1)]
                        #_                     (when (< 20 (.size d))
                                                 (rdf/pp-model d))
                        [n
                         (.size m1)
                         (.size m2)
                         (.size d1)
                         (.size d2)
                         d1
                         d2
                         m1])
                      (catch Exception e :error))))
             (filter #(< 10 (nth % 3)))
             #_(take 1)
             (map #(conj % (-> (nth % 7) gv-assertion-query first str)))
             (remove #(re-find #"gci-express" (nth % 8)))
             #_(mapv [])
             #_tap>
             #_(take 5)
             #_(map #(conj % (rdf/to-turtle (nth % 5))))
             (run! (fn [x]
                     (println (nth x 0))
                     (println "------------")
                     (rdf/pp-model (nth x 5))
                     (println "------------")
                     (rdf/pp-model (nth x 6))
                     (println "------------")))
             #_(into [])
             #_count))))
  (count "http://dataexchange.clinicalgenome.org/gci/")

  
  (def lars2-list
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2024-06-28.edn.gz"]
      (->> (event-store/event-seq r)
           (filter #(re-find #"8afe11e7-7c53-46e2-b48e-37ee2bc144ce"
                             (::event/value %)))
           (into []))))

  (count lars2-list)


  
  (defn process-gv [event]    
    (p/process (get-in comp-app [:processors :gene-validity-transform])
               (assoc event
                      ::event/completion-promise (promise)
                      ::event/skip-local-effects true
                      ::event/skip-publish-effects true)))

  (count last-issue-2)

  
  
  (->> last-issue-2
       (map process-gv)
       (mapv ::event/iri))

  (def test-event
    (->> last-issue
         (map process-gv)
         (filter #(= "http://dataexchange.clinicalgenome.org/gci/2e16f182-bd87-4206-81ce-709bff483d18"
                     (::event/iri %)))
         last))


  (keys test-event)

  (let [tdb-old @(get-in comp-app [:storage :old-tdb :instance])
        tdb-new @(get-in comp-app [:storage :gv-tdb :instance])]
    (rdf/tx tdb-old
      (rdf/tx tdb-new
        (try
          (let [m1 (storage/read tdb-old
 "http://dataexchange.clinicalgenome.org/gci/06ff19da-e3d3-4bf8-8b84-7afe1d12ee4f")
                m2 (:gene-validity/model
                    (p/process (get-in comp-app [:processors :gene-validity-transform])
                               (assoc (last last-issue-2)
                                      ::event/completion-promise (promise)
                                      ::event/skip-local-effects true
                                      ::event/skip-publish-effects true)))]
            (println "\n---\n")
            #_(println (rdf/to-turtle (.difference m1 m2)))
            (rdf/pp-model (.difference m1 m2))
            (println "\n---\n")
            (rdf/pp-model (.difference m2 m1))
            #_(println (rdf/to-turtle (.difference m2 m1)))
            [(.size m1)
             (.size m2)
             (.size (.difference m1 m2))
             (.size (.difference m2 m1))])
          (catch Exception e :error)))))

  (-> (p/process (get-in comp-app [:processors :gene-validity-transform])
                 (assoc (last last-issue-2)
                        ::event/completion-promise (promise)
                        ::event/skip-local-effects true
                        ::event/skip-publish-effects true))
      :gene-validity/model
      rdf/pp-model)

  (println
   (rdf-query/expand-query-str
    (slurp (io/resource "genegraph/gene_validity/sepio_model/construct_proband_score.sparql"))))
  p)


(comment
  (def ascl4-event
        (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2024-06-28.edn.gz"]
      (->> (event-store/event-seq r)
           (filter #(re-find #"b4da722c-f7fc-4dc4-ad19-1d1b023d488f"
                             (::event/value %)))
           last)))

  (->> (process-gv ascl4-event)
       :gene-validity/model
       rdf/pp-model)

  (->> (process-gv ascl4-event)
       ::event/data
       tap>)

  

  )


(comment
  (def lars2-list
    (event-store/with-event-reader [r "/Users/tristan/data/genegraph-neo/gene_validity_complete-2024-06-28.edn.gz"]
      (->> (event-store/event-seq r)
           (filter #(re-find #"8afe11e7-7c53-46e2-b48e-37ee2bc144ce"
                             (::event/value %)))
           (into []))))

  (-> (process-gv (second lars2-list))
      :gene-validity/model
      rdf/pp-model)

    (-> (process-gv (second lars2-list))
        ::event/data
        tap>)
  )
