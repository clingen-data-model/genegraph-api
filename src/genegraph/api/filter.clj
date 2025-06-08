(ns genegraph.api.filter
  (:require [genegraph.framework.storage.rdf :as rdf]))

(defn filter-call->query-params [filters {:keys [filter select]
                                          :as filter-call}]
  ((:fn (get filters filter)) filter-call))

(defn compile-jena-query [union-filters]
  (rdf/create-query
   [:project ['x]
    (->> (:bgp union-filters)
         (cons :bgp)
         (into []))]))

(defn argument->kw [a]
  (-> a rdf/resource rdf/->kw))

(defn proposition-type-pattern-fn [{:keys [argument]}]
  [:bgp
   ['x :cg/subject 'proposition]
   ['proposition :rdf/type (argument->kw argument)]])

(defn copy-change-pattern-fn [{:keys [argument]}]
  [:bgp
   ['x :cg/subject 'proposition]
   ['proposition :cg/variant 'variant]
   ['variant :ga4gh/copyChange (argument->kw argument)]])

(defn assertion-direction-pattern-fn [{:keys [argument]}]
  [:bgp
   ['x :cg/direction (argument->kw argument)]])

(def dosage-sufficient-feature-pattern
  [['dosage_proposition :cg/feature 'feature]
   ['mechanism_assertion :cg/subject 'dosage_proposition]
   ['mechanism_assertion :cg/evidenceStrength :cg/DosageSufficientEvidence]])

(def haploinsufficiency-pattern
  (into
   []
   (concat [:bgp]
           dosage-sufficient-feature-pattern
           [['dosage_proposition :cg/mechanism :cg/Haploinsufficiency]])))

(def triplosensitivity-pattern
  (into
   []
   (concat [:bgp]
           dosage-sufficient-feature-pattern
           [['dosage_proposition :cg/mechanism :cg/Triplosensitivity]])))

(def gene-validity-moderate-and-greater
  [:filter
   [:in 'class :cg/Moderate :cg/Strong :cg/Definitive]
   [:bgp
    ['gv_prop :cg/gene 'feature]
    ['gv_assertion :cg/subject 'gv_prop]
    ['gv_assertion :cg/evidenceStrength 'class]]])

(def gene-validity-moderate-and-greater-ad-xl
  [:join
   gene-validity-moderate-and-greater
   [:filter
    [:in 'moi :hp/XLinkedInheritance :hp/AutosomalDominantInheritance]
    [:bgp
     ['gv_prop :cg/modeOfInheritance 'moi]]]])


(def gene-validity-moderate-and-greater-ar
  [:join
   gene-validity-moderate-and-greater
   [:filter
    [:in 'moi :hp/AutosomalRecessiveInheritance]
    [:bgp
     ['gv_prop :cg/modeOfInheritance 'moi]]]])

(def dosage-ar-linked
  [:bgp
   ['dosage_proposition :cg/feature 'feature]
   ['mechanism_assertion :cg/subject 'dosage_proposition]
   ['mechanism_assertion :cg/evidenceStrength :cg/DosageAutosomalRecessive]])

(def ar-gene
  [:union
   gene-validity-moderate-and-greater-ar
   dosage-ar-linked])

(def feature-set-name->bgp
  {"CG:HaploinsufficiencyFeatures" haploinsufficiency-pattern
   "CG:TriplosensitivityFeatures" triplosensitivity-pattern
   "CG:GeneValidityModerateAndGreater" gene-validity-moderate-and-greater
   "CG:GeneValidityModerateAndGreaterADXL" gene-validity-moderate-and-greater-ad-xl
   "CG:GeneValidityModerateAndGreaterAR" gene-validity-moderate-and-greater-ad-xl
   "CG:ARGene" ar-gene})

(defn feature-set-overlap-pattern
  [overlap-extent feature-set]
  (into
   []
   [:join
    [:bgp
     ['x :cg/subject 'proposition]
     ['proposition :cg/variant 'variant]
     ['variant overlap-extent 'feature]]
    (get feature-set-name->bgp (:argument feature-set))]))

(defn gene-count-min-pattern-fn [{:keys [argument]}]
  [:bgp
   ['x :cg/subject 'proposition]
   ['proposition :cg/variant 'variant]
   ['variant :cg/meetsCriteria (argument->kw argument)]])

(defn min-last-evaluted-date-pattern-fn [{:keys [argument]}]
  [:filter
   [:< argument 'date]
   [:bgp
    ['x :cg/dateLastEvaluated 'date]]])

;; TODO start here
(defn has-annotation [{:keys [argument]}]
  (let [base-pattern [:bgp
                      ['annotation :cg/subject 'x]
                      ['annotation :rdf/type :cg/AssertionAnnotation]]]
    (if argument
      (conj base-pattern ['annotation :cg/classification (argument->kw argument)])
      base-pattern)))

;; TODO Complete filters for other than proposition_type
;; Clean up legacy implementation

;; applies only to sequence features
;; will need alternate paths for
;; types of assertions other than gene validity
(defn has-assertion [{:keys [argument]}]
  [:bgp
   ['proposition :cg/gene 'x]
   ['assertion :cg/subject 'proposition]
   ['assertion :rdf/type :cg/EvidenceStrengthAssertion]])

(defn is-about-gene [{:keys [argument]}]
  [:bgp
   ['proposition :cg/gene (argument->kw argument)]
   ['x :cg/subject 'proposition]])

(defn is-about-gene-symbol [{:keys [argument]}]
  [:bgp
   ['gene :skos/prefLabel argument]
   ['proposition :cg/gene 'gene]
   ['x :cg/subject 'proposition]])

(defn is-obsolete [{:keys [argument]}]
  [:bgp ['x :prov/wasInvalidatedBy 'otherx]])

;; Using _ for filter names, these translate directly to GraphQL enums
;; so using snake-case to support javascript usage
(def filters
  {:is_obsolete {:pattern-fn is-obsolete
                 :description "Selects only assertions that have been invalidated by other assertions. Generally included for negation purposes, in order to select only current curations."
                 :domain :cg/EvidenceStrengthAssertion}
   :proposition_type {:pattern-fn proposition-type-pattern-fn
                      :description "Type of proposition referred to by the evidence level assertion. Types include CG:VariantPathogenicityProposition, CG:GeneValidityProposition, and CG:ConditionMechanismProposition"
                      :domain :cg/EvidenceStrengthAssertion}
   :copy_change {:pattern-fn copy-change-pattern-fn
                 :description "Copy change of the referred-to variant"
                 :domain :cg/EvidenceStrengthAssertion}
   :assertion_direction {:pattern-fn assertion-direction-pattern-fn
                         :description "Direction of the assertion. Requires assertion to be the subject type"
                         :domain :cg/EvidenceStrengthAssertion}
   :complete_overlap_with_feature_set
   {:pattern-fn (fn [feature-set]
                  (feature-set-overlap-pattern :cg/CompleteOverlap feature-set))
    :description "Assertion that has complete overlap with the given feature set. Valid arguments include CG:HaploinsufficiencyFeatures and CG:TriplosensitivityFeatures"
    :domain :cg/EvidenceStrengthAssertion}
   :partial_overlap_with_feature_set
   {:pattern-fn (fn [feature-set]
                  (feature-set-overlap-pattern :cg/PartialOverlap feature-set))
    :description "Assertion that has partial overlap with the given feature set. Valid arguments include CG:HaploinsufficiencyFeatures and CG:TriplosensitivityFeatures"
    :domain :cg/EvidenceStrengthAssertion}
   :gene_count_min
   {:pattern-fn gene-count-min-pattern-fn
    :description "Threshold for minimum number of gene features. Valid arguments are CG:Genes25, CG:Genes35, and CG:Genes50"
    :domain :cg/EvidenceStrengthAssertion}
   :date_evaluated_min
   {:pattern-fn min-last-evaluted-date-pattern-fn
    :description "Filter for classifications evaluated after the given date (in ISO format"
    :domain :cg/EvidenceStrengthAssertion}
   :has_annotation
   {:pattern-fn has-annotation
    :description "Filter for assertions that have had some annotation made on them."
    :domain :cg/EvidenceStrengthAssertion}
   :has_assertion
   {:pattern-fn has-assertion
    :description "Filter for sequence features that are the subject of an assertion."
    :domain :cg/SequenceFeature}
   :is_about_gene
   {:pattern-fn is-about-gene
    :description "Filter for assertions that are about a specific gene. Expect a gene IRI or CURIE as an argument."
    :domain :cg/EvidenceStrengthAssertion}
   :is_about_gene_symbol
   {:pattern-fn is-about-gene-symbol
    :description "Filter for assertions that are about a specific gene. Expect a gene symbol as an argument."
    :domain :cg/EvidenceStrengthAssertion}})

(defn filter-call->expr [filter-call]
  (let [pattern ((-> filter-call :filter filters :pattern-fn) filter-call)]
    (if (= :not_exists (:operation filter-call))
      [:not-exists pattern]
      [:exists pattern])))

(defn filters->op [pattern filter-calls]
  (into []
        (concat
         [:filter]
         (mapv filter-call->expr filter-calls)
         [pattern])))

(defn filtered-query->op [pattern filter-calls]
  [:project ['x]
   (filters->op pattern filter-calls)])

(defn compile-filter-query
  "Pass a BGP PATTERN, with associated filter calls with the form "
  [pattern filter-calls]
  (tap> (filtered-query->op pattern filter-calls))
  (rdf/create-query (filtered-query->op pattern filter-calls)))

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
