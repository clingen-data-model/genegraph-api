(ns genegraph.api.overlaps
  (:require [clojure.data.xml :as xml]
            [clojure.data.zip.xml :as xml-zip]
            [clojure.data.csv :as csv]
            [clojure.zip :as zip]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.set :as set]
            [clojure.math :as math]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.storage.rocksdb :as rocksdb]
            [genegraph.api.hybrid-resource :as hr]
            [genegraph.framework.id :as id]
            [genegraph.api.protocol :as ap]
            [genegraph.api.sequence-index :as idx]
            [genegraph.api.shared-data :as shared-data]
            [io.pedestal.log :as log]
            [hato.client :as hc]
            [genegraph.api.ga4gh]))

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
    (let [loc1-start (max-coord (:ga4gh/start loc1))
          loc2-start (max-coord (:ga4gh/start loc2))
          loc1-end (min-coord (:ga4gh/end loc1))
          loc2-end (min-coord (:ga4gh/end loc2))
          all-coords [loc1-start loc2-start loc1-end loc2-end]]
      (if (or (some nil? all-coords)
              (<= loc1-end loc2-start)
              (<= loc2-end loc1-start))
        :cg/NoOverlap
        :cg/OuterOverlap))
    :cg/NoOverlap))

(defn overlap-type [loc1 loc2]
  (let [loc1-start (max-coord (:ga4gh/start loc1))
        loc2-start (max-coord (:ga4gh/start loc2))
        loc1-end (min-coord (:ga4gh/end loc1))
        loc2-end (min-coord (:ga4gh/end loc2))
        all-coords [loc1-start loc2-start loc1-end loc2-end]
        result (cond
                 (some nil? all-coords) :cg/NoOverlap
                 (and (<= loc1-start loc2-start)
                      (<= loc2-end loc1-end)) :cg/CompleteOverlap
                 (and (< loc1-start loc2-end)
                      (< loc2-start loc1-end)) :cg/PartialOverlap
                 :default (outer-overlap? loc1 loc2))]
    result))

;; Code for handling partial overlaps

(defn exons-for-gene
  "Return the exons for a given gene, sorted by start location"
  [db gene-iri sequence-iri]
  (storage/scan db [:exons gene-iri sequence-iri]))

(defn exon-overlaps [loc exons]
  (mapv (fn [exon] {:overlap (overlap-type loc (:location exon))
                    :exon exon})
        exons))

(defn most-coding-sequence-deleted? [exons-with-overlaps]
  (let [exons-with-coding-extents
        (mapv (fn [e]
                (let [{:ga4gh/keys [start end]} (get-in e [:exon :location])]
                  (assoc e :cds-size (- end start))))
              exons-with-overlaps)]
    (< 0.5
       (/
        (->> exons-with-coding-extents
             (filterv #(= :cg/CompleteOverlap (:overlap %)))
             (mapv :cds-size)
             (reduce +))
        (reduce + (mapv :cds-size exons-with-coding-extents))))))

(defn odd-number-of-splice-signals? [exons-with-overlaps]
  (some-> (frequencies (map :overlap exons-with-overlaps))
          :cg/PartialOverlap
          odd?))

(defn deletion-of-first-exon? [exons-with-overlaps]
  (let [strand (-> exons-with-overlaps first :exon :strand)
        first-exon (if (= "+" strand)
                     (first exons-with-overlaps)
                     (last exons-with-overlaps))]
    (= :cg/CompleteOverlap (:overlap first-exon))))

(defn probable-gene-disruption? [exons-with-overlaps]
  (let [tests [most-coding-sequence-deleted?
               odd-number-of-splice-signals?
               deletion-of-first-exon?]]
    (some (fn [t] (t exons-with-overlaps)) tests)))

(defn possible-frameshift? [exons-with-overlaps]
  (<= 1
      (count
       (set/intersection #{:cg/OuterOverlap :cg/PartialOverlap}
                         (set (map :overlap exons-with-overlaps))))))

(defn fully-intronic? [exons-with-overlaps]
  (= #{:cg/NoOverlap}
     (set (map :overlap exons-with-overlaps))))

(defn disruptions-from-partial-overlap [db loc gene]
  (let [exons (exons-for-gene db gene (:ga4gh/sequenceReference loc))
        exons-with-overlaps (exon-overlaps loc exons)]
    (cond
      (probable-gene-disruption? exons-with-overlaps) :cg/ProbableGeneDisruption
      (possible-frameshift? exons-with-overlaps) :cg/PossibleFrameshift
      (fully-intronic? exons-with-overlaps) :cg/FullyIntronic
      :default :cg/SmallExonDeletion)))

(def partial-overlap-set #{:cg/OuterOverlap :cg/PartialOverlap})

(defn update-partial-overlaps
  "Update partial overlaps for predicted gene disruption based on exonic involvement"
  [db loc overlaps]
  (if loc
    (mapv (fn [overlap]
            (if (partial-overlap-set (:overlap overlap))
              (assoc overlap
                     :overlap
                     (disruptions-from-partial-overlap db
                                                       loc
                                                       (get-in overlap [:gene :iri])))
              overlap))
          overlaps)
    overlaps))

(comment

  (def loc1
    {:ga4gh/sequenceReference "https://identifiers.org/refseq:NC_000001.11"
     :ga4gh/start 925634
     :ga4gh/end [930434 931187]
     :type :ga4gh/SequenceLocation
     :iri "https://genegraph.clinicalgenome.org/r/pYvB2LXA4IejcUU_6PaFxg"
     :overlaps [{:gene {:type :so/Gene, :iri "https://identifiers.org/ncbigene:148398", :strand "+", :parent "https://identifiers.org/ncbigene:148398", :ga4gh/location #{{:type :ga4gh/SequenceLocation, :ga4gh/start 923923, :ga4gh/end 944574, :ga4gh/sequenceReference "https://identifiers.org/refseq:NC_000001.11", :iri "https://genegraph.clinicalgenome.org/r/v92DQZr_I23pEV2kbBY8EA"} {:type :ga4gh/SequenceLocation, :ga4gh/start 859303, :ga4gh/end 879954, :ga4gh/sequenceReference "https://identifiers.org/refseq:NC_000001.10", :iri "https://genegraph.clinicalgenome.org/r/kKQSo2P4iX8odVpVT1l3Bg"}}}, :overlap :cg/OuterOverlap}]})

  (def rdb @(get-in genegraph.user/api-test-app [:storage :object-db :instance]))

  (disruptions-from-partial-overlap rdb loc1 "https://identifiers.org/ncbigene:148398")
  (tap> (analyze-partial-overlaps rdb loc1))
 
  )

(defn gene-overlaps-for-location [db location]
  (->> (mapcat
        #(rocksdb/range-get
          db
          (idx/location->search-params location %))
        [:so/Gene])
       (map :iri)
       set))

(defn gene-ids-for-loci [db loci]
  (let [loc-overlaps #(gene-overlaps-for-location db %)]
    (->> loci
         (mapv loc-overlaps)
         (reduce set/union))))

(def overlap-priority
  [:cg/CompleteOverlap :cg/PartialOverlap :cg/OuterOverlap :cg/NoOverlap])

(defn gene-overlap [locs gene]
  (let [loc-map (reduce (fn [m l]
                          (assoc m
                                 (:ga4gh/sequenceReference l)
                                 l))
                        {}
                        locs)
        overlaps (set
                  (map
                   (fn [gene-loc]
                     (if-let [var-loc (get loc-map
                                           (:ga4gh/sequenceReference
                                            gene-loc))]
                       (overlap-type var-loc gene-loc)
                       :cg/NoOverlap))
                   (:ga4gh/location gene)))]
    (some overlaps overlap-priority)))

(defn in-sequence-set? [loci seq-set]
  (some (fn [loc]
          (when (contains? seq-set (:ga4gh/sequenceReference loc))
            loc))
        loci))

(defn priority-loc [loci]
  (or (in-sequence-set? loci shared-data/build38seqs)
      (in-sequence-set? loci shared-data/build37seqs)
      (first loci)))

(defn gene-overlaps-for-loci [db loci]
  (let [get-gene (fn [gene] (storage/read db [:objects gene]))
        best-loc (priority-loc loci)]
    (->> (gene-ids-for-loci db loci)
         (mapv #(storage/read db [:objects %]))
         (mapv (fn [g]
                 {:gene g
                  :overlap (gene-overlap loci g)}))
         (remove #(= :cg/NoOverlap (:overlap %)))
         (update-partial-overlaps db best-loc))))

(defn protein-coding-gene? [gene tdb]
  (let [q (rdf/create-query "select ?g where { ?g a :so/GeneWithProteinProduct } ")]
    (seq (q tdb {:g (rdf/resource gene)}))))

(comment
  (def chr16p-prox
    (filter  #(re-find #"ISCA-37400" (:iri % ))
             genegraph.user/last-regions))

  (let [object-db @(get-in genegraph.user/api-test-app [:storage :object-db :instance])
        tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])]
    (rdf/tx tdb
      (->> chr16p-prox
           (mapv #(gene-overlaps-for-loci object-db (:ga4gh/location %)))
           (mapv (fn [g]
                   (filterv #(protein-coding-gene? (get-in % [:gene :iri]) tdb)
                            g)))
           tap>
           #_(mapv #(get-in % [:gene :iri])))))


  (defn prox-genes-for-region [r]
    (let [object-db @(get-in genegraph.user/api-test-app [:storage :object-db :instance])
          tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])]
      (rdf/tx tdb
        (->> (gene-overlaps-for-loci object-db
                                     (:ga4gh/location r))
             (filterv #(protein-coding-gene? (get-in % [:gene :iri]) tdb))
             (mapv #(get-in % [:gene :iri]))
             set))))

  (def recurrent-regions-with-genes
    (->> genegraph.user/last-regions
         (map #(assoc % :genes (prox-genes-for-region %)))
         (filterv #(seq (:genes %)))))
  
  (->> recurrent-regions-with-genes
       (map #(count (:genes %)))
       frequencies)


  
  (defn variants-in-region [region variant-gene-sets]
    (let [region-genes (:genes region)
          genes-count (count region-genes)
          window (math/round (* genes-count 0.05))]
      (->> variant-gene-sets
           (filter #(and (<= (count (:genes %)) (+ genes-count window))
                         (<= (- genes-count window) (count (:genes %)))))
           (filterv #(contains-most? region-genes
                                     (:genes %)
                                     0.90))
           (mapv :variant)
           (remove nil?))))
  
  (def regions-with-variants
    (mapv #(assoc % :variants (variants-in-region % variant-gene-sets)) recurrent-regions-with-genes))

  (do
    (defn variant-info [iri hybrid-db]
      (let [v (hr/hybrid-resource iri hybrid-db)]
        (assoc v :direction (rdf/->kw
                             (rdf/ld1-> v [[:cg/variant :<] [:cg/subject :<] :cg/direction])))))

    

    (defn region-info [r hybrid-db]
      (assoc r :label (rdf/ld1-> (rdf/resource (:iri r) (:tdb hybrid-db)) [:rdfs/label])))

    (defn add-variant-call-frequencies [r variant-type]
      (assoc-in r
                [:call-frequencies variant-type]
                (frequencies (map :direction (get r variant-type)))))

    (def header
      ["Region Name"
       "ID"
       "P/LP Loss"
       "VUS Loss"
       "B/LB Loss"
       "P/LP Gain"
       "VUS Gain"
       "B/LB Gain"])

    (defn region->row [{:keys [label iri call-frequencies]}]
      (let [gain (:efo/copy-number-gain call-frequencies)
            loss (:efo/copy-number-loss call-frequencies)]
        [label
         (re-find #"ISCA-\d+" iri)
         (:cg/Supports loss 0)
         (:cg/Inconclusive loss 0)
         (:cg/Refutes loss 0)
         (:cg/Supports gain 0)
         (:cg/Inconclusive gain 0)
         (:cg/Refutes gain 0)]))

    ;; start here, group by change type, record classification frequencies for discrepancies.
    (with-open [w (io/writer "/Users/tristan/Desktop/recurrent-regions-in-clinvar.csv")]
      (let [object-db @(get-in genegraph.user/api-test-app [:storage :object-db :instance])
            tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])
            hybrid-db {:object-db object-db :tdb tdb}]

        (rdf/tx tdb
          (->> regions-with-variants
               (map #(region-info % hybrid-db))
               (map #(assoc % :variants (mapv (fn [v] (variant-info v hybrid-db)) (:variants %))))
               (map #(merge % (group-by :ga4gh/copyChange (:variants %))))
               (map #(add-variant-call-frequencies % :efo/copy-number-gain))
               (map #(add-variant-call-frequencies % :efo/copy-number-loss))
               (mapv region->row)
               (cons header)
               (csv/write-csv w))))))
  
  (def chr16p-prox-genes
    (let [object-db @(get-in genegraph.user/api-test-app [:storage :object-db :instance])
          tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])]
      (rdf/tx tdb
        (->> (gene-overlaps-for-loci object-db
                                     (:ga4gh/location (first chr16p-prox)))
             (filterv #(protein-coding-gene? (get-in % [:gene :iri]) tdb))
             (mapv #(get-in % [:gene :iri]))
             set))))
  (def variant-gene-sets
    (let [object-db @(get-in genegraph.user/api-test-app [:storage :object-db :instance])
          tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])
          q (rdf/create-query "
select ?v where {
?v :cg/CompleteOverlap ?g .
?g a :so/GeneWithProteinProduct .
}")
          gq (rdf/create-query "
select ?g where {
?v :cg/CompleteOverlap ?g .
?g a :so/GeneWithProteinProduct .
}")]
      (rdf/tx tdb
        (->> (q tdb)
             (mapv (fn [v]
                     {:variant (str v)
                      :genes (->> (gq tdb {:v v})
                                  (map str)
                                  set)}))))))

  (count variant-gene-sets)

  (tap> (take 5 variant-gene-sets))
  (tap> chr16p-prox-genes)


  ;; write a clojure function comparing whether the items in
  ;; set 1 contain most of the elements in set 2

  (defn contains-most? 
    ([set1 set2] (contains-most? set1 set2 0.5))
    ([set1 set2 threshold]
     (let [intersection-count (count (clojure.set/intersection set1 set2))
           set2-count (count set2)]
       (>= (/ intersection-count set2-count) threshold))))


  (do
    (defn loc->pos [loc cmp]
      (if (number? loc)
        loc
        (->> loc (remove nil?) (apply cmp))))

    #_(defn variant-color [v]
        (if (= :efo/copy-number-loss (:ga4gh/copyChange v))
          "255,0,0"
          "0,0,255"))

    (defn variant-color [v]
      (case (:direction v)
        :cg/Supports  "255,0,0"
        :cg/Inconclusive "0,0,255"
        :cg/Refutes "0,255,0"
        "100,100,100"))



    (defn variant->bed-row [v object-db]
      (let [v-obj (storage/read object-db [:objects v])
            l
            (->> (:cg/includedVariants v-obj)
                 (mapv :ga4gh/location)
                 (filter #(= "https://identifiers.org/refseq:NC_000016.9"
                             (get-in % [:ga4gh/sequenceReference])))
                 first)]
        (if (and (:ga4gh/start l) (:ga4gh/end l))
          ["chr16"
           (loc->pos (:ga4gh/start l) min)
           (loc->pos (:ga4gh/end l) max)
           v
           "500"
           "."
           (loc->pos (:ga4gh/start l) min)
           (loc->pos (:ga4gh/end l) max)
           (variant-color v-obj)]
          nil)))

    (defn variant->bed [v]
      (let [l
            (->> (:cg/includedVariants v-obj)
                 (mapv :ga4gh/location)
                 (filter #(= "https://identifiers.org/refseq:NC_000016.9"
                             (get-in % [:ga4gh/sequenceReference])))
                 first)]
        (if (and (:ga4gh/start l) (:ga4gh/end l))
          ["chr16"
           (loc->pos (:ga4gh/start l) min)
           (loc->pos (:ga4gh/end l) max)
           v
           "500"
           "."
           (loc->pos (:ga4gh/start l) min)
           (loc->pos (:ga4gh/end l) max)
           (variant-color v-obj)]
          nil)))

    (defn variants->bed [path vs]
      (with-open [w (io/writer path)]

        (mapv #(variant->bed-row % ))))

    "chr16:29638676-30188531"
    (with-open [w (io/writer "/Users/tristan/Desktop/chr16p-prox.bed")]
      (let [object-db @(get-in genegraph.user/api-test-app [:storage :object-db :instance])]
        (csv/write-csv
         w
         (concat
          [["browser position chr16:29638676-30188531"]]
          (->> variant-gene-sets
               (filter #(and (<= (count (:genes %)) 32)
                             (<= 25 (count (:genes %)))))
               (filterv #(contains-most? chr16p-prox-genes
                                         (:genes %)
                                         0.99))
               #_(take 5)
               (mapv :variant)
               (mapv #(variant->bed-row % object-db))
               (remove nil?)))
         :separator \tab))))



  )

(comment
  (-> "/Users/tristan/Downloads/tableExport-2.json"
      slurp
      json/read-str
      tap>)
  )
