(ns genegraph.api.common
  (:require [io.pedestal.interceptor :as interceptor]
            [genegraph.framework.storage :as storage])
  (:import [org.apache.jena.sparql.core Transactional]
           [org.apache.jena.query ReadWrite]
           [org.apache.jena.rdf.model Model]))

(def jena-transaction-interceptor
  (interceptor/interceptor
   {:name ::jena-transaction-interceptor
    :enter (fn [context]
             (let [api-tdb (get-in context [::storage/storage :api-tdb])
                   object-db (get-in context [::storage/storage :object-db])]
               (.begin api-tdb ReadWrite/READ)
               (-> context
                   (assoc-in [:request :lacinia-app-context :tdb]
                             api-tdb)
                   (assoc-in [:request :lacinia-app-context :object-db]
                             object-db))))
    :leave (fn [context]
             (.end (get-in context [::storage/storage :api-tdb]))
             context)
    :error (fn [context ex]
             (.end (get-in context [::storage/storage :api-tdb]))
             context)}))
