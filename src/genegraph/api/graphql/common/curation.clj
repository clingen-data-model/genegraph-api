(ns genegraph.api.graphql.common.curation
  (:require [genegraph.framework.storage.rdf :as rdf]
            [clojure.string :as s]
            [io.pedestal.log :as log])
  (:import [org.apache.jena.graph NodeFactory]))

(def gene-validity-bgp
  '[[validity_proposition :sepio/has-subject gene]
    [validity_proposition :sepio/has-object disease]
    [validity_proposition :rdf/type :sepio/GeneValidityProposition]])

(def actionability-bgp
  '[[actionability_genetic_condition :sepio/is-about-gene gene]
    [ac_report :sepio/is-about-condition actionability_genetic_condition]
    [ac_report :rdf/type :sepio/ActionabilityReport]
    [actionability_genetic_condition :rdfs/subClassOf disease]])

(def actionability-assertion-bgp
  '[[actionability_assertion :sepio/has-subject gene]
    [actionability_assertion :sepio/has-object disease]
    [actionability_assertion :rdf/type :sepio/ActionabilityAssertion]])

(def gene-dosage-bgp
  '[[dosage_report :iao/is-about gene]
    [gene :rdf/type :so/Gene]
    [dosage_report :rdf/type :sepio/GeneDosageReport]
    [dosage_report :bfo/has-part dosage_assertion]])

(def gene-dosage-disease-bgp
  '[[dosage_proposition :sepio/has-object disease]
    [dosage_assertion :sepio/has-subject dosage_proposition]
    [dosage_report :bfo/has-part dosage_assertion]
    [dosage_report :iao/is-about gene]
    [gene :rdf/type :so/Gene]
    [dosage_report :rdf/type :sepio/GeneDosageReport]])

(def curation-bgps
  [gene-validity-bgp
   actionability-bgp
   gene-dosage-disease-bgp])

(def pattern-curation-activities
  [[gene-validity-bgp :GENE_VALIDITY]
   [actionability-bgp :ACTIONABILITY]
   [gene-dosage-bgp :GENE_DOSAGE]])

;; This is pretty inefficient...
(def test-resource-for-activity
  (map (fn [[pattern activity]]
         [(rdf/create-query (cons :bgp pattern) {::rdf/type :ask}) activity])
       pattern-curation-activities))

(defn activities [model query-params]
  (reduce (fn [acc [test activity]]
            (if (seq (test model query-params)) 
              (conj acc activity)
              acc))
          #{}
          test-resource-for-activity))

(def gene-validity-disease-bgp
  '[[validity_proposition :sepio/has-object disease]
    [validity_proposition :rdf/type :sepio/GeneValidityProposition]])

(def actionability-disease-bgp
  '[[actionability_genetic_condition :rdfs/subClassOf disease]
    [actionability_genetic_condition :sepio/is-about-gene gene]
    [ac_report :sepio/is-about-condition actionability_genetic_condition]
    [ac_report :rdf/type :sepio/ActionabilityReport]])

(def pattern-disease-curation-activities
  [[gene-validity-disease-bgp :GENE_VALIDITY]
   [actionability-disease-bgp :ACTIONABILITY]
   [gene-dosage-disease-bgp :GENE_DOSAGE]])

(def test-disease-for-activity
  (map (fn [[pattern activity]]
         [(rdf/create-query (cons :bgp pattern) {::rdf/type :ask}) activity])
       pattern-disease-curation-activities))

(defn disease-activities [model query-params]
  (reduce (fn [acc [test activity]] 
            (if (seq (test model query-params)) 
              (conj acc activity)
              acc))
          #{}
          test-disease-for-activity))

(def union-of-all-curations
  (cons :union (map #(cons :bgp %) curation-bgps)))

(def gene-dosage-disease-gene-first-bgp
  '[[dosage_report :iao/is-about gene]
    [dosage_report :bfo/has-part dosage_assertion]
    [dosage_assertion :sepio/has-subject dosage_proposition]
    [dosage_proposition :sepio/has-object disease]
    [gene :rdf/type :so/Gene]
    [dosage_report :rdf/type :sepio/GeneDosageReport]])

(def union-of-all-curations-for-gene-list
  (cons :union (map #(cons :bgp %)
                    [gene-validity-bgp
                     actionability-bgp
                     gene-dosage-disease-gene-first-bgp])))

(def actionability-curations-for-genetic-condition
  (rdf/create-query [:project ['ac_report]
                     (cons :bgp actionability-bgp)]))



(def actionability-assertions-for-genetic-condition
  (rdf/create-query [:project ['actionability_assertion]
                     (cons :bgp actionability-assertion-bgp)]))

(def gene-validity-with-sort-bgp
  (conj gene-validity-bgp
        ['validity_assertion :sepio/has-subject 'validity_proposition]
        ['gene :skos/prefLabel 'gene_label]
        ['disease :rdfs/label 'disease_label]
        ['validity_assertion :sepio/qualified-contribution 'gv_contrib]
        ['gv_contrib :bfo/realizes 'role]
        ['gv_contrib :sepio/has-agent 'affiliation]
        ))

(defn text-search-bgp
  "Produce a BGP fragment for performing a text search based on a resource.
  Will produce a list of properties matching 'text', which may be either a
  property or a variable.

  A complete query using this function could be composed like this:
  (create-query [:project ['x] (cons :bgp (text-search-bgp 'x :cg/resource 'text))])

  where x is a resource to return, and text is a variable expected to be bound to the
  text to search for"
  [resource property text]
  (let [node0 (symbol "text0")
        node1 (symbol "text1")
        rdf-first (NodeFactory/createURI "http://www.w3.org/1999/02/22-rdf-syntax-ns#first")
        rdf-rest (NodeFactory/createURI "http://www.w3.org/1999/02/22-rdf-syntax-ns#rest")]
    [[resource (NodeFactory/createURI "http://jena.apache.org/text#query") node0]
     [node0 rdf-first property]
     [node0 rdf-rest node1]
     [node1 rdf-first text]
     [node1 rdf-rest
      (NodeFactory/createURI "http://www.w3.org/1999/02/22-rdf-syntax-ns#nil")]]))

(def gene-validity-text-search-bgp
  (cons :union (map #(cons :bgp (concat (text-search-bgp % :cg/resource 'text)
                                        gene-validity-with-sort-bgp))
                    ['gene 'disease 'validity_assertion])))

(def gene-validity-curations-text-search
  (rdf/create-query [:project ['validity_assertion]
                 gene-validity-text-search-bgp]))

(def gene-validity-curations
  (rdf/create-query [:project ['validity_assertion]
                 (cons :bgp gene-validity-with-sort-bgp)]))

(def dosage-sensitivity-curations-for-genetic-condition
  (rdf/create-query [:project ['dosage_assertion]
                 (cons :bgp gene-dosage-disease-bgp)]))

(def curated-diseases-for-gene
  (rdf/create-query [:project ['disease]
                 union-of-all-curations-for-gene-list]))

(defn curated-genetic-conditions-for-gene [model query-params]
  (map #(array-map :gene (:gene query-params) :disease %) 
       (remove #(= (rdf/resource :mondo/Disease) %)
               (curated-diseases-for-gene model query-params))))

(def curated-genes-for-disease
  (rdf/create-query [:project ['gene]
                 union-of-all-curations]))

(defn curated-genetic-conditions-for-disease [model query-params]
  (map #(array-map :disease (:disease query-params) :gene %)
       (curated-genes-for-disease model query-params)))

(def role-map
  {:APPROVER :sepio/ApproverRole
   :SECONDARY_CONTRIBUTOR :sepio/SecondaryContributorRole})

(defn- add-role-to-params [params]
  (case (:role params)
    :ANY (dissoc params :role)
    nil (assoc params :role (rdf/resource :sepio/ApproverRole))
    (assoc params :role (rdf/resource (role-map (:role params))))))

(defn- add-text-to-params [params]
  (if (string? (:text params))
    (assoc params :text (s/lower-case (:text params)))
    (dissoc params :text)))

(defn gene-validity-curations-for-resolver
  "Method to be called by resolvers desiring a list of gene validity curations
  with limit, sort and offset, including a total count field. Value should be a map and
  will be merged into the query parameters, limiting the result to curations that match
  the given argument"
  [context args value]
  (let [model (:db context)
        params (-> args
                   (select-keys [:limit :offset :sort])
                   (assoc :distinct true))
        query-params (-> (select-keys args [:role])
                         (merge value)
                         add-role-to-params
                         add-text-to-params
                         (assoc ::rdf/params params))
        query (if (:text args)
                gene-validity-curations-text-search
                gene-validity-curations)
        count (query model (assoc query-params ::rdf/params {:type :count}))]
    {:curation_list (query model query-params)
     :count count}))

(def gene-validity-affiliation-query
  (rdf/create-query
   '[:project [validity_assertion]
     [:bgp 
      [gv_contrib :sepio/has-agent affiliation]
      [gv_contrib :bfo/realizes role]
      [validity_assertion :sepio/qualified-contribution gv_contrib]
      [validity_assertion :sepio/has-subject validity_proposition]
      [validity_proposition :sepio/has-subject gene]
      [validity_proposition :sepio/has-object disease]
      [validity_proposition :rdf/type :sepio/GeneValidityProposition]
      [gene :skos/prefLabel gene_label]
      [disease :rdfs/label disease_label]]]))

(defn gene-validity-curations-for-affiliation
  "Optimized for listing associated with affiliations"
  [context args value]
  (let [model (:db context)
        params (-> args
                   (select-keys [:limit :offset :sort])
                   (assoc :distinct true))
        query-params (-> (select-keys args [:role])
                         (merge value)
                         add-role-to-params
                         add-text-to-params
                         (assoc ::rdf/params params))
        count (gene-validity-affiliation-query model (assoc query-params ::rdf/params {:type :count}))]
    {:curation_list (gene-validity-affiliation-query model query-params)
     :count count}))

(def validity-curated-genes
  (rdf/create-query [:project ['gene]
                     (cons :bgp gene-validity-with-sort-bgp)]))

(def validity-curated-genes-text-search
  (rdf/create-query [:project ['gene]
                 (cons :bgp
                       (concat (text-search-bgp 'gene :cg/resource 'text)
                               gene-validity-with-sort-bgp))]))

(defn validity-curated-genes-for-resolver
  "Method to be called by resolvers desiring a list of genes with limit, sort and offset,
  including a total count field. Value should be a map and will be merged into the query
  parameters, limiting the result to curations that match
  the given argument"
  [args value]
  (let [params (-> args (select-keys [:limit :offset :sort]) (assoc :distinct true))
        query-params (-> (if (string? (:text args))
                           {:text (s/lower-case (:text args))}
                           {})
                         (assoc ::rdf/params params)
                         (merge value))
        query (if (:text args)
                validity-curated-genes-text-search
                validity-curated-genes)
        count (query (assoc query-params ::rdf/params {:type :count}))]
    {:gene_list (query query-params)
     :count count}))

(def validity-curated-diseases
  (rdf/create-query [:project ['disease]
                 (cons :bgp gene-validity-with-sort-bgp)]))

(def validity-curated-diseases-text-search
  (rdf/create-query [:project ['disease]
                 (cons :bgp
                       (concat (text-search-bgp 'disease :cg/resource 'text)
                               gene-validity-with-sort-bgp))]))

(defn validity-curated-diseases-for-resolver
  "Method to be called by resolvers desiring a list of diseases with limit, sort and offset,
  including a total count field. Value should be a map and will be merged into the query
  parameters, limiting the result to curations that match
  the given argument"
  [args value]
  (let [params (-> args (select-keys [:limit :offset :sort]) (assoc :distinct true))
        query-params (-> (if (string? (:text args))
                           {:text (s/lower-case (:text args))}
                           {})
                         (assoc ::rdf/params params)
                         (merge value))
        query (if (:text args)
                validity-curated-diseases-text-search
                validity-curated-diseases)
        count (query (assoc query-params ::rdf/params {:type :count}))]
    {:disease_list (query query-params)
     :count count}))

(defn genes-for-resolver
  "Method to be called by resolvers desiring a list of genes with limit, sort and offset,
  including a total count field. Value should be a map and will be merged into the query
  parameters, limiting the result to curations that match
  the given argument"
  [context args value]
  (let [model (:db context)
        params (-> args (select-keys [:limit :offset :sort]) (assoc :distinct true))
        query-params (-> (if (string? (:text args))
                           {:text (s/lower-case (:text args))}
                           {})
                         (assoc ::rdf/params params)
                         (merge value))
        gene-bgp '[[gene :rdf/type :so/Gene]
                   [gene :skos/prefLabel gene_label]]
        base-bgp (if (:text args)
                   (concat (text-search-bgp 'gene :cg/resource 'text) gene-bgp)
                   gene-bgp)
        selected-curation-type-bgp (case (:curation_activity args)
                                     :GENE_VALIDITY gene-validity-bgp
                                     :ACTIONABILITY actionability-bgp
                                     :GENE_DOSAGE gene-dosage-bgp
                                     [])
        bgp (if (= :ALL (:curation_activity args))
              [:union 
               (cons :bgp (concat base-bgp gene-validity-bgp))
               (cons :bgp (concat base-bgp actionability-bgp))
               (cons :bgp (concat base-bgp gene-dosage-bgp))]
              (cons :bgp
                    (concat base-bgp
                            selected-curation-type-bgp)))
        query (rdf/create-query [:project 
                                 ['gene]
                                 bgp])
        result-count (query model (assoc query-params ::rdf/params {:type :count}))]
    {:gene_list (query model query-params)
     :count result-count}))

(defn diseases-for-resolver 
  "Method to be called by resolvers desiring a list of diseases with limit, sort and offset,
  including a total count field. Value should be a map and will be merged into the query
  parameters, limiting the result to curations that match
  the given argument"
  [context args value]
  (let [model (:db context)
        params (-> args (select-keys [:limit :offset :sort]) (assoc :distinct true))
        query-params (if (:text args)
                       {:text (-> args :text s/lower-case) ::rdf/params params}
                       {::rdf/params params})
        selected-curation-type-bgp (case (:curation_activity args)
                                     :GENE_VALIDITY gene-validity-bgp
                                     :ACTIONABILITY actionability-bgp
                                     :GENE_DOSAGE gene-dosage-disease-bgp
                                     nil)
        bgp (if (= :ALL (:curation_activity args))
              [:union 
               (cons :bgp (conj gene-validity-bgp 
                                '[disease :rdfs/label disease_label]))
               (cons :bgp (conj actionability-bgp
                                '[disease :rdfs/label disease_label]))
               (cons :bgp (conj gene-dosage-disease-bgp
                                '[disease :rdfs/label disease_label]))]
              (when (some? selected-curation-type-bgp)
                (cons :bgp (conj selected-curation-type-bgp
                                 '[disease :rdfs/label disease_label]))))
        query-bgp (if (:text args) 
                    [:join (cons :bgp (text-search-bgp 'disease :cg/resource 'text)) bgp]
                    bgp)
        query (if (some? bgp)
                (rdf/create-query [:project 
                             ['disease]
                               query-bgp])
                ;; Consider restructuring this around a BGP when variable length
                ;; predicates are supported in the algebra, is messy as written.
                (if (:text args)
                  (rdf/create-query 
                   (str "select ?s WHERE { "
                        "?s :jena/query ( :cg/resource ?text ) . "
                        "?s <http://www.w3.org/2000/01/rdf-schema#subClassOf>* "
                        "<http://purl.obolibrary.org/obo/MONDO_0000001> . "
                        "?s :rdfs/label ?disease_label . "
                        "FILTER (!isBlank(?s)) }"))
                  (rdf/create-query 
                   (str "select ?s WHERE { ?s <http://www.w3.org/2000/01/rdf-schema#subClassOf>* "
                        "<http://purl.obolibrary.org/obo/MONDO_0000001> . "
                        "?s :rdfs/label ?disease_label . "
                        "FILTER (!isBlank(?s)) }"))))
        result-count (query model (assoc query-params ::rdf/params {:type :count}))]
    {:disease_list (query model query-params)
     :count result-count}))


(def evaluation-criteria
  (rdf/create-query 
   "select distinct ?criteria where 
{ ?criteria_type <http://www.w3.org/2000/01/rdf-schema#subClassOf>* <http://purl.obolibrary.org/obo/SEPIO_0000037> .
  ?criteria a ?criteria_type . }"))

(def classifications
  (rdf/create-query
   "select distinct ?classification where 
{ ?assertion_type <http://www.w3.org/2000/01/rdf-schema#subClassOf>* <http://purl.obolibrary.org/obo/SEPIO_0000001> .
  ?assertion a ?assertion_type .
  ?assertion :sepio/has-object ?classification . }"))
