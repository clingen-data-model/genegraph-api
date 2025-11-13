(ns genegraph.api.graphql.schema.assertion
  (:require [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.event :as event]
            [genegraph.api.hybrid-resource :as hr]
            [genegraph.api.conflicts :as conflicts]
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

(defn related-assertions
  "Build a list of assertions that are similar enough to include in a list of related assertions. May be expanded to include arguments to filters. Currently only supports assertions on variant pathogenicity propositions."
  [context args value]
  (let [related-assertions-query (rdf/create-query "
select ?relatedAssertions where {
?sourceAssertion :cg/direction ?sourceDirection .
?sourceAssertion :cg/subject / :cg/variant ?sourceVariant .
?sourceVariant :cg/CompleteOverlap ?features ;
:ga4gh/copyChange ?copyChange .
?relatedVariants :cg/CompleteOverlap ?features ;
:ga4gh/copyChange ?copyChange .
?relatedPropositions :cg/variant ?relatedVariants .
?relatedAssertions :cg/subject ?relatedPropositions ;
:cg/direction ?relatedAssertionDirection .
FILTER (?sourceAssertion != ?relatedAssertions)
FILTER (?relatedAssertionDirection != ?sourceDirection)
}
")
        assertion-features-query
  (rdf/create-query "
select ?features where {
?sourceAssertion :cg/subject / :cg/variant / :cg/CompleteOverlap ?features .
}
")
        initial-set (reduce
                     (fn [m a] (assoc m
                                      a
                                      (assertion-features-query
                                       (:tdb context)
                                       {:sourceAssertion a})))
                     {}
                     (related-assertions-query
                      (:tdb context)
                      {:sourceAssertion value}))
        source-assertion-genes (assertion-features-query
                         (:tdb context)
                         {:sourceAssertion value})
        max-gene-count (count source-assertion-genes)]
    (->> initial-set
         (filter (fn [[assertion genes]]
                    (<= (count genes) max-gene-count)))
         (mapv key))))

(comment
  (let [tdb @(get-in genegraph.user/api-test-app
                     [:storage :api-tdb :instance])
        source (rdf/resource "https://identifiers.org/clinvar.submission:SCV000108561")
        p22q112 (rdf/resource "https://identifiers.org/clinvar.submission:SCV000080221")]
    (rdf/tx tdb
      (->> (related-assertions {:tdb tdb} nil p22q112)
           #_(into [])
           tap>)))

    (let [tdb @(get-in genegraph.user/api-test-app
                     [:storage :api-tdb :instance])
        source (rdf/resource "https://identifiers.org/clinvar.submission:SCV000108561")
        p22q112 (rdf/resource "https://identifiers.org/clinvar.submission:SCV000080221" tdb)]
    (rdf/tx tdb
      (->> (rdf/ld1-> p22q112 [:cg/direction]))))
  
  (let [tdb @(get-in genegraph.user/api-test-app
                     [:storage :api-tdb :instance])
        source (rdf/resource "https://identifiers.org/clinvar.submission:SCV000108561")]
    (rdf/tx tdb
      (->> (related-assertions-query tdb {:sourceAssertion source})
           (mapv (fn [a]
                   [(str a)
                    (count (assertion-features-query tdb {:sourceAssertion a}))]))
           (sort-by second)
           tap>
           #_count
           #_(mapv #(rdf/ld-> % [:rdf/type])))))
  )

;; Duplicates
;; VCV000441983.3
;; VCV000441984.3


(defn evidence-fn [context args value]
  (->> (rdf/ld-> value [:cg/evidence])
       (mapv #(hr/hybrid-resource % context))))

(defn versions-fn [context args value]
  (->> (rdf/ld-> value [:dc/isVersionOf [:dc/isVersionOf :<]])
       (mapv #(hr/hybrid-resource % context))))

(def evidence-def
  {:type '(list :Resource)
   :args {:recursive {:type 'Boolean} :type {:type 'String}}
   :resolve (fn [c a v] (evidence-fn c a v))})

(def strength-score-def
  {:type 'Float
   :description "Numeric score of the statement. May be nil, used only when the applicable criteria calls for a numeric score in the assertion or critera assessment."
   :path [:cg/strengthScore]})


(defn conflicting-assertions-fn [c a v]
  (conflicts/conflicting-assertions c a v))

(def assertion
  {:name :EvidenceStrengthAssertion
   :graphql-type :object
   :implements [:Resource]
   :fields {:conflictingAssertions
            {:type '(list :EvidenceStrengthAssertion)
             :resolve (fn [c a v] (conflicting-assertions-fn c a v))}
            :subject
            {:type :Resource
             :path [:cg/subject]}
            :strengthScore strength-score-def
            :annotations
            {:type '(list :AssertionAnnotation)
             :resolve (fn [context _ v]
                        (mapv #(hr/hybrid-resource % context)
                              (rdf/ld-> v [[:cg/subject :<]])))}
            :version
            {:type 'String
             :path [:cg/version]}
            :curationReasons
            {:type '(list :Resource)
             :path [:cg/curationReasons]}
            :curationReasonDescription
            {:type 'String
             :path [:cg/curationReasonDescription]}
            :classification
            {:type :Resource
             :path [:cg/classification]}
            :evidenceStrength
            {:type :Resource
             :path [:cg/evidenceStrength]}
            :evidence evidence-def
            :versions
            {:type '(list :EvidenceStrengthAssertion)
             :resolve (fn [c a v] (versions-fn c a v))}
            :relatedAssertions
            {:type '(list :EvidenceStrengthAssertion)
             :resolve (fn [c a v] (related-assertions c a v))}
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
            :specifiedBy {:type :Resource
                          :path [:cg/specifiedBy]}
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


(defn gv-disease [context args value]
  #_(rdf/ld1-> value [:cg/disease])
  (let [disease (rdf/ld1-> value [:cg/disease])]
    (if (rdf/ld1-> disease [:rdfs/label])
      disease
      (rdf/ld1-> disease [[:skos/exactMatch :<]]))))

(def gene-validity-proposition
  {:name :GeneValidityProposition
   :graphql-type :object
   :implements [:Resource]
   :fields {:gene {:type :SequenceFeature
                   :path [:cg/gene]}
            :disease {:type :Resource
                      #_#_:path [:cg/disease]
                      :resolve (fn [c a v] (gv-disease c a v))}
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


(def evidence-line
  {:name :EvidenceLine
   :graphql-type :object
   :description "An evidence line represents an independent and meaningful argument for or against a particular proposition, that is based on the interpretation of one or more pieces of information as evidence."
   :implements [:Resource]
   :fields {:evidence evidence-def
            :evidenceStrength {:type :Resource
                               :path [:cg/evidenceStrength]}
            :strengthScore strength-score-def
            :specifiedBy {:type :Resource
                          :path [:cg/specifiedBy]}}})
