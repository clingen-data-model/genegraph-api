(ns genegraph.log-monitor
  (:require [genegraph.framework.app :as app]
            [genegraph.framework.event :as event]
            [genegraph.framework.protocol :as p]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.storage.rocksdb :as rocksdb]
            [genegraph.api :as gv]
            [clojure.data.json :as json]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.log :as log]
            [portal.api :as portal]
            [clojure.walk :as walk]
            [clojure.string :as string]
            [clojure.data :as data]
            [hato.client :as hc]))

(defn handle-log-entry-fn [e]
  (let [data (::event/data e)]
    #_(log/info :duration (- (:end-time data) (:start-time data))
              :status (:status data)
              :response-size (:response-size data)
              :start-time (:start-time data)
              :handled-by (:handled-by data))
    (event/store e
                 :log-store
                 [:log-record (str (:start-time data))]
                 data)))

(def handle-log-entry
  (interceptor/interceptor
   {:name ::handle-log-entry
    :enter (fn [e] (handle-log-entry-fn e))}))

(def log-monitor-def
  {:type :genegraph-app
   :kafka-clusters {:data-exchange gv/data-exchange}
   :topics
   {:api-log
    (assoc gv/api-log-topic
           :type :kafka-reader-topic)}
   :storage
   {:log-store
    {:name :log-store
     :type :rocksdb
     :path "/users/tristan/data/genegraph-neo/log-store-prod-1"}}
   :processors
   {:api-log-reader
    {:name :api-log-reader
     :type :processor
     :backing-store :log-store
     :subscribe :api-log
     :interceptors [handle-log-entry]}}})

(defn print-query [res]
  (-> (:query res)
      (json/read-str :key-fn keyword)
      :query
      println))

(comment

  (def p (portal/open))
  (add-tap #'portal/submit)
  (portal/close)
  (portal/clear)
  
  (def log-monitor (p/init log-monitor-def))
  (p/start log-monitor)
  (p/stop log-monitor)
  (-> (rocksdb/range-get @(get-in log-monitor [:storage :log-store :instance])
                         [:log-record])
      last
      tap>)

  (->> (rocksdb/range-get @(get-in log-monitor [:storage :log-store :instance])
                          [:log-record])
       (filter #(not= (:status %) 200))
       (map #(assoc % :duration (- (:end-time %) (:start-time %))))
       (map :duration))
  
  
  (->> (rocksdb/range-get @(get-in log-monitor [:storage :log-store :instance])
                          [:log-record])
       (sort-by :response-size)
       reverse
       (take 10)
       tap>)

  (->> (rocksdb/range-get @(get-in log-monitor [:storage :log-store :instance])
                          [:log-record])
       (remove :handled-by)
       #_(filter #(re-find #"genes\(limit" (:query %)))
       (map #(assoc % :duration (- (:end-time %) (:start-time %))))
       (sort-by :duration)
       reverse
       (take 20)
       (mapv :duration))

  (->> (rocksdb/range-get @(get-in log-monitor [:storage :log-store :instance])
                          [:log-record])
       (remove :handled-by)
       #_(filter #(re-find #"genes\(limit" (:query %)))
       (map #(assoc % :duration (- (:end-time %) (:start-time %))))
       (sort-by :duration)
       reverse
       (take 1)
       #_(mapv :duration)
       (map #(assoc % :parsed-query (json/read-str (:query %) :key-fn keyword)))
       (run! #(println (:parsed-query %))))

  

  
  
  (spit "/Users/tristan/Desktop/problem-query.graphql"
        (-> (storage/read @(get-in log-monitor [:storage :log-store :instance])
                          [:log-record "1720618886905"])
            :query
            (json/read-str :key-fn keyword)
            :query))
  
  )
(def c (hc/build-http-client {:connect-timeout 100
                              :redirect-policy :always
                              :timeout (* 1000 60 10)}))

(def genegraph-stage "https://genegraph.stage.clingen.app/api")
(def genegraph-local "http://localhost:8888/api")

(defn parse-response [response]
  (walk/postwalk
   (fn [x]
     (if (map? x)
       (update-vals x
                    (fn [y]
                      (cond (vector? y) (set y)
                            (= "CG:PediatricActionabilityWorkingGroup" y)
                            "CGTERMS:PediatricActionabilityWorkingGroup"
                            (= "CG:AdultActionabilityWorkingGroup" y)
                            "CGTERMS:AdultActionabilityWorkingGroup"
                            :else y)))
       x))
   (-> response :body (json/read-str :key-fn keyword))))

(defn request [query host]
  (try
    (parse-response
     (hc/post host
              {:http-client c
               :content-type :json
               :body query}))
    (catch Exception e {:exception e})))

(defn request-retries [query host retries]
  (loop [r (request query host)
         attempt 0]
    (if (and (:exception r) (< attempt retries))
      (recur (request query host) (inc retries))
      r)))

(defn populate-querydb [db host offsets response-key]
  (run! (fn [o]
          (let [e (storage/read db [:log-record o])]
            (when-not (= ::storage/miss e)
              (let [q (string/replace (:query e) "legacy_json" "")
                    r (request-retries q host 10)]
                (if (:exception r)
                  (storage/write db
                                 [:event o]
                                 (assoc e response-key :exception))
                  (storage/write db
                                 [:event o]
                                 (assoc e response-key r)))))))
        offsets))

(defn compare-querydb [db offsets key-1 key-2]
  (run! (fn [o]
          (let [e (storage/read db [:log-record o])
                v1 (get e key-1)
                v2 (get e key-2)]
            (when-not (or (= ::storage/miss e)
                          (= :exception v1)
                          (= :exception v2))
              (storage/write db
                             [:event o]
                             (assoc e :diff (data/diff v1 v2))))))
        offsets))

(comment
  (populate-querydb @(get-in log-monitor [:storage :log-store :instance])
                    genegraph-local
                    ["1711397874600"]
                    ::local-response)

  (let [q (:query (storage/read @(get-in log-monitor [:storage :log-store :instance])
                               [:log-record "1711548454782"]))
        local (future (request-retries q genegraph-local 10))
        remote (future (request-retries q genegraph-stage 10))]
    (portal/clear)
    (tap> (data/diff @local @remote)))
  
  )

(comment
  (time (populate-querydb genegraph-local (take 1000 offsets) :local-response))
  (time (populate-querydb genegraph-stage (take 1000 offsets) :genegraph-response))
  (time (compare-querydb (take 1000 offsets) :genegraph-response :local-response))
  
  (let [db @(:instance querydb)
        discrepancies (->> (take 1000 offsets)
                           (map #(storage/read db [:event %]))
                           (remove #(= ::storage/miss %))
                           (remove (fn [e]
                                     (let [[d1 d2 _] (:diff e)]
                                       (and (nil? d1) (nil? d2)))))
                           (remove (fn [e]
                                     (let [[d1 d2 _] (:diff e)]
                                       (and (get-in d1 [:data :gene :chromosome_band])
                                            (get-in d1 [:data :gene :chromosome_band])))))
                           (remove #(re-find #"resource\(iri:" (:query %)))
                           #_(filter #(= :exception (:local-response %))))]
    (portal/clear)
    (print-query (nth discrepancies 0))
    (tap> (nth discrepancies 0))
    (count discrepancies))
  
  (def new-offsets
    (s/difference (set (map ::event/offset current-discrepancies-2))
                  (set (map ::event/offset previous-discrepancies))))

  (tap> (filter #(new-offsets (::event/offset %)) current-discrepancies-2))


  (count current-discrepancies-2)
  (count previous-discrepancies)
  (count current-discrepancies)


  (let [db @(:instance querydb)
        discrepancies (->> (take 1000 offsets)
                           (map #(storage/read db [:log-record %]))
                           (remove #(= ::storage/miss %))
                           (remove (fn [e]
                                     (let [[d1 d2 _] (:diff e)]
                                       (and (nil? d1) (nil? d2))))))]
    (->> discrepancies
         (remove #(re-find #"resource\(iri:" (:query %)))
         (map :query)
         frequencies
         (sort-by second)
         reverse))
  
  
  (time
   (event-store/with-event-reader [r "/users/tristan/data/genegraph-neo/genegraph-logs_2024-02-14.edn.gz"]
     (storage/write @(:instance querydb)
                    :all-offsets
                    (persistent!
                     (reduce (fn [offsets e]
                               (conj! offsets (::event/offset e)))
                             (transient [])
                             (event-store/event-seq r))))))
  

  (defn legacy-json [x]
    (-> x
        :data
        :gene
        :genetic_conditions
        first
        :gene_validity_assertions
        first
        p        :legacy_json))
  
pp  (tap> (data/diff (-> diffs first :diff second legacy-json json/read-str)
                   (-> diffs first :diff first legacy-json json/read-str)))


  

  )
