(ns genegraph.api.base.clinvar
  (:require [clojure.data.xml :as xml]
            [clojure.data.zip.xml :as xml-zip]
            [clojure.zip :as zip]
            [clojure.java.io :as io]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage :as storage]
            [genegraph.api.protocol :as ap]
            [io.pedestal.log :as log])
  (:import [org.apache.jena.rdf.model Model ModelFactory]
           [java.util.zip GZIPInputStream]))

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
            :DateLastEvaluated (xml-zip/xml1->
                                n1
                                :Classification
                                (xml-zip/attr :DateLastEvaluated))
            :ReviewStatus (xml-zip/xml1->
                           n1
                           :Classification
                           :ReviewStatus
                           xml-zip/text)
            :Classification (xml-zip/xml1->
                             n1
                             :Classification
                             :GermlineClassification
                             xml-zip/text)
            :Assertion  (xml-zip/xml1->
                         n1
                         :Assertion
                         xml-zip/text)))
   (xml-zip/xml-> n
                  :ClassifiedRecord
                  :ClinicalAssertionList
                  :ClinicalAssertion)))

(defn clinvar-xml->intermediate-model [xml-node]
  (let [z (zip/xml-zip xml-node)]
    (assoc (clinvar-zip-node->allele z)
           :classifications (clinvar-zip-node->classifications z))))

#_(with-open [clinvar-stream (GZIPInputStream. (io/input-stream clinvar-xml-path))]
     (->> (:content (xml/parse clinvar-stream))
          (map clinvar/clinvar-xml->intermediate-model)
          (filter #(or (:copy-count %)
                       (< 1000 (variation-length %))))
          (run! #(storage/write @(get-in cnv-app
                                         [:storage :resources :instance])
                                ["clinvar"
                                 (:variation-id %)]
                                %))))

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

(comment
  (tap>
   (with-open [is (->{:type :file
                      :base "data/base/"
                      :path "clinvar.xml.gz"}
                     storage/as-handle
                     io/input-stream
                     GZIPInputStream.)]
     (->> (:content (xml/parse is))
          (take 10000)
          (map clinvar-xml->intermediate-model)
          (filter #(or (:copy-count %)
                         (< 1000 (variation-length %))))
          (take 1)
          (into []))))
  (+ 1 1)
  )

(defmethod ap/process-base-event :genegraph.api.base/load-clinvar
  [event]
  (log/info :fn ::ap/process-base-event
            :dispatch :genegraph.api.base/load-clinvar
            :object-db (get-in event [::storage/storage :object-db]))
  (with-open [is (->{:type :file
                     :base "data/base/"
                     :path "clinvar.xml.gz"}
                    storage/as-handle
                    io/input-stream
                    GZIPInputStream.)]
    (->> (:content (xml/parse is))
         (take 10000)
         (map clinvar-xml->intermediate-model)
         (filter #(or (:copy-count %)
                      (< 1000 (variation-length %))))
         (take 1)
         (run! #(storage/write (get-in event [::storage/storage :object-db])
                               ["clinvar" (:variation-id %)]
                               %))))
  event)

;;placeholder for now
(defmethod rdf/as-model :genegraph.api.base/clinvar [{:keys [source]}]
  (log/info :fn ::rdf/as-model :format :genegraph.api.base/clinvar)
  (ModelFactory/createDefaultModel))
