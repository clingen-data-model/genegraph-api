(ns genegraph.api.graphql.response-cache
  (:require [genegraph.framework.storage :as storage]
            [genegraph.framework.event :as event]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.log :as log])
  (:import [java.time Instant]))

(defn retrieve-cached-result [event]
  (let [db (get-in event [::storage/storage :response-cache-db])
        cache-result (storage/read
                      db
                      [:cached-result (get-in event [:request :body])])
        last-update (storage/read db :last-update)]
    (if (or (= ::storage/miss cache-result)
            (and (number? last-update)
                 (:calculated-at cache-result)
                 (< (:calculated-at cache-result) last-update)))
      (assoc event ::query-timer (System/currentTimeMillis))
      (assoc event
             :response (:response cache-result)
             ::event/handled-by :response-cache))))

(defn enter-response-cache [event]
  (if (::skip-response-cache event)
    event
    (retrieve-cached-result event)))

(defn store-response-in-cache [event]
  (let [current-time (System/currentTimeMillis)]
    (if (and (::query-timer event)
             (< 10 (- current-time (::query-timer event))))
      (event/store event
                   :response-cache-db
                   [:cached-result (get-in event [:request :body])]
                   {:response (:response event)
                    :calculated-at current-time})
      event)))

(defn leave-response-cache [event]
  (if (::skip-response-cache event)
    event
    (store-response-in-cache event)))

(def response-cache
  (interceptor/interceptor
   {:name ::response-cache
    :enter (fn [e] (enter-response-cache e))
    :leave (fn [e] (leave-response-cache e))}))

(def invalidate-cache
  (interceptor/interceptor
   {:name ::invalidate-cache
    :enter (fn [e] (event/store
                    e
                    :response-cache-db
                    :last-update
                    (System/currentTimeMillis)))}))
