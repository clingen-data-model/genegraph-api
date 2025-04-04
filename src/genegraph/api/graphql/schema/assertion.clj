(ns genegraph.api.graphql.schema.assertion
  (:require [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.event :as event]
            [genegraph.api.hybrid-resource :as hr]
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
   :implements [:Resource]
   :fields {:conflictingAssertions
            {:type '(list :EvidenceStrengthAssertion)
             :resolve (fn [_ _ v] (:conflictingAssertions v))}
            :subject
            {:type :Resource
             :path [:cg/subject]}
            :annotations
            {:type '(list :AssertionAnnotation)
             :resolve (fn [context _ v]
                        (mapv #(hr/hybrid-resource % context)
                              (rdf/ld-> v [[:cg/subject :<]])))}
            :classification
            {:type :Resource
             :path [:cg/classification]}
            :evidenceStrength
            {:type :Resource
             :path [:cg/evidenceStrength]}
            ;; :comments {}
            ;; :submitter
            ;; {:type :Resource
            ;;  :resolve (fn [{:keys [tdb]} _ v]
            ;;             (-> (:cg/contributions v)
            ;;                 first
            ;;                 :cg/agent
            ;;                 (rdf/resource tdb)))}
            
            :contributions
            {:type '(list :Contribution)
             :path [:cg/contributions]}
            
            :date {:type 'String
                   :resolve scv-date}
            :label {:type 'String
                    :resolve assertion-label}
            ;; :reviewStatus {}
            ;; :description {}
            }})

(defn proposition-assertions [context args value]
  (mapv
   #(hr/hybrid-resource % context)
   (rdf/ld-> value [[:cg/subject :<]])))

(def variant-pathogenicity-proposition
  {:name :VariantPathogenicityProposition
   :graphql-type :object
   :implements [:Resource]
   :fields {:variant {:type :CanonicalVariant
                      :path [:cg/variant]}
            :assertions {:type '(list :EvidenceStrengthAssertion)
                         :resolve (fn [c a v] (proposition-assertions c a v))}}})

(def genetic-condition-mechanism-proposition
  {:name :GeneticConditionMechanismProposition
   :graphql-type :object
   :description "Proposition that variation affecting a given feature is causative of a condition, specified or unspecified, with a particular mechanism of action (haploinsufficiency, triplosensitivity, etc), if available."
   :implements [:Resource]
   :fields {:feature {:type :SequenceFeature
                      :path [:cg/feature]}
            :mechanism {:type :Resource
                        :path [:cg/mechanism]}
            :condition {:type :Resource
                        :path [:cg/condition]}
            :assertions {:type '(list :EvidenceStrengthAssertion)
                         :resolve (fn [c a v] (proposition-assertions c a v))}}})


(def gene-validity-proposition
  {:name :GeneValidityProposition
   :graphql-type :object
   :implements [:Resource]
   :fields {:gene {:type :SequenceFeature
                   :path [:cg/gene]}
            :disease {:type :Resource
                      :path [:cg/disease]}
            :modeOfInheritance {:type :Resource
                                :path [:cg/modeOfInheritance]}
            :assertions {:type '(list :EvidenceStrengthAssertion)
                         :resolve (fn [c a v] (proposition-assertions c a v))}}})

(defn assertion-query-fn [context args value]
  (hr/hybrid-resource (:iri args) context))

(def assertion-query
  {:name :assertion
   :graphql-type :query
   :description "Query to find a single assertion in Genegraph."
   :type :EvidenceStrengthAssertion
   :args {:iri {:type 'String}}
   :resolve (fn [context args value] (assertion-query-fn context args value))})
