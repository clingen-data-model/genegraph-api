(ns genegraph.api.graphql.legacy-schema.affiliation
  (:require [genegraph.framework.storage.rdf :as rdf]
            [genegraph.api.graphql.common.curation :as curation]
            [clojure.string :as s]))

(def affiliation-query-without-text
  (rdf/create-query [:project ['affiliation]
                   (cons :bgp curation/gene-validity-with-sort-bgp)]))

(defn affiliations [context args value]
  (let [params (-> args (select-keys [:limit :offset :sort]) (assoc :distinct true))
        query-params {::rdf/params params}
        query affiliation-query-without-text
        result-count (query (:db context)
                            (assoc query-params 
                                   ::rdf/params
                                   {:type :count, :distinct :true})) ]
    {:agent_list (query (:db context) query-params)
     :count result-count}))

(defn gene-validity-assertions [context args value]
  (curation/gene-validity-curations-for-affiliation context args {:affiliation value}))

(defn affiliation-query [context args value]
  (rdf/resource (:iri args) (:db context)))
