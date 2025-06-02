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

(def main-record-id-query
  (rdf/create-query "
select ?mainRecord where {
 ?assertion a :cg/EvidenceStrengthAssertion ;
 :dc/isVersionOf ?mainRecord . } "))

(def same-as-query
  (rdf/create-query "select ?x where { ?x :owl/sameAs ?y }"))

;; Jena methods mutate the model, will use this behavior 😱
(defn replace-hgnc-with-ncbi-gene-fn [event]
  (rdf/tx (get-in event [::storage/storage :api-tdb])
      (let [m (::event/data event)]
        (when-let [prop (first (prop-query m))]
          (let [hgnc-gene (rdf/ld1-> prop [:cg/gene])
                ncbi-gene (first (same-as-query
                                  (get-in event [::storage/storage :api-tdb])
                                  {:y hgnc-gene}))]
            (.remove m (rdf/construct-statement [prop :cg/gene hgnc-gene]))
            (.add m (rdf/construct-statement [prop :cg/gene ncbi-gene]))))))
  event)

(def replace-hgnc-with-ncbi-gene
  (interceptor/interceptor
   {:name ::replace-hgnc-with-ncbi-gene
    :enter (fn [e] (replace-hgnc-with-ncbi-gene-fn e))}))

(defn has-publish-action [m]
  (< 0 (count ((rdf/create-query "select ?x where { ?x :cg/role :cg/Publisher } ") m))))

(defn prop-iri [event]
  (-> event ::event/data prop-query first str))

(defn main-record-iri [event]
  (-> event ::event/data main-record-id-query first str))

(def assertion-query
  (rdf/create-query "
select ?assertion where {
?assertion a :cg/EvidenceStrengthAssertion .
}"))

(defn assertion-resource [event]
  (-> event ::event/data assertion-query first))

(defn assertion-iri [event]
  (-> event assertion-resource str))

(defn store-curation-fn [event]
  (if (has-publish-action (::event/data event))
    (-> event
        (event/store :api-tdb (main-record-iri event) (::event/data event))
        (event/store :object-db [:models (assertion-iri event)] (::event/data event))
        (event/store :object-db [:models (main-record-iri event)] (::event/data event)))
    (event/delete event :api-tdb (prop-iri event))))

(def construct-minimized-assertion-query
  (rdf/create-query "
construct {
 ?assertion ?p ?o ;
 :prov/wasInvalidatedBy ?newAssertion ;
 :dc/dateSubmitted ?pubdate ;
 :dc/dateAccepted ?approvalDate .
 
 ?proposition ?p1 ?o1 .

}
where {
 ?assertion ?p ?o ;
 a :cg/EvidenceStrengthAssertion .
 
 ?proposition ?p1 ?o1 ;
 a :cg/GeneValidityProposition .

 OPTIONAL {
  ?assertion :cg/contributions ?pubcontrib .
  ?pubcontrib :cg/role :cg/Publisher ;
  :cg/date ?pubdate .
 }
 OPTIONAL {
  ?assertion :cg/contributions ?approvalcontrib .
  ?approvalcontrib :cg/role :cg/Approver ;
  :cg/date ?approvalDate .
 }
 filter not exists { ?assertion :cg/contributions ?o }
 filter not exists { ?assertion :cg/evidence ?o }
}
"))

(def prior-version-query
  (rdf/create-query "
select ?x where {
?x :dc/isVersionOf ?priorVersion .
filter not exists { ?x :prov/wasInvalidatedBy ?newerx }
}
"))

(defn get-tdb [event]
  (get-in event [::storage/storage :api-tdb]))

#_(defn prior-version-model [event]
  (first
   (prior-version-query
    (get-tdb event)
    {:priorVersion (-> event
                       ::event/data
                       main-record-id-query
                       first)})))

#_(defn add-minimized-prior-version-fn [event]
  (let [tdb (get-tdb event)]
    (rdf/tx tdb
      (if-let [m (prior-version-model event)]
        (let [min-model (construct-minimized-assertion-query
                         (storage/read tdb (main-record-iri event)))]
          #_(println "prior version found")
          (event/store event
                       :api-tdb
                       (-> min-model assertion-query first str)
                       min-model))
        (do
          #_(println "prior version not found")
          event)))))

(defn prior-version-model [event]
  (let [m (storage/read (get-in event [::storage/storage :object-db])
                        [:models (main-record-iri event)])]
    (if (= ::storage/miss m)
      nil
      m)))

(defn add-minimized-prior-version-fn [event]
  (if-let [m (prior-version-model event)]
    (event/store event
                 :api-tdb
                 (assertion-iri event)
                 (construct-minimized-assertion-query m))
    event))

(def add-minimized-prior-version
  (interceptor/interceptor
   {:name ::add-minimized-prior-version
    :enter (fn [e] (add-minimized-prior-version-fn e))}))

(def await-genes
  (interceptor/interceptor
   {:name ::await-genes
    :enter (fn [e] (await-genes-fn e))}))

(def store-curation
  (interceptor/interceptor
   {:name ::store-curation
    :enter (fn [e] (store-curation-fn e))}))

(def import-gv-curations
  {:type :processor
   :subscribe :gene-validity-sepio
   :name :gene-validity-sepio-reader
   :backing-store :api-tdb
   :init-fn (init-await-genes ::import-gv-curations-await-genes)
   :interceptors [await-genes
                  replace-hgnc-with-ncbi-gene
                  add-minimized-prior-version
                  store-curation]})
