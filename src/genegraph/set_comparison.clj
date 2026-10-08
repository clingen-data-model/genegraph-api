(ns genegraph.set-comparison
  "Setup for comparision of variant sets for selection of computational predictors.
  May be a partner to associated notebooks."
  (:require [genegraph.api.base.clinvar :as clinvar]
            [clojure.java.io :as io]
            [genegraph.api.base.vcf :as vcf]
            [charred.api :as charred]
            [genegraph.api.iscn :as iscn]
            [genegraph.api.overlaps :as overlaps]
            [genegraph.framework.storage.rdf :as rdf])
  (:import [java.util.zip GZIPInputStream]))

(def object-db @(get-in genegraph.user/api-test-app [:storage :object-db :instance]))
(def tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance]))
(def hybrid-db {:object-db object-db :tdb tdb})

(def gene-disruption-set
  #{:cg/CompleteOverlap :cg/ProbableGeneDisruption})

(defn lof-score [gene-scores]
  (let [score-product (reduce * (map #(- 1 %) gene-scores))]
    (Math/log (/ (- 1 score-product) score-product))))

;; Pre-filter: Marked as 'deletion', 'duplication', 'copy number gain' 'copy number loss'
;; size < 1kb
(def clinvar-cnvs
  (->> (clinvar/clinvar-cnvs object-db clinvar/clinvar-cnv-variation-ids)
       (into [])
       #_(take 5)
       #_(mapv clinvar/clinvar-variant->ga4gh)))

(defonce gnomad-cnv
  (with-open [r (-> "/Users/tristan/data/genegraph-base/gnomad-cnv.vcf.gz"
                    io/input-stream
                    GZIPInputStream.)]
    (->> (charred/read-csv r :separator \tab)
         (remove #(re-find #"^#" (first %)))
         #_(take 100)
         (map vcf/vcf-row->map)
         (filterv (fn [{:keys [alt filter svlen predicted_lof]}]
                    (and (= "<DEL>" alt)
                         svlen
                         (< 1000 (Long/parseLong svlen))
                         (= "PASS" filter)
                         #_predicted_lof)))
         #_(mapv vcf/->ga4gh-variant))))

(defn scored-vcf-cnv [cnv]
  (let [overlaps (overlaps/gene-overlaps-for-loci
                  object-db
                  [(vcf/->ga4gh-loc cnv)])
        disruptive-overlaps (filterv #(gene-disruption-set (:overlap %))
                                     overlaps)
        gene-scores (remove
                     nil?
                     (mapv (fn [o] (rdf/ld1-> (rdf/resource (:gene o) tdb)
                                              [[:cg/feature :<] :cg/lower95CI]))
                           disruptive-overlaps))
        lof-score (when (seq gene-scores) (lof-score gene-scores))]
    (assoc cnv
           :overlaps overlaps
           :disruptive-overlaps disruptive-overlaps
           :gene-scores gene-scores
           :lof-score lof-score)))

(defonce gnomad-cnv-scored
  (rdf/tx tdb
    (mapv scored-vcf-cnv gnomad-cnv)))
(comment
  (tap> (take 5 gnomad-cnv-scored))
  )

(def weird-gnomad-cnv
  {:disruptive-overlaps [],
   :posmax "1054727",
   :variant
   {:type :ga4gh/CopyNumberChange,
    :ga4gh/copyChange :efo/copy-number-loss,
    :ga4gh/location
    {:ga4gh/sequenceReference
     "https://identifiers.org/refseq:NC_000001.11",
     :ga4gh/start [925634 1054727],
     :ga4gh/end [999478 1092392],
     :type :ga4gh/SequenceLocation,
     :iri
     "https://genegraph.clinicalgenome.org/r/bNEcvYrSLX1dkxltDZdodg"},
    :iri
    "https://genegraph.clinicalgenome.org/r/y_HcVCt1z1zvsVeuu9EHnw"},
   :alt "<DEL>",
   :ref "N",
   :endmin "999478",
   :svtype "DEL",
   :pos "925634",
   :svlen "137193",
   :genes "AGRN,HES4,ISG15,KLHL17,NOC2L,PERM1,PLEKHN1,SAMD11",
   :filter "PASS",
   :endmax "1092392",
   :overlaps [],
   :id "variant_is_80_5__DEL",
   :posmin "925634",
   :chrom "chr1",
   :qual ".",
   :end "1062827"})

(defonce gnomad-sv
  (with-open [r (-> "/Users/tristan/data/gnomad/gnomad-sv.vcf.gz"
                    io/input-stream
                    GZIPInputStream.)]
    (->> (charred/read-csv r :separator \tab)
         (remove #(re-find #"^#" (first %)))
         #_(take 1000)
         (map vcf/vcf-row->map)
         (filterv (fn [{:keys [alt filter svlen predicted_lof]}]
                    (and (= "<DEL>" alt)
                         svlen
                         #_(< 1000 (Long/parseLong svlen))
                         (= "PASS" filter)
                         predicted_lof))))))

(defonce gnomad-sv-scored
  (rdf/tx tdb
    (mapv scored-vcf-cnv gnomad-sv)))

(defonce mayo
  (with-open [r (io/reader "/Users/tristan/data/mayo.csv")]
    (let [variant-set (->> (charred/read-csv r)
                           (take 5)
                           (mapv first)
                           iscn/variant-set)]
      (->> (:observations variant-set)
           (filterv :variant)))))


(comment
  (tap> clinvar-cnvs)
  (count clinvar-cnvs)
  (tap> (take 5 gnomad-cnv))
  (count gnomad-cnv)
  (tap> (take 5 gnomad-sv))
  (count gnomad-sv)
  (tap> mayo)
  )



(comment
  ;; Calculating some figures for gnomAD SV
  (time
   (with-open [r (-> "/Users/tristan/data/gnomad/gnomad-sv.vcf.gz"
                    io/input-stream
                    GZIPInputStream.)]
     (->> (charred/read-csv r :separator \tab)
          (remove #(re-find #"^#" (first %)))
          #_(take 100)
          (map vcf/vcf-row->map)
          (filter (fn [{:keys [alt filter svlen predicted_lof]}]
                      (and (= "<DEL>" alt)
                           svlen
                           (< 1000 (Long/parseLong svlen))
                           predicted_lof)))
          #_(mapv vcf/->ga4gh-variant)
          count)))
  
  ;; total in set
  2154486
  ;; dels only
  1197080
  ;; dels > 1kb
  293924
  ;; w plof
  36087
  
  )
