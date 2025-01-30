(ns genegraph.api.base.clinvar
  (:require [clojure.data.xml :as xml]
            [clojure.data.zip.xml :as xml-zip]
            [clojure.zip :as zip]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.set :as set]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.storage.rocksdb :as rocksdb]
            [genegraph.framework.id :as id]
            [genegraph.api.protocol :as ap]
            [genegraph.api.sequence-index :as idx]
            [io.pedestal.log :as log]
            [hato.client :as hc]
            [genegraph.api.spec.ga4gh])
  (:import [org.apache.jena.rdf.model Model ModelFactory]
           [java.util.zip GZIPInputStream]))

(def variant-type->efo-term
  {"Deletion" :efo/copy-number-loss
   "copy number loss" :efo/copy-number-loss
   "copy number gain" :efo/copy-number-gain
   "Duplication" :efo/copy-number-gain})

(def copy-number-types (set (keys variant-type->efo-term)))

(-> (System/getProperties) (.setProperty "jdk.xml.totalEntitySizeLimit" "500000000"))

(defn update-int-vals [m ks]
  (reduce (fn [m1 k]
            (update m1 k #(if % (Long/parseLong %) nil)))
          m
          ks))

(defn xml-attrs->map [n attrs]
  (reduce (fn [m a] (assoc m a (xml-zip/attr n a))) {} attrs))

(defn clinvar-zip-node->allele-locations [z]
  (mapv #(-> (xml-attrs->map %
                            [:forDisplay
                             :Assembly
                             :Chr
                             :Accession
                             :outerStart
                             :innerStart
                             :start
                             :stop
                             :innerStop
                             :outerStop
                             :display_start
                             :display_stop
                             :Strand
                             :variantLength
                             :referenceAllele
                             :alternateAllele
                             :AssemblyAccessionVersion
                             :AssemblyStatus
                             :positionVCF
                             :referenceAlleleVCF
                             :alternateAlleleVCF
                             :forDisplayLength])
             (update-int-vals [:outerStart
                               :innerStart
                               :start
                               :stop
                               :innerStop
                               :outerStop
                               :display_start
                               :display_stop
                               :variantLength
                               :positionVCF
                               :forDisplayLength]))
        (xml-zip/xml-> z :Location :SequenceLocation)))

(defn clinvar-zip-node->hgvs-list [z]
  (mapv (fn [z1]
          (when-let [expr (xml-zip/xml1-> z1 :NucleotideExpression)]
            (assoc
             (xml-attrs->map expr
                             [:sequenceAccessionVersion
                              :sequenceAccession
                              :sequenceVersion
                              :change
                              :MANESelect])
             :expression (xml-zip/xml1-> z1
                                         :NucleotideExpression
                                         :Expression
                                         xml-zip/text))))
        (xml-zip/xml-> z :HGVSlist :HGVS)))

;; TODO consider haplotypes, non-simple alleles
(defn clinvar-zip-node->allele [z]
  (if-let [sa (xml-zip/xml1-> z :ClassifiedRecord :SimpleAllele)]
    (update-int-vals
     {:allele-id (xml-zip/attr sa :AlleleID)
      :variation-id (xml-zip/attr sa :VariationID)
      :variant-type (xml-zip/xml1-> sa :VariantType xml-zip/text)
      :spdi (xml-zip/xml1-> sa :CanonicalSPDI xml-zip/text)
      :genes (vec (xml-zip/xml-> sa :GeneList :Gene (xml-zip/attr :GeneID)))
      :name (xml-zip/xml1-> sa :Name xml-zip/text)
      :location (clinvar-zip-node->allele-locations sa)
      :hgvs (clinvar-zip-node->hgvs-list sa)
      :copy-count (xml-zip/xml1-> z
                                  :ClassifiedRecord
                                  :ClinicalAssertionList
                                  :ClinicalAssertion
                                  :SimpleAllele
                                  :AttributeSet
                                  :Attribute
                                  (xml-zip/attr= :Type "AbsoluteCopyNumber")
                                  xml-zip/text)}
     [:copy-count])
    {}))

(defn classification-allele->allele [z]
  (if-let [sa (xml-zip/xml1-> z :SimpleAllele)]
    {:allele-id (xml-zip/attr sa :AlleleID)
     :variation-id (xml-zip/attr sa :VariationID)
     :variant-type (xml-zip/xml1-> sa :VariantType xml-zip/text)
     :spdi (xml-zip/xml1-> sa :CanonicalSPDI xml-zip/text)
     :genes (vec (xml-zip/xml-> sa :GeneList :Gene (xml-zip/attr :GeneID)))
     :name (xml-zip/xml1-> sa :Name xml-zip/text)
     :location (clinvar-zip-node->allele-locations sa)
     :hgvs (xml-zip/xml-> sa
                          :AttributeSet
                          :Attribute
                          (xml-zip/attr= :Type "HGVS")
                          xml-zip/text)}
    {}))

(defn clinvar-zip-node->classifications [n]
  (mapv
   (fn [n1]
     (assoc (xml-attrs->map
             (xml-zip/xml1-> n1 :ClinVarAccession)
             [:Accession
              :DateUpdated
              :DateCreated
              :Type
              :Version
              :SubmitterName
              :OrgID
              :OrganizationCategory])
            :SimpleAllele (classification-allele->allele n1)
            :DateLastEvaluated (xml-zip/xml1->
                                n1
                                :Classification
                                (xml-zip/attr :DateLastEvaluated))
            :ReviewStatus (xml-zip/xml1->
                           n1
                           :Classification
                           :ReviewStatus
                           xml-zip/text)
            :Comment (xml-zip/xml1->
                      n1
                      :Classification
                      :Comment
                      xml-zip/text)
            :Classification (xml-zip/xml1->
                             n1
                             :Classification
                             :GermlineClassification
                             xml-zip/text)
            :Assertion  (xml-zip/xml1->
                         n1
                         :Assertion
                         xml-zip/text)
            :SubmissionComment (xml-zip/xml-> n1
                                              :Comment
                                              xml-zip/text)))
   (xml-zip/xml-> n
                  :ClassifiedRecord
                  :ClinicalAssertionList
                  :ClinicalAssertion)))

(defn clinvar-xml->intermediate-model [xml-node]
  (let [z (zip/xml-zip xml-node)]
    (assoc (clinvar-zip-node->allele z)
           :classifications (clinvar-zip-node->classifications z))))

(defn variation-length [{:keys [location]}]
  (if (seq location)
    (apply max
           (mapv
            (fn [{:keys [display_start display_stop]}]
              (if (and display_start display_stop)
                (- display_stop display_start)
                -1))
            location))
    -1))

(def cnv-types
  #{"Deletion"
    "Duplication"
    "copy number gain"
    "copy number loss"})

(defn is-cnv? [variant]
  (and
   (cnv-types (:variant-type variant))
   (or (:copy-count variant)
       (< 1000 (variation-length variant)))))



;;placeholder for now
(defmethod rdf/as-model :genegraph.api.base/clinvar [{:keys [source]}]
  (log/info :fn ::rdf/as-model :format :genegraph.api.base/clinvar)
  (ModelFactory/createDefaultModel))


(defn get-clinvar-variants [variants http-client]
  (:body
   (hc/get (str "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?db=clinvar&rettype=vcv&is_variationid&id="
                (str/join "," variants)
                "&from_esearch=true")
           {:http-client http-client})))

(defn clinvar-loc->ga4gh-loc [{:keys [Accession
                                      display_start
                                      display_stop
                                      innerStart
                                      outerStart
                                      innerStop
                                      outerStop
                                      start
                                      stop]}]
  (let [loc {:ga4gh/sequenceReference (str "https://identifiers.org/refseq:"
                                           Accession)
             :ga4gh/start (if (or innerStart outerStart)
                            [outerStart innerStart]
                            (or start display_start))
             :ga4gh/end   (if (or innerStop outerStop)
                            [innerStop outerStop]
                            (or stop display_stop))
             :type :ga4gh/SequenceLocation}]
    (assoc loc :iri (id/iri loc))))




(defn clinvar-variant->ga4gh-alleles [{:keys [location
                                              variant-type
                                              hgvs
                                              spdi]
                                       :as clinvar-variant}]
  (mapv (fn [l]
          (let [hgvs-expressions
                (mapv (fn [v]{:type :ga4gh/Expression
                              :ga4gh/syntax "HGVS"
                              :ga4gh/value (if (map? v)
                                             (:expression v)
                                             v)})
                      hgvs)
                expressions (if spdi
                              (conj
                               hgvs-expressions
                               {:type :ga4gh/Expression
                                :ga4gh/syntax "SPDI"
                                :ga4gh/value spdi})
                              hgvs-expressions)

                vrs-allele
                {:type :ga4gh/CopyNumberChange
                 :ga4gh/copyChange (variant-type->efo-term
                                    variant-type)
                 :ga4gh/location (clinvar-loc->ga4gh-loc l)
                 :ga4gh/expressions expressions}]
            (assoc vrs-allele :iri (id/iri vrs-allele))))
        location))

;; classifications
#_{"association" 20,
   "risk factor" 19,
   "Likely pathogenic" 4476,            ; X
   "Likely pathogenic, low penetrance" 3,
   "Uncertain significance" 32984,      ; X
   "Likely benign" 4773,                ; X
   "probable-pathogenic" 2,
   "pathologic" 24,
   "conflicting data from submitters" 192, ; ?
   "Likely Pathogenic" 2,
   "Affects" 2,
   "pathogenic" 1,
   "protective" 1,
   "Pathogenic" 23540,                  ; X
   "other" 1,
   "not provided" 1032,
   "drug response" 25,
   "Benign/Likely benign" 195,          ; X
   "Benign" 4620,                       ; X
   "Pathogenic, low penetrance" 6,
   "Uncertain risk allele" 1, 
   "Pathogenic/Likely pathogenic" 8     ; X
   }



(def clinvar-class->acmg-class 
  {"Likely pathogenic" :cg/LikelyPathogenic
   "Uncertain significance" :cg/UncertainSignificance
   "Likely benign" :cg/LikelyBenign
   "probable-pathogenic" :cg/LikelyPathogenic
   "pathologic" :cg/Pathogenic
   "conflicting data from submitters" :cg/ConflictingData
   "Likely Pathogenic" :cg/LikelyPathogenic
   "pathogenic" :cg/Pathogenic
   "Pathogenic" :cg/Pathogenic
   "Benign/Likely benign" :cg/Benign
   "Benign" :cg/Benign
   "Pathogenic/Likely pathogenic" :cg/Pathogenic })

;; review status
#_{"no assertion criteria provided" 34607,
   "criteria provided, single submitter" 35753,
   "no classification provided" 1028,
   "reviewed by expert panel" 126, 
   "flagged submission" 203}

(def clinvar-status->cg-status
  {"no assertion criteria provided" :cg/NoCriteria
   "criteria provided, single submitter" :cg/CriteriaProvided
   "no classification provided" :cg/NoClassification
   "reviewed by expert panel" :cg/ExpertPanel
   "flagged submission" :cg/Flagged})

(def acmg-class->evidence-direction
  {:cg/Pathogenic :cg/Supports
   :cg/LikelyPathogenic :cg/Supports
   :cg/UncertainSignificance :cg/Inconclusive
   :cg/LikelyBenign :cg/Refutes
   :cg/Benign :cg/Refutes})

(defn clinvar-variant->canonical-var [v]
  (let [base
        {:iri (str "https://identifiers.org/clinvar:" (:variation-id v))
         :type :cg/CanonicalVariant
         :rdfs/label (:name v)
         :cg/includedVariants (clinvar-variant->ga4gh-alleles v)}]
    (if-let [copy-change (variant-type->efo-term (:variant-type v))]
      (assoc base :ga4gh/copyChange copy-change)
      base)))

(defn variant-pathogenicity-proposition [v]
  (let [prop {:type :cg/VariantPathogenicityProposition
              :cg/variant (:iri v)
              :cg/condition :mondo/HereditaryDisease}]
    (assoc prop :iri (id/iri prop))))

(defn scv-contributions [scv]
  (let [role-mapping {:DateCreated :cg/Creator
                      :DateLastEvaluated :cg/Evaluator
                      :DateUpdated :cg/Submitter}
        scv-agent (str "https://identifiers.org/clinvar.submitter:"
                       (:OrgID scv))]
    (->> (select-keys scv [:DateCreated :DateLastEvaluated :DateUpdated])
         (remove #(nil? (val %)))
         (mapv (fn [[k v]]
                 {:type :cg/Contribution
                  :cg/agent scv-agent
                  :cg/role (get role-mapping k)
                  :cg/date v})))))

(defn clinvar-scv->strength-assertion
  [prop-iri {:keys [Accession
                    Classification
                    SubmissionComment
                    Comment
                    ReviewStatus
                    Version
                    SimpleAllele]
             :as scv}]
  (let [classification (get clinvar-class->acmg-class
                            Classification
                            :cg/OtherClassification)]
    {:type :cg/EvidenceStrengthAssertion
     :iri (str "https://identifiers.org/clinvar.submission:"
               Accession)
     :cg/subject prop-iri
     :cg/classification classification
     :dc/description Comment
     :cg/comments (into [] SubmissionComment)
     :cg/contributions (scv-contributions scv)
     :cg/direction (get acmg-class->evidence-direction
                        classification
                        :cg/Inconclusive)
     :cg/reviewStatus (get clinvar-status->cg-status
                           ReviewStatus
                           :cg/OtherStatus)
     :cg/submittedVariant (if SimpleAllele ; TODO pick up here
                            (clinvar-variant->ga4gh-alleles SimpleAllele)
                            nil)
     :cg/version Version}))

(defn clinvar-variant->ga4gh [{:keys [classifications
                                      name
                                      variant-type
                                      location
                                      classifications]
                               :as clinvar-variant}]
  (let [variant (clinvar-variant->canonical-var clinvar-variant)
        prop (variant-pathogenicity-proposition variant)
        ->assertions #(clinvar-scv->strength-assertion (:iri prop) %)]
    (conj (mapv ->assertions classifications)
          variant
          prop)))


(defn attrs->statements [attrs]
  (let [iri (:iri attrs)]
    (mapv
     (fn [[k v]] [iri k (rdf/resource v)])
     (set/rename-keys (dissoc attrs :iri)
                      {:type :rdf/type}))))

(defn canonical-variant->statements [cv]
  (attrs->statements
   (select-keys cv [:iri :type :ga4gh/copyChange])))

(defn assertion->statements [assertion]
  (attrs->statements
   (select-keys assertion
                [:iri
                 :type
                 :cg/subject
                 :cg/direction
                 :cg/classification
                 :cg/reviewStatus])))

(defn clinvar-variant->statements [clinvar-variant]
  (concat
   (mapcat canonical-variant->statements
           (filter #(= :cg/CanonicalVariant
                       (:type %))
                   clinvar-variant))
   (mapcat attrs->statements
           (filter #(= :cg/VariantPathogenicityProposition
                       (:type %))
                   clinvar-variant))
   (mapcat assertion->statements
           (filter #(= :cg/EvidenceStrengthAssertion
                       (:type %))
                   clinvar-variant))))

(defn variant->statements-and-objects [cv-xml]
  (let [objects (clinvar-variant->ga4gh cv-xml)]
    {:objects objects
     :statements (clinvar-variant->statements objects)}))

(defn gene-overlaps-for-location [db location]
  (->> (mapcat
        #(rocksdb/range-get
          db
          (idx/location->search-params location %))
        [:so/Gene :cg/DosageRegion])
       (map :iri)
       set))

(defn gene-ids-for-bundle [db variant-bundle]
  (let [loc-overlaps #(gene-overlaps-for-location db %)]
    (->> (:objects variant-bundle)
         (filter #(= :cg/CanonicalVariant (:type %)))
         (mapcat :cg/includedVariants)
         (mapv :ga4gh/location)
         (mapv loc-overlaps)
         (reduce set/union))))

(defn max-coord [coord-or-range]
  (if (vector? coord-or-range)
    (second coord-or-range)
    coord-or-range))

(defn min-coord [coord-or-range]
  (if (vector? coord-or-range)
    (first coord-or-range)
    coord-or-range))

;; question for group, how to handle outer overlaps
(defn outer-overlap? [loc1 loc2]
    (if (some vector? [(:ga4gh/start loc1)
                       (:ga4gh/start loc2)
                       (:ga4gh/end loc1)
                       (:ga4gh/end loc2)])
      (let [loc1-start (min-coord (:ga4gh/start loc1))
            loc2-start (min-coord (:ga4gh/start loc2))
            loc1-end (max-coord (:ga4gh/end loc1))
            loc2-end (max-coord (:ga4gh/end loc2))
            all-coords [loc1-start loc2-start loc1-end loc2-end]]
        (if (or (some nil? all-coords)
                (< loc1-end loc2-start)
                (< loc2-end loc1-start))
          :cg/NoOverlap
          :cg/OuterOverlap))
      :cg/NoOverlap))

(defn overlap-type [loc1 loc2]
  (let [loc1-start (max-coord (:ga4gh/start loc1))
        loc2-start (max-coord (:ga4gh/start loc2))
        loc1-end (min-coord (:ga4gh/end loc1))
        loc2-end (min-coord (:ga4gh/end loc2))
        all-coords [loc1-start loc2-start loc1-end loc2-end]]
    (cond
      (some nil? all-coords) :cg/NoOverlap
      (and (< loc1-start loc2-start)
           (< loc2-end loc1-end)) :cg/CompleteOverlap
      (or (and (< loc1-start loc2-start)
               (< loc2-start loc1-end))
          (and (< loc2-end loc1-end)
               (< loc2-start loc2-end))) :cg/PartialOverlap
      :default (outer-overlap? loc1 loc2))))

(comment
  ;; complete 
  (overlap-type
   {:ga4gh/sequenceReference
    "https://identifiers.org/refseq:NC_000006.12",
    :ga4gh/start 65057728,
    :ga4gh/end 65320715,
    :type :ga4gh/SequenceLocation,
    :iri "https://genegraph.clingen.app/giBN-xozr5c"}
   {:type :ga4gh/SequenceLocation,
    :ga4gh/start 65301476,
    :ga4gh/end 65306574,
    :ga4gh/sequenceReference
    "https://identifiers.org/refseq:NC_000006.12",
    :iri "https://genegraph.clingen.app/y22ZdngSVeI"})
  ;; outer
  (overlap-type
   {:ga4gh/sequenceReference
    "https://identifiers.org/refseq:NC_000006.12",
    :ga4gh/start [20 22]
    :ga4gh/end [28 45]
    :type :ga4gh/SequenceLocation,
    :iri "https://genegraph.clingen.app/giBN-xozr5c"}
   {:type :ga4gh/SequenceLocation,
    :ga4gh/start 30
    :ga4gh/end 40
    :ga4gh/sequenceReference
    "https://identifiers.org/refseq:NC_000006.12",
    :iri "https://genegraph.clingen.app/y22ZdngSVeI"})
    (overlap-type
   {:ga4gh/sequenceReference
    "https://identifiers.org/refseq:NC_000006.12",
    :ga4gh/start [nil 22]
    :ga4gh/end [28 nil]
    :type :ga4gh/SequenceLocation,
    :iri "https://genegraph.clingen.app/giBN-xozr5c"}
   {:type :ga4gh/SequenceLocation,
    :ga4gh/start 30
    :ga4gh/end 40
    :ga4gh/sequenceReference
    "https://identifiers.org/refseq:NC_000006.12",
    :iri "https://genegraph.clingen.app/y22ZdngSVeI"})
  )


(def overlap-priority
  [:cg/CompleteOverlap :cg/PartialOverlap :cg/OuterOverlap :cg/NoOverlap])

;; what if the overlaps are different on the
;; different sequences. report in priority order
(defn gene-overlap [variant-locs gene]
  (let [overlaps (set
                  (map
                   (fn [gene-loc]
                     (if-let [var-loc (get variant-locs
                                           (:ga4gh/sequenceReference
                                            gene-loc))]
                       (overlap-type var-loc gene-loc)
                       :cg/NoOverlap))
                   (:ga4gh/location gene)))]
    (some overlaps overlap-priority)))

(comment
  (gene-overlap
   {"https://identifiers.org/refseq:NC_000006.12"
    {:ga4gh/sequenceReference
     "https://identifiers.org/refseq:NC_000006.12",
     :ga4gh/start 65057728,
     :ga4gh/end 65320715,
     :type :ga4gh/SequenceLocation,
     :iri "https://genegraph.clingen.app/giBN-xozr5c"},
    "https://identifiers.org/refseq:NC_000006.11"
    {:ga4gh/sequenceReference
     "https://identifiers.org/refseq:NC_000006.11",
     :ga4gh/start 65767621,
     :ga4gh/end 66030608,
     :type :ga4gh/SequenceLocation,
     :iri "https://genegraph.clingen.app/Sw6IHSYjojs"}}

   {:type :so/Gene,
    :iri "https://identifiers.org/ncbigene:441155",
    :ga4gh/location
    #{{:type :ga4gh/SequenceLocation,
       :ga4gh/start 66011369,
       :ga4gh/end 66016467,
       :ga4gh/sequenceReference
       "https://identifiers.org/refseq:NC_000006.11",
       :iri "https://genegraph.clingen.app/FWjCl-XKcrs"}
      {:type :ga4gh/SequenceLocation,
       :ga4gh/start 65301476,
       :ga4gh/end 65306574,
       :ga4gh/sequenceReference
       "https://identifiers.org/refseq:NC_000006.12",
       :iri "https://genegraph.clingen.app/y22ZdngSVeI"}}})


  (gene-overlap
   {"https://identifiers.org/refseq:NC_000006.12"
    {:ga4gh/sequenceReference
     "https://identifiers.org/refseq:NC_000006.12",
     :ga4gh/start [5 10],
     :ga4gh/end [20 40],
     :type :ga4gh/SequenceLocation,
     :iri "https://genegraph.clingen.app/giBN-xozr5c"},
    "https://identifiers.org/refseq:NC_000006.11"
    {:ga4gh/sequenceReference
     "https://identifiers.org/refseq:NC_000006.11",
     :ga4gh/start 30,
     :ga4gh/end 40,
     :type :ga4gh/SequenceLocation,
     :iri "https://genegraph.clingen.app/Sw6IHSYjojs"}}

   {:type :so/Gene,
    :iri "https://identifiers.org/ncbigene:441155",
    :ga4gh/location
    #{{:type :ga4gh/SequenceLocation,
       :ga4gh/start 32
       :ga4gh/end 38
       :ga4gh/sequenceReference
       "https://identifiers.org/refseq:NC_000006.11",
       :iri "https://genegraph.clingen.app/FWjCl-XKcrs"}
      {:type :ga4gh/SequenceLocation,
       :ga4gh/start 30
       :ga4gh/end 40
       :ga4gh/sequenceReference
       "https://identifiers.org/refseq:NC_000006.12",
       :iri "https://genegraph.clingen.app/y22ZdngSVeI"}}})


  )

(defn get-canonical-variant [variant-bundle]
  (first (filter #(= :cg/CanonicalVariant (:type %))
                 (:objects variant-bundle))))

(defn variant-loci [canonical-variant]
  (if canonical-variant
    (->> (:cg/includedVariants canonical-variant)
         (mapv :ga4gh/location)
         (reduce (fn [a v] (assoc a (:ga4gh/sequenceReference v) v)) {}))
    []))

(defn filter-gene-locations [variant-bundle]
  (let [loci (variant-loci variant-bundle)]
    (assoc variant-bundle :variant-loci loci)))

(defn add-gene-overlaps-for-variant [db variant-bundle]
  (let [canonical-variant (get-canonical-variant variant-bundle)
        get-gene (fn [gene] (storage/read db [:objects gene]))
        vloci (variant-loci canonical-variant)]
    (update variant-bundle
            :statements
            #(reduce (fn [stmts g]
                      (let [overlap (gene-overlap vloci
                                                  (get-gene g))]
                        (if (not= :cg/NoOverlap overlap)
                          (conj stmts
                                [(:iri canonical-variant)
                                 overlap
                                 (rdf/resource g)])
                          stmts)))
                    %
                    (gene-ids-for-bundle db variant-bundle)))))

(defn write-variant-bundle [object-db tdb variant-bundle]
  (let [model (rdf/statements->model (:statements variant-bundle))]
    (run! #(storage/write object-db
                          [:objects (:iri %)]
                          %)
          (:objects variant-bundle))
    (storage/write tdb
                     (:iri (get-canonical-variant variant-bundle))
                     (rdf/statements->model (:statements variant-bundle)))))

(defmethod ap/process-base-event :genegraph.api.base/load-clinvar
  [event]
  (log/info :fn ::ap/process-base-event
            :dispatch :genegraph.api.base/load-clinvar
            :object-db (get-in event [::storage/storage :object-db]))
  (with-open [is (-> (get-in event [:genegraph.framework.event/data
                                    :source])
                     storage/as-handle
                     io/input-stream
                     GZIPInputStream.)]
    (let [object-db (get-in event [::storage/storage :object-db])
          tdb (get-in event [::storage/storage :api-tdb])
          add-gene-overlaps-with-db
          #(add-gene-overlaps-for-variant object-db  %)
          write-variant-bundle-with-db
          #(write-variant-bundle object-db tdb %)]
      (->> (:content (xml/parse is))
           (map clinvar-xml->intermediate-model)
           (filter is-cnv?)
           (map variant->statements-and-objects)
           (map add-gene-overlaps-with-db)
           (run! write-variant-bundle-with-db)))
    (Thread/sleep 500))
  (log/info :fn ::ap/process-base-event
            :msg "clinvar complete")
  event)

;; do these reflect our codes:
;; https://va-ga4gh.readthedocs.io/en/latest/examples/variant-pathogenicity-statement.html


;; A bit of sample data for working with 
(comment
  (defonce http-client
    (hc/build-http-client {:connect-timeout 10000
                           :redirect-policy :always}))

  (def test-submissions (get-clinvar-variants ["14206" "59149"] http-client))

  (def flagged-submission-xml (get-clinvar-variants ["1412663"] http-client))

  (def internal-conflict (get-clinvar-variants ["150155"] http-client))
  ;;  150155
  (println internal-conflict)

  (println flagged-submission-xml)

  (println test-submissions)
  (let [object-db @(get-in genegraph.user/api-test-app [:storage :object-db :instance])
        tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])
        add-gene-overlaps-with-db
        #(add-gene-overlaps-for-variant object-db  %)]
    (->> (xml/parse-str test-submissions)
         :content
         (mapv #(-> %
                    clinvar-xml->intermediate-model
                    variant->statements-and-objects
                    add-gene-overlaps-with-db))
         #_tap>))
  )

;; Some template code for extracting statistics from ClinVar
;; Note that it takes about a half-hour to parse the ClinVar XML
(comment
  (.start (Thread.
           (fn []
             (with-open [is (->{:type :file
                                :base "data/base/"
                                :path "clinvar.xml.gz"}
                               storage/as-handle
                               io/input-stream
                               GZIPInputStream.)]
               (->> (:content (xml/parse is))
                    #_(take 10000)
                    (map clinvar-xml->intermediate-model)
                    (filter #(and (cnv-types (:variant-type %))
                                  (or (:copy-count %)
                                      (< 1000 (variation-length %)))))
                    #_(take 1)
                    (mapcat :classifications)
                    (map :ReviewStatus)
                    frequencies
                    #_(into [])
                    tap>)))))
  (+ 1 1)
  )
