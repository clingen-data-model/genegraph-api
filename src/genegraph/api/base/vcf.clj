(ns genegraph.api.base.vcf
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [charred.api :as charred]
            [genegraph.framework.id :as id]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.api.shared-data :as shared-data]
            [genegraph.api.overlaps :as overlaps])
  (:import [java.util.zip GZIPInputStream]))
;; look into PREDICTED_ fields
(def info-fields-needed
  #{"END" ; End position of the structural variant
    "ENDMAX"
    "ENDMIN"
    "Genes"
    "POSMAX"
    "POSMIN"
    "SVLEN"
    "SVTYPE"
    "AC" ; Number of non-reference alleles observed (biallelic sites only)
    "AF" ; Allele frequency (biallelic sites only)
    "ALGORITHMS" ; Source algorithms
    "AN" ;Total number of alleles genotyped (biallelic sites only)
    "BOTHSIDES_SUPPORT" ; Variant has read-level support for both sides of breakpoint.Indicates higher-confidence variants
    "CHR2" ; Chromosome for END coordinate
    "CN_COUNT" ; Number of samples observed at each copy state
    "CN_FREQ" ; Frequency of samples observed at each copy state
    "CN_NUMBER" ; Total number of samples with estimated copy numbers (multiallelic CNVs only).
    "CN_STATUS" ; Differnt copy states observed
    "CPX_INTERVALS" ; Genomic intervals constituting complex variant
    "CPX_TYPE" ; Class of complex variant
    "END2" ; End position of the structural variant on CHR2
    "FREQ_HET" ; Heterozygous genotype frequency (biallelic sites only)
    "FREQ_HOMALT" ; Homozygous alternate genotype frequency (biallelic sites only)
    "FREQ_HOMREF" ; Homozygous reference genotype frequency (biallelic sites only)
    "OUTLIER_SAMPLE_ENRICHED_LENIENT" ; SVs that are enriched for non-reference genotypes in outlier samples (10%)
    "PREDICTED_LOF" ; Gene(s) on which the SV is predicted to have a loss-of-function effect.
    "PREDICTED_COPY_GAIN" ; Gene(s) on which the SV is predicted to have a copy-gain effect.
    })

(defn info-field->map [info-field]
  (update-keys
   (->> (str/split info-field #";")
        (map #(str/split % #"="))
        (filter #(info-fields-needed (first %)))
        (map (fn [s]
               (if (= 1 (count s))
                 (conj s true)
                 s)))
        (into {}))
   #(keyword (str/lower-case %))))

["#CHROM" "POS" "ID" "REF" "ALT" "QUAL" "FILTER" "INFO"]

(defn vcf-row->map [[chrom pos id ref alt qual filter info]]
  (let [row-map {:chrom chrom
                 :pos pos
                 :id id
                 :ref ref
                 :alt alt
                 :qual qual
                 :filter filter}]
    (merge
     (info-field->map info)
     row-map)))

;; gnomad v 4.1 is grch38
;; can fuss with vcf contigs another time 
(defn chr-num [chr-string]
  )

(defn seq-loc [chr-string]
  (get (:grch38 shared-data/chr-to-ref)
       (second (re-find #"chr(.+)$" chr-string))))

(seq-loc "chr13")

(defn ->ga4gh-loc [{:keys [chrom endmin endmax end pos posmax posmin]}]
  (let [start-min (Long/parseLong posmin)
        start-max (Long/parseLong posmax)
        end-min (Long/parseLong endmin)
        end-max (Long/parseLong endmax)
        loc {:ga4gh/sequenceReference (seq-loc chrom)
             :ga4gh/start (if (not= start-min start-max)
                            [start-min start-max]
                            start-min)
             :ga4gh/end (if (not= end-min end-max)
                          [end-min end-max]
                          end-min)
             :type :ga4gh/SequenceLocation}]
    (assoc loc :iri (id/iri loc))))

(def ->efo-term
  {"DEL" :efo/copy-number-loss
   "DUP" :efo/copy-number-gain})

(defn ->ga4gh-variant [{:keys [svtype] :as v}]
  (let [variant {:type :ga4gh/CopyNumberChange
                 :ga4gh/copyChange (->efo-term svtype)
                 :ga4gh/location (->ga4gh-loc v)}]
    (assoc variant :iri (id/iri variant))))

(comment
  (let [tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])
        object-db @(get-in genegraph.user/api-test-app [:storage :object-db :instance])]
    (rdf/tx
     tdb
     (with-open [r (-> "/Users/tristan/Downloads/gnomad-cnv.vcf.gz"
                       io/input-stream
                       GZIPInputStream.)]
       (->> (charred/read-csv r :separator \tab)
            (remove #(re-find #"^#" (first %)))
            (take 1)
            (mapv #(-> %
                       vcf-row->map
                       ->ga4gh-variant
                       :ga4gh/location))
            (mapv #(assoc % :overlaps (overlaps/gene-overlaps-for-loci object-db [%])))
            tap>))))

  (with-open [r (-> "/Users/tristan/Downloads/gnomad-cnv.vcf.gz"
                    io/input-stream
                    GZIPInputStream.)]
    (->> (charred/read-csv r :separator \tab)
         (remove #(re-find #"^#" (first %)))
         (take 1)
         (mapv vcf-row->map)
         tap>))

  (with-open [r (-> "/Users/tristan/Downloads/gnomad-cnv.vcf.gz"
                    io/input-stream
                    GZIPInputStream.)]
    (->> (charred/read-csv r :separator \tab)
         (filter #(re-find #"^#" (first %)))
         (take 100)
         (into [])
         tap>))

  (with-open [r (-> "/Users/tristan/data/gnomad/gnomad-sv.vcf"
                    io/input-stream
                    GZIPInputStream.)]
    (->> (charred/read-csv r :separator \tab)
         (remove #(re-find #"^#" (first %)))
         (map vcf-row->map)
         (filter #(= "<DEL>" (:alt %)))
         (take 5)
         (map #(info-field->map (:info %)))
         (map #(get % "SVLEN"))
         (remove nil?)
         (map #(Long/parseLong %))
         (remove #(< % 1000))
         #_(into [])
         count
         tap>))
  (.start
   (Thread.
    (fn []
      (with-open [r (-> "/Users/tristan/data/gnomad/gnomad-sv.vcf.gz"
                        io/input-stream
                        GZIPInputStream.)]
        (->> (charred/read-csv r :separator \tab)
             (remove #(re-find #"^#" (first %)))
             (map vcf-row->map)
             (filter (fn [{:keys [alt filter svlen predicted_lof]}]
                       (and (= "<DEL>" alt)
                            (= "PASS" filter)
                            svlen
                            (< 1000 (Long/parseLong svlen))
                            predicted_lof)))
             count
             #_(take 5)
             #_(into [])
             tap>)))))
  (+ 1 1)
  ;; Dels < 1kb
  282080

  (do
    (defn info-metadata->map [[info-str]]
      (->> (str/split (second (re-find #"\<(.*)\>" info-str)) #",")
           (mapv #(str/split % #"="))
           (filter #(= 2 (count %)))
           (into {})
           walk/keywordize-keys))
    (with-open [r (-> "/Users/tristan/data/gnomad/gnomad-sv.vcf.gz"
                      io/input-stream
                      GZIPInputStream.)]
      (->> (charred/read-csv r :separator \tab)
           (take 1000)
           (filter #(re-find #"^##INFO" (first %)))
           (map info-metadata->map)
           (sort-by :ID)
           #_(take 5)
           (mapv (fn [r] [(:ID r) (:Description r)]))
           tap>))
    )

  (with-open [r (-> "/Users/tristan/data/gnomad/gnomad-sv.vcf.gz"
                    io/input-stream
                    GZIPInputStream.)]
    (->> (charred/read-csv r :separator \tab)
         (take 1000)
         (filterv #(re-find #"^#" (first %)))
         tap>))

  (+ 1 1)
  ;; alt (SV Type)
  {"<DUP>" 269326, "<INS:ME:SVA>" 17607, "<INS>" 83441, "<INS:ME:LINE1>" 30223, "<BND>" 356035, "<INV>" 2193, "<DEL:ME:LINE1>" 8505, "<DEL:ME:HERVK>" 693, "<CTX>" 99, "<CPX>" 15189, "<CNV>" 721, "<INS:ME:ALU>" 173374, "<DEL>" 1197080}
  
  @genegraph.user/p

  ;; filter (quality filters)
  (reverse
   (sort-by val
            {"IGH_MHC_OVERLAP;UNRESOLVED" 7280,
             "HIGH_NCR;LOWQUAL_WHAM_SR_DEL" 70291,
             "IGH_MHC_OVERLAP;LOWQUAL_WHAM_SR_DEL" 882,
             "OUTLIER_SAMPLE_ENRICHED" 109905,
             "LOWQUAL_WHAM_SR_DEL" 131479,
             "FAIL_MANUAL_REVIEW" 254,
             "FAIL_MANUAL_REVIEW;HIGH_NCR" 23,
             "PASS" 1199117,
             "UNRESOLVED" 278316,
             "REFERENCE_ARTIFACT" 57,
             "HIGH_NCR;IGH_MHC_OVERLAP;UNRESOLVED" 1624,
             "HIGH_NCR;IGH_MHC_OVERLAP;LOWQUAL_WHAM_SR_DEL" 493,
             "HIGH_NCR;UNRESOLVED" 79159,
             "IGH_MHC_OVERLAP" 5424,
             "HIGH_NCR" 82853,
             "LOWQUAL_WHAM_SR_DEL;OUTLIER_SAMPLE_ENRICHED" 186815,
             "HIGH_NCR;IGH_MHC_OVERLAP" 514}))

  ;; same, sorted
  (["PASS" 1199117]
   ["UNRESOLVED" 278316]
   ["LOWQUAL_WHAM_SR_DEL;OUTLIER_SAMPLE_ENRICHED" 186815]
   ["LOWQUAL_WHAM_SR_DEL" 131479]
   ["OUTLIER_SAMPLE_ENRICHED" 109905]
   ["HIGH_NCR" 82853]
   ["HIGH_NCR;UNRESOLVED" 79159]
   ["HIGH_NCR;LOWQUAL_WHAM_SR_DEL" 70291]
   ["IGH_MHC_OVERLAP;UNRESOLVED" 7280]
   ["IGH_MHC_OVERLAP" 5424]
   ["HIGH_NCR;IGH_MHC_OVERLAP;UNRESOLVED" 1624]
   ["IGH_MHC_OVERLAP;LOWQUAL_WHAM_SR_DEL" 882]
   ["HIGH_NCR;IGH_MHC_OVERLAP" 514]
   ["HIGH_NCR;IGH_MHC_OVERLAP;LOWQUAL_WHAM_SR_DEL" 493]
   ["FAIL_MANUAL_REVIEW" 254]
   ["REFERENCE_ARTIFACT" 57]
   ["FAIL_MANUAL_REVIEW;HIGH_NCR" 23])
  


  

  )
