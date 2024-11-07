(ns genegraph.api.graphql.schema.evidence-strength-assertion
  (:require [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.event :as event]
            [io.pedestal.log :as log]))

(defn assertion-label [{:keys [object-db]} _ v]
  (let [get-object #(storage/read object-db [:objects %])]
    (-> v
        :cg/subject
        get-object
        :cg/variant
        get-object
        :rdfs/label)))

(defn scv-date [{:keys [tdb]} _ v]
  (let [contribs (group-by :cg/role (:cg/contributions v))]
    (-> (some #(get contribs %)
           [:cg/Evaluator
            :cg/Submitter
            :cg/Creator])
        first
        :cg/date)))

(def assertion
  {:name :EvidenceStrengthAssertion
   :graphql-type :object
   :skip-type-resolution true
   :fields {:iri {:type 'String}
            
            :conflictingAssertions
            {:type '(list :GeneticConditionMechanismAssertion)
             :resolve (fn [_ _ v] (:conflictingAssertions v))}
            
            :classification
            {:type :Resource
             :resolve (fn [{:keys [tdb]} _ v]
                        (rdf/resource (:cg/classification v)
                                      tdb))}
            ;; :comments {}
            :submitter
            {:type :Resource
             :resolve (fn [{:keys [tdb]} _ v]
                        (-> (:cg/contributions v)
                            first
                            :cg/agent
                            (rdf/resource tdb)))}
            
            :date {:type 'String
                   :resolve scv-date}
            :label {:type 'String
                    :resolve assertion-label}
            ;; :reviewStatus {}
            ;; :description {}
            }})
