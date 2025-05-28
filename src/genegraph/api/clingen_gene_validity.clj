(ns genegraph.api.clingen-gene-validity
  (:require [io.pedestal.interceptor :as interceptor]
            [io.pedestal.log :as log]
            [genegraph.framework.event :as event]
            [genegraph.framework.protocol :as p]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.storage.rdf :as rdf]))

(def genes-graph-name
  "https://www.genenames.org/")

(defn init-await-genes [listener-name]
  (fn [p]
    (let [genes-promise (promise)]
      (p/publish (get-in p [:topics :system])
                 {:type :register-listener
                  :name listener-name
                  :promise genes-promise
                  :predicate #(and (= :base-data (get-in % [::event/data :source]))
                                   (= genes-graph-name
                                      (get-in % [::event/data ::event/key])))})

      (assoc p
             ::event/metadata
             {::genes-promise genes-promise
              ::genes-atom (atom false)}))))

(defn graph-initialized? [e graph-name]
  (let [db (get-in e [::storage/storage :api-tdb])]
    (rdf/tx db
      (-> (storage/read db graph-name)
          .size
          (> 0)))))

(defn await-genes-fn [{:keys [::genes-promise ::genes-atom ::event/kafka-topic] :as e}]
  (when-not @genes-atom
    (while (not
            (or (graph-initialized? e genes-graph-name)
                (not= :timeout (deref genes-promise (* 1000 30) :timeout))))
      (log/info :fn ::await-genes-fn
                :msg "Awaiting genes load"
                :topic kafka-topic))
    (log/info :fn ::await-genes-fn :msg "Genes loaded")
    (reset! genes-atom true))
  e)


(def prop-query
  (rdf/create-query "select ?prop where { ?prop a :cg/GeneValidityProposition } "))

(def same-as-query
  (rdf/create-query "select ?x where { ?x :owl/sameAs ?y }"))

;; Jena methods mutate the model, will use this behavior 😱
(defn replace-hgnc-with-ncbi-gene-fn [event]
  (rdf/tx (get-in event [::storage/storage :api-tdb])
      (let [m (::event/data event)
            prop (first (prop-query m))
            hgnc-gene (rdf/ld1-> prop [:cg/gene])
            ncbi-gene (first (same-as-query (get-in event [::storage/storage :api-tdb])
                                            {:y hgnc-gene}))]
        (.remove m (rdf/construct-statement [prop :cg/gene hgnc-gene]))
        (.add m (rdf/construct-statement [prop :cg/gene ncbi-gene]))))
  event)

(def replace-hgnc-with-ncbi-gene
  (interceptor/interceptor
   {:name ::replace-hgnc-with-ncbi-gene
    :enter (fn [e] (replace-hgnc-with-ncbi-gene-fn e))}))

(defn has-publish-action [m]
  (< 0 (count ((rdf/create-query "select ?x where { ?x :cg/role :cg/Publisher } ") m))))

(defn prop-iri [event]
  (-> event ::event/data prop-query first str))

(defn store-curation-fn [event]
  (if (has-publish-action (::event/data event))
    (event/store event :api-tdb (prop-iri event) (::event/data event))
    (event/delete event :api-tdb (prop-iri event))))

(def store-curation
  (interceptor/interceptor
   {:name ::store-curation
    :enter (fn [e] (store-curation-fn e))}))
