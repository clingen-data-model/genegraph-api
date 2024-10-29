(ns genegraph.api.base.gff
  (:require [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [genegraph.framework.id :as id]
            [genegraph.framework.storage :as storage]
            [genegraph.api.protocol :as ap]
            [genegraph.api.sequence-index :as idx]
            [io.pedestal.log :as log])
  (:import [java.util.zip GZIPInputStream]
           [java.nio ByteBuffer]
           [java.util Base64]
           [net.jpountz.xxhash XXHashFactory]
           [com.google.common.primitives Longs]))

(defn seq->hash
  "Return a hash of the elements in seq. Currently supports only str and long"
  [xs]
  (let [h64 (.hash64 (XXHashFactory/fastestInstance))
        len (reduce (fn [n x]
                      (if (int? x)
                        (+ n Long/BYTES)
                        (+ n (count (.getBytes ^String x)))))
                    0
                    xs)
        bb (ByteBuffer/allocate len)]
    (run! (fn [x]
            (if (int? x)
              (.putLong bb ^Long x)
              (.put bb (.getBytes ^String x))))
          xs)
    (.encodeToString
     (.withoutPadding (Base64/getUrlEncoder))
     (Longs/toByteArray (.hash h64 bb 0 len 0)))))

(defn gff-attributes->map
  [[seqid
    source
    type
    start
    end
    score
    strand
    phase
    gff-attributes]]
  (let [attr-map (transient {})]
    (reduce
     (fn [m x]
       (let [[_ k v] (re-find #"^(\w*)=(.*)$" x)]
         (if k (assoc! m (keyword "gff-attrs" k) v) m)))
     attr-map
     (str/split gff-attributes #";"))
    (persistent!
     (assoc! attr-map
             :gff/seqid seqid
             :gff/source source
             :gff/type type
             :gff/start (Long/valueOf start)
             :gff/end (Long/valueOf end)
             :gff/score score
             :gff/strand strand
             :gff/phase phase
             :ncbi/gene-id (some->> attr-map
                                    :gff-attrs/Dbxref
                                    (re-find #"GeneID:(\d+)")
                                    second)
             ::hash (seq->hash [type seqid start end strand])))))

(def refseq-root "https://identifiers.org/refseq:")
(def entrez-gene-root "https://identifiers.org/ncbigene:")

(def gff-type->so-term
  {"gene" :so/Gene})

(def useable-types
  (set (keys gff-type->so-term)))

(defn gff-map->location [gff-map]
  (let [location-attrs
        {:type :ga4gh/SequenceLocation
         :ga4gh/start (:gff/start gff-map)
         :ga4gh/end (:gff/end gff-map)
         :ga4gh/sequenceReference (str refseq-root (:gff/seqid gff-map))}]
    (assoc location-attrs :iri (id/iri location-attrs))))

(defn gff-feature-id [gff-map]
  (case (:gff/type gff-map)
    "gene" (str entrez-gene-root (:ncbi/gene-id gff-map))))

(defn gff-map->sequence-feature [gff-map]
  (let [location (gff-map->location gff-map)]
    {:type (gff-type->so-term (:gff/type gff-map))
     :iri (gff-feature-id gff-map)
     :location location}))

(defmethod idx/sequence-feature->sequence-index :so/Gene [feature]
  (let [loci (idx/location->index-entries (:location feature))]
    (mapv 
     (fn [l]
       {:key [:sequences
              :so/Gene
              (:sequence-reference l)
              (:coordinate l)
              (:iri feature)]
        :value (select-keys feature [:iri])})
     loci)))

(defn gff-map->object-and-indexes [gff-map]
  (let [feature (gff-map->sequence-feature gff-map)]
    {:object feature
     :indexes (idx/sequence-feature->sequence-index feature)}))

(defn write-object-and-indexes [db {:keys [object indexes]}]
  (when object
    (let [k [:objects (:iri object)]
          existing-record (storage/read db k)
          merged-object (if (= ::storage/miss existing-record)
                          (assoc object
                                 :ga4gh/location
                                 #{(:location object)})
                          (assoc object
                                 :ga4gh/location
                                 (conj (:ga4gh/location existing-record)
                                       (:location object))))]
      (storage/write db
                     [:objects (:iri object)]
                     (dissoc merged-object :location))))
  (run! #(storage/write db (:key %) (:value %)) indexes))

(defmethod ap/process-base-event :genegraph.api.base/load-gff [event]
  (log/info :fn ::ap/process-base-event
            :action :genegraph.api.base/load-gff)
  (with-open [r (-> (get-in event [:genegraph.framework.event/data
                                   :source])
                    storage/as-handle
                    io/input-stream
                    GZIPInputStream.
                    io/reader)]
    (let [write-records #(write-object-and-indexes
                          (get-in event [::storage/storage :object-db])
                          %)]
      (->> (csv/read-csv r :separator \tab)
           (filter #(< 3 (count %)))    ; remove comments
           (map gff-attributes->map)
           (filter #(useable-types (:gff/type %)))
           (map gff-map->object-and-indexes)
           (run! write-records))))
  (log/info :fn ::ap/process-base-event
            :action :genegraph.api.base/load-gff)
  event)

(comment
  
  (re-find #"^(\w*)=(.*)$" "ID=exon-NR_046018.2-1")
  (tap> gff-record)
  (time
   (let [gff-path "/Users/tristan/data/clinvar-cnv-annotation/GRCh38.gff.gz"]
     (with-open [r (-> gff-path
                       io/input-stream
                       GZIPInputStream.
                       io/reader)]
       (->> (csv/read-csv r :separator \tab)
            (filter #(< 3 (count %)))   ; remove comments
            (map gff-attributes->map)
            #_(map :gff/type)
            #_frequencies
            (filter #(useable-types (:gff/type %)))
            (map gff-map->sequence-feature)
            #_(map idx/sequence-feature->sequence-index)
            (take 5)
            (into [])
            tap>))))

  (time
   (let [gff-path "/Users/tristan/data/clinvar-cnv-annotation/GRCh38.gff.gz"]
     (with-open [r (-> gff-path
                       io/input-stream
                       GZIPInputStream.
                       io/reader)]
       (->> (csv/read-csv r :separator \tab)
            (filter #(< 3 (count %)))   ; remove comments
            (map gff-attributes->map)
            (filter #(= "exon" (:gff/type %)))
            (map #(-> % gff-map->location :iri))
            (into #{})
            count))))

  (time
   (def mRNA-names
     (let [gff-path "/Users/tristan/data/clinvar-cnv-annotation/GRCh38.gff.gz"]
       (with-open [r (-> gff-path
                         io/input-stream
                         GZIPInputStream.
                         io/reader)]
         (->> (csv/read-csv r :separator \tab)
              (filter #(< 3 (count %))) ; remove comments
              (map gff-attributes->map)
              (filter #(= "mRNA" (:gff/type %)))
              #_(take 5)
              (map #(select-keys % [:gff-attrs/Parent
                                    :gff-attrs/Name]))
              #_(map :gff-attrs/Parent)
              #_(into #{})
              #_(map :gff/type)
              #_frequencies
              (into [])
              #_tap>
              #_(map #(-> % gff-map->location :iri)))))))

  (->> mRNA-names
       (filter #(= 0 (count (:gff-attrs/Name %))))
       count)
  
  

  (time
   (def ofd1-annotation
     (let [gff-path "/Users/tristan/data/clinvar-cnv-annotation/GRCh38.gff.gz"]
       (with-open [r (-> gff-path
                         io/input-stream
                         GZIPInputStream.
                         io/reader)]
         (->> (csv/read-csv r :separator \tab)
              (filter #(< 3 (count %))) ; remove comments
              (map gff-attributes->map)
              #_(map :gff/type)
              #_frequencies
              (filter #(= "8481" (:ncbi/gene-id %)))
              (into []))))))


  (->> kmt2c-annotation
       (map :gff/type)
       frequencies
       tap>)
  (tap>
   (update-vals
    (group-by :gff-attrs/Parent
              (filter #(= "exon" (:gff/type %)) ofd1-annotation))
    (fn [v] (->> (map ::hash v) sort seq->hash))))

  (->> ofd1-annotation
       (filter #(= "mRNA" (:gff/type %)))
       (map #(select-keys % [:gff-attrs/ID :gff-attrs/Name]))
       tap>)
  
  ;; Do all mRNAs have transcript_ids accessioned in genbank/refseq/nuccore whatever?
  ;; There's a :gff-attrs/ID field with an rna- prefix for mRNAs
  ;; But also a :gff-attrs/Name field with the transcript accession
  ;; Would prefer to use the terse accession if possible
  
  ;; Similarly Exons have a :gff-attrs/transcript_id that refers to the
  ;; transcript accession
  ;; as well as a :gff-atrrs/Parent that uses the rna- notation. They have an
  ;; ID field and no

  ;; https://github.com/The-Sequence-Ontology/Specifications/blob/master/gff3.md

  ;; Looks like ID and Parent are the way to go.
  ;; may need to use the annotation source that corresponds most closely to the 

  (time
   (let [gff-path "/Users/tristan/data/clinvar-cnv-annotation/GRCh38.gff.gz"]
     (with-open [r (-> gff-path
                       io/input-stream
                       GZIPInputStream.
                       io/reader)]
       (->> (csv/read-csv r :separator \tab)
            (filter #(< 3 (count %)))   ; remove comments
            (map gff-attributes->map)
            #_(map :gff/type)
            #_frequencies
            (filter #(= "gene" (:gff/type %)))
            (take 3)
            (into [])
            tap>))))


  (time
   (let [gff-path "/Users/tristan/data/clinvar-cnv-annotation/GRCh38.gff.gz"]
     (with-open [r (-> gff-path
                       io/input-stream
                       GZIPInputStream.
                       io/reader)]
       (->> (csv/read-csv r :separator \tab)
            (filter #(< 3 (count %)))   ; remove comments
            (map gff-attributes->map)
            #_(map :gff/type)
            #_frequencies
            (filter #(= "gene" (:gff/type %)))
            (take 3)
            (into [])
            tap>))))

  

  (time
   (let [gff-path "/Users/tristan/data/clinvar-cnv-annotation/ensembl_GRCh38.gff.gz"]
     (with-open [r (-> gff-path
                       io/input-stream
                       GZIPInputStream.
                       io/reader)]
       (->> (csv/read-csv r :separator \tab)
            (filter #(< 3 (count %)))   ; remove comments
            (map gff-attributes->map)
            #_(filter #(and (= "mRNA" (:gff/type %))
                            (= "protein_coding" (:gff-attrs/biotype %))))
            (filter #(and (= "exon" (:gff/type %))
                          #_(= "protein_coding" (:gff-attrs/biotype %))))
            (take 3)
            (into [])
            #_count
            tap>))))

  (time
   (def ensembl-ofd1-children
     (let [gff-path "/Users/tristan/data/clinvar-cnv-annotation/ensembl_GRCh38.gff.gz"]
       (with-open [r (-> gff-path
                         io/input-stream
                         GZIPInputStream.
                         io/reader)]
         (->> (csv/read-csv r :separator \tab)
              (filter #(< 3 (count %))) ; remove comments
              (map gff-attributes->map)
              (filter #(= "gene:ENSG00000046651" (:gff-attrs/Parent %)))
              (into []))))))

  (->> ensembl-ofd1-children
       (map :gff/type)
       frequencies
       tap>)

  (time
   (def ensembl-ofd1-grandchildren
     (let [gff-path "/Users/tristan/data/clinvar-cnv-annotation/ensembl_GRCh38.gff.gz"
           child-ids (set (map :gff-attrs/ID ensembl-ofd1-children))]
       (with-open [r (-> gff-path
                         io/input-stream
                         GZIPInputStream.
                         io/reader)]
         (->> (csv/read-csv r :separator \tab)
              (filter #(< 3 (count %))) ; remove comments
              (map gff-attributes->map)
              (filter #(child-ids (:gff-attrs/Parent %)))
              (into []))))))

  (->> ensembl-ofd1-grandchildren
       (filter #(= "exon" (:gff/type %)))
       (map :gff-attrs/exon_id)
       set
       count)

  (->> ensembl-ofd1-grandchildren
       (filter #(= "exon" (:gff/type %)))
       (group-by #(select-keys % [:gff/start :gff/end]))
       (map #(set (map :gff-attrs/exon_id (val %))))
       (filter #(< 2 (count %)))
       tap>)

  (->> ensembl-ofd1-grandchildren
       (filter #(= "exon" (:gff/type %)))
       (filter #(#{"ENSE00003672386" "ENSE00003921251" "ENSE00003476711"}
                 (:gff-attrs/exon_id %)))
       (group-by #(select-keys % [:gff/start :gff/end]))
       tap>)

  (->> ensembl-ofd1-grandchildren
       (filter #(= "exon" (:gff/type %)))
       (group-by :gff-attrs/exon_id)
       tap>)

  (time
   (let [gff-path "/Users/tristan/data/clinvar-cnv-annotation/GRCh38.gff.gz"]
     (with-open [r (-> gff-path
                       io/input-stream
                       GZIPInputStream.
                       io/reader)]
       (->> (line-seq r)
            #_(filter #(< 3 (count %))) ; remove comments
            #_(map gff-attributes->map)
            (take 20)
            (into [])
            tap>))))


  (clojure.pprint/pprint
   (->>
    {"replication_regulatory_region" 11, "sequence_secondary_structure" 14, "V_gene_segment" 664, "regulatory_region" 2, "DNaseI_hypersensitive_site" 177, "vault_RNA" 4, "telomerase_RNA" 1, "lnc_RNA" 32088, "primary_transcript" 2139, "CAAT_signal" 6, "sequence_feature" 1964, "tRNA" 691, "conserved_region" 138, "promoter" 439, "direct_repeat" 20, "mRNA" 144447, "biological_region" 83821, "sequence_alteration_artifact" 10, "nucleotide_motif" 623, "snRNA" 172, "cDNA_match" 25870, "nucleotide_cleavage_site" 4, "pseudogene" 19251, "snoRNA" 1300, "C_gene_segment" 44, "tandem_repeat" 64, "TATA_box" 30, "imprinting_control_region" 2, "gene" 47876, "D_gene_segment" 61, "replication_start_site" 3, "protein_binding_site" 1416, "repeat_instability_region" 62, "centromere" 24, "Y_RNA" 4, "CAGE_cluster" 96, "microsatellite" 5, "non_allelic_homologous_recombination_region" 514, "minisatellite" 13, "region" 709, "sequence_comparison" 1, "enhancer" 81572, "meiotic_recombination_region" 368, "matrix_attachment_site" 18, "RNase_P_RNA" 2, "epigenetically_modified_region" 12, "transcript" 14995, "origin_of_replication" 87, "rRNA" 80, "scRNA" 4, "J_gene_segment" 128, "match" 107863, "response_element" 20, "GC_rich_promoter_region" 15, "enhancer_blocking_element" 59, "CDS" 1836136, "insulator" 12, "repeat_region" 10, "recombination_feature" 632, "ncRNA" 54, "transcriptional_cis_regulatory_region" 1935, "locus_control_region" 14, "antisense_RNA" 24, "sequence_alteration" 30, "dispersed_repeat" 5, "silencer" 4917, "mobile_genetic_element" 204, "D_loop" 1, "exon" 2301289, "mitotic_recombination_region" 54, "chromosome_breakpoint" 14, "miRNA" 3218, "RNase_MRP_RNA" 1}
    (sort-by val)
    reverse))

  )


;; https://ftp.ncbi.nlm.nih.gov/genomes/all/annotation_releases/9606/GCF_000001405.40-RS_2023_03/GCF_000001405.40_GRCh38.p14_genomic.gff.gz

;; https://ftp.ncbi.nlm.nih.gov/genomes/all/GCF/000/001/405/GCF_000001405.25_GRCh37.p13/GCF_000001405.25_GRCh37.p13_genomic.gff.gz


