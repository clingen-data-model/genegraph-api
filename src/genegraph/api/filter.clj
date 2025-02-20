(ns genegraph.api.filter
  (:require [genegraph.framework.storage.rdf :as rdf]))

(defn filter-call->query-params [filters {:keys [filter select]
                                          :as filter-call}]
  ((:fn (get filters filter)) filter-call))

(defn compile-jena-query [combined-filters]
  (rdf/create-query
   [:project ['x]
    (->> (:bgp combined-filters)
         (cons :bgp)
         (into []))]))

(defn proposition-type-filter-fn [{:keys [argument]}]
  {:bgp
   [['x :cg/subject 'proposition]
    ['proposition :rdf/type 'proposition_type]]
   :params {:proposition_type (rdf/resource argument)}})

(defn resource-type-filter-fn [{:keys [argument]}]
  {:bgp
   [['x :rdf/type 'resource_type]]
   :params {:resource_type (rdf/resource argument)}})

(defn copy-change-filter-fn [{:keys [argument]}]
  {:bgp
   [['proposition :cg/variant 'variant]
    ['variant :ga4gh/copyChange 'copy_change]]
   :params {:copy_change (rdf/resource argument)}})

(defn assertion-direction-fn [{:keys [argument]}]
  {:bgp [['x :cg/direction 'evidence_direction]]
   :params {:evidence_direction (rdf/resource argument)}})

(def dosage-sufficient-feature-pattern
  [['dosage_proposition :cg/feature 'feature]
   ['mechanism_assertion :cg/subject 'dosage_proposition]
   ['mechanism_assertion :cg/evidenceStrength :cg/DosageSufficientEvidence]])

(def haploinsufficiency-sufficient-feature-pattern
  (conj
   dosage-sufficient-feature-pattern
   ['dosage_proposition :cg/mechanism :cg/Haploinsufficiency]))

(def triplosensitivity-sufficient-feature-pattern
  (conj
   dosage-sufficient-feature-pattern
   ['dosage_proposition :cg/mechanism :cg/Triplosensitivity]))

(def feature-set-name->bgp
  {"CG:HaploinsufficiencyFeatures" haploinsufficiency-sufficient-feature-pattern
   "CG:TriplosensitivityFeatures" triplosensitivity-sufficient-feature-pattern})

;; TODO start here
;; Assumption is that 
(defn feature-set-overlap
  [overlap-extent feature-set]
  {:bgp (->>  [['variant overlap-extent 'feature]
               ['proposition :cg/variant 'variant]]
              (concat (get feature-set-name->bgp feature-set))
              (into []))
   :params {}})

(def filters
  {:proposition_type {:fn proposition-type-filter-fn
                      :description "Type of proposition referred to by the evidence level assertion. Types include CG:VariantPathogenicityProposition, CG:GeneValidityProposition, and CG:ConditionMechanismProposition"}
   :resource_type {:fn resource-type-filter-fn
                   :description "Type of resource to select. Mandatory for most queries. For curated knowledge assertions, use CG:EvidenceStrengthAssertion"}
   :copy_change {:fn copy-change-filter-fn
                 :description "Copy change of the referred-to variant"}
   :assertion_direction {:fn assertion-direction-fn
                         :description "Direction of the assertion. Requires assertion to be the subject type"}
   :complete_overlap_with_feature_set
   {:fn (fn [feature-set]
          (feature-set-overlap :cg/CompleteOverlap feature-set))
    :description "Assertion that has complete overlap with the given feature set. Valid arguments include CG:HaploinsufficiencyFeatures and CG:TriplosensitivityFeatures"}})


(defn combine-filters [{:keys [tdb]} filter-calls]
  (reduce
   (fn [m filter-call]
     (merge-with into
                 m
                 (filter-call->query-params filters filter-call)))
   {:bgp [] :params {}}
   filter-calls))

(defn apply-filters [context filter-calls]
  (let [combined-filters (combine-filters context filter-calls)
        query (compile-jena-query combined-filters)]
    (query (:tdb context) (assoc (:params combined-filters)
                                 ::rdf/params {:limit 200}))))

;; Consider auto-generating enumeration values with description text.


(comment
  (filter-call->query-params filters
                             {:filter :proposition_type
                              :param "CG:VariantPathogenicityProposition"})
  
  (let [tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])
        q (compile-jena-query
           :cg/EvidenceStrengthAssertion
           [(proposition-type-filter :cg/VariantPathogenicityProposition)])]
    (rdf/tx tdb
      (count (q tdb))))

  (let [context {:tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])
                 :filters filters}]
    (rdf/tx (:tdb context)
      (take 5
       (apply-filters context
                      [{:filter :resource_type
                        :param "CG:EvidenceStrengthAssertion"}
                       {:filter :proposition_type
                        :param "CG:VariantPathogenicityProposition"}]))))
  )
