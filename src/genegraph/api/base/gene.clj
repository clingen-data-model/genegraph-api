(ns genegraph.api.base.gene
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.event :as event]
            [io.pedestal.log :as log]
            [genegraph.api.protocol :as ap]))

;; symbol -> skos:prefLabel ? rdf:label
;; name -> skos:altLabel 
;; everything else that needs to be searchable -> skos:hiddenLabel
;; uri -> munge of entrez id and https://www.ncbi.nlm.nih.gov/gene/

(def source-uri "https://www.genenames.org/")
(def hgnc  "https://www.genenames.org")
(def ensembl  "https://www.ensembl.org")
(def entrez-gene-root "https://identifiers.org/ncbigene:")


(def locus-types {"immunoglobulin gene" "http://purl.obolibrary.org/obo/SO_0002122"
                  "T cell receptor gene" "http://purl.obolibrary.org/obo/SO_0002099"
                  "RNA, micro" "http://purl.obolibrary.org/obo/SO_0000276"
                  "gene with protein product" "http://purl.obolibrary.org/obo/SO_0001217"
                  "RNA, transfer" "http://purl.obolibrary.org/obo/SO_0000253"
                  "pseudogene" "http://purl.obolibrary.org/obo/SO_0000336"
                  "RNA, long non-coding" "http://purl.obolibrary.org/obo/SO_0001877"
                  "virus integration site" "http://purl.obolibrary.org/obo/SO_0000946?"
                  "RNA, vault" "http://purl.obolibrary.org/obo/SO_0000404"
                  "endogenous retrovirus" "http://purl.obolibrary.org/obo/SO_0000100"
                  "RNA, small nucleolar" "http://purl.obolibrary.org/obo/SO_0000275"
                  "T cell receptor pseudogene" "http://purl.obolibrary.org/obo/SO_0002099"
                  "immunoglobulin pseudogene" "http://purl.obolibrary.org/obo/SO_0002098"
                  "RNA, small nuclear" "http://purl.obolibrary.org/obo/SO_0000274"
                  "readthrough" "http://purl.obolibrary.org/obo/SO_0000883"
                  "RNA, ribosomal" "http://purl.obolibrary.org/obo/SO_0000252"
                  "RNA, misc" "http://purl.obolibrary.org/obo/SO_0000356"})

(defn gene-iri [gene]
  (str entrez-gene-root (:entrez_id gene)))

(defn gene-types [gene]
  (let [base-types [:so/Gene :so/SequenceFeature]]
    (mapv rdf/resource
          (if-let [locus-type (locus-types (:locus_type gene))]
            (conj base-types locus-type)
            base-types))))

(defn gene-as-triple [gene]
  (let [uri (gene-iri gene)
        hgnc-id (:hgnc_id gene)
        hgnc-iri (rdf/resource
                  (s/replace (:hgnc_id gene)
                             "HGNC"
                             "https://identifiers.org/hgnc"))
        ensembl-iri (rdf/resource
                     (str "http://rdf.ebi.ac.uk/resource/ensembl/"
                          (:ensembl_gene_id gene)))]
    (remove nil?
            (concat [[uri :skos/prefLabel (:symbol gene)]
                     [uri :rdfs/label (:symbol gene)]
                     [uri :skos/altLabel (:name gene)]
                     (when-let [loc (:location gene)] [uri :so/chromosome-band loc])
                     [uri :owl/sameAs (rdf/resource hgnc-id)]
                     [hgnc-id :dc/source (rdf/resource hgnc)]
                     [uri :owl/sameAs ensembl-iri]
                     [uri :owl/sameAs hgnc-iri]
                     [ensembl-iri :dc/source (rdf/resource ensembl)]]
                    (map #(vector uri :rdf/type %) (gene-types gene))
                    (map #(vector uri :skos/hiddenLabel %)
                         (:alias_symbol gene))
                    (map #(vector uri :skos/hiddenLabel %)
                         (:prev_name gene))
                    (map #(vector uri :skos/hiddenLabel %)
                         (:prev_symbol gene))))))

(defn genes-as-triple [genes-json]
  (let [genes (filter :entrez_id (get-in genes-json [:response :docs]))]
    (conj (mapcat gene-as-triple genes)
          ["https://www.genenames.org/" :rdf/type :void/Dataset])))

#_(defmethod rdf/as-model :genegraph.api.base/hgnc [{:keys [source]}]
  (log/info :fn ::rdf/as-model :format :genegraph.api.base/hgnc)
  (with-open [r (io/reader (storage/->input-stream source))]
    (-> (json/read r :key-fn keyword)
        genes-as-triple
        rdf/statements->model)))

(defmethod rdf/as-model :genegraph.api.base/hgnc [event]
  (log/info :fn ::rdf/as-model :format :genegraph.api.base/hgnc)
  (-> event
      ::document
      genes-as-triple
      rdf/statements->model))

(defn hgnc-gene->index-doc [gene]
  {:iri (gene-iri gene)
   :source source-uri
   :symbols (into
             []
             (concat
              [(:symbol gene)]
              (:prev_symbol gene)
              (:alias_symbol gene)))
   :labels (into
            []
            (cons
             (:name gene)
             (:prev_name gene)))
   :types (mapv str (gene-types gene))})

(defn add-document [event]
  (println "adding-document")
  (with-open [r  (-> event ::event/data :source storage/->input-stream io/reader)]
    (assoc event ::document (json/read r :key-fn keyword))))

(comment 
  (tap>
   (hgnc-gene->index-doc
    {:gene_group ["Immunoglobulin like domain containing"], :mane_select ["ENST00000263100.8" "NM_130786.4"], :omim_id ["138670"], :pubmed_id [2591067], :ccds_id ["CCDS12976"], :hgnc_id "HGNC:5", :symbol "A1BG", :name "alpha-1-B glycoprotein", :gene_group_id [594], :agr "HGNC:5", :ucsc_id "uc002qsd.5", :rgd_id ["RGD:69417"], :locus_group "protein-coding gene", :entrez_id "1", :mgd_id ["MGI:2152878"], :refseq_accession ["NM_130786"], :ensembl_gene_id "ENSG00000121410", :merops "I43.950", :status "Approved", :locus_type "gene with protein product", :vega_id "OTTHUMG00000183507", :date_modified "2023-01-20", :uniprot_ids ["P04217"], :uuid "fc83f9c0-da0f-4f8e-bfc7-5ef6b7ee052e", :location "19q13.43", :date_approved_reserved "1989-06-30"})))

(defmethod ap/process-base-event :genegraph.api.base/load-genes
  [event]
  (println "processing base event genes")
  (let [event-with-parsed-data (add-document event)
        m (rdf/as-model (::event/data event-with-parsed-data))]
    (reduce
     (fn [e g] (let [d (hgnc-gene->index-doc g)]
                 (event/store e :text-index (:iri d) d)))
     (event/store event-with-parsed-data :api-tdb "https://www.genenames.org/" m)
     (get-in event-with-parsed-data [::document :response :docs]))))

(comment
  (time
   (def m2
     (-> (rdf/as-model
         {:format :genegraph.api.base/hgnc
          :source (io/file
                   "/Users/tristan/code/genegraph-api/data/base/hgnc.json")}))))

  (+ 1 1)

  (spit "/Users/tristan/data/genegraph-neo/genes-model.ttl"
        (rdf/to-turtle m2))
  
  
  (let [genes-q (rdf/create-query "select ?g where { ?g a ?t } ")]
    (count (genes-q m2 {:t :so/Gene})))

  
  (tap>
   {:gene_group ["Immunoglobulin like domain containing"], :mane_select ["ENST00000263100.8" "NM_130786.4"], :omim_id ["138670"], :pubmed_id [2591067], :ccds_id ["CCDS12976"], :hgnc_id "HGNC:5", :symbol "A1BG", :name "alpha-1-B glycoprotein", :gene_group_id [594], :agr "HGNC:5", :ucsc_id "uc002qsd.5", :rgd_id ["RGD:69417"], :locus_group "protein-coding gene", :entrez_id "1", :mgd_id ["MGI:2152878"], :refseq_accession ["NM_130786"], :ensembl_gene_id "ENSG00000121410", :merops "I43.950", :status "Approved", :locus_type "gene with protein product", :vega_id "OTTHUMG00000183507", :date_modified "2023-01-20", :uniprot_ids ["P04217"], :uuid "fc83f9c0-da0f-4f8e-bfc7-5ef6b7ee052e", :location "19q13.43", :date_approved_reserved "1989-06-30"})
  
  (rdf/resource :skos/prefLabel)
  )


;; (defmethod add-model :hgnc-genes [event]
;;   (let [model (-> event 
;;                   :genegraph.sink.event/value
;;                   (json/parse-string true)
;;                   genes-as-triple
;;                   db/statements-to-model)]
;;     (assoc event ::q/model model )))
