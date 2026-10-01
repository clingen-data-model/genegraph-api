(ns cnv-lof-score
  {:nextjournal.clerk/visibility {:code :hide}}
  (:require [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage :as storage]
            [genegraph.api.hybrid-resource :as hr]
            [genegraph.api.lof-score :as lof-score]
            [genegraph.api.overlaps :as overlaps]
            [genegraph.api.base.vcf :as vcf]
            [genegraph.api.iscn :as iscn]
            [genegraph.user :as u]
            [nextjournal.clerk :as clerk]
            [charred.api :as charred]
            [clojure.java.io :as io]
            [clojure.math :as math])
  (:import [java.util.zip GZIPInputStream]))

;; ### Control set

;; Rare (<1% site frequency) autosomal coding copy number variants from exome
;; sequencing from 464,297 individuals.

^{::clerk/visibility {:result :hide}}
(def gene-disruption-set
  #{:cg/CompleteOverlap :cg/ProbableGeneDisruption})

^{::clerk/visibility {:result :hide}}
(def tdb
  @(get-in genegraph.user/api-test-app
           [:storage :api-tdb :instance]))

^{::clerk/visibility {:result :hide}}
(def object-db
  @(get-in genegraph.user/api-test-app
           [:storage :object-db :instance]))

^{::clerk/visibility {:result :hide}}
(def hybrid-db {:tdb tdb :object-db object-db})

^{::clerk/visibility {:result :hide}}
(def variants-with-lof-score
  (let [tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])
        object-db @(get-in genegraph.user/api-test-app [:storage :object-db :instance])
        hybrid-db {:tdb tdb :object-db object-db}
        q (rdf/create-query "
select ?v where {
?v a :cg/CanonicalVariant ;
:cg/CompleteOverlap ?gene ;
:ga4gh/copyChange ?change .
?lofprob :cg/feature ?gene ;
a :cg/LOFProbabilityEstimation .
}
")]
    (rdf/tx tdb
      (->> (q tdb {:change :efo/copy-number-loss})
           (mapv #(hr/hybrid-resource % hybrid-db))
           (mapv (fn [v]
                   {:variant v
                    :lof-score (lof-score/variant-lof-score v)
                    :gene-count (count (rdf/ld-> v [:cg/CompleteOverlap]))}))))))

^{::clerk/visibility {:result :hide}}
(clerk/plotly
 {:data
  [{:x (mapv :lof-score variants-with-lof-score)
    :type "histogram"}]})




^{::clerk/visibility {:result :hide}}
(def gnomad-cnv
  (let
      [tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])
       object-db @(get-in genegraph.user/api-test-app [:storage :object-db :instance])
       hybrid-db {:tdb tdb :object-db object-db}]
      (with-open [r (-> "/Users/tristan/data/genegraph-base/gnomad-cnv.vcf.gz"
                        io/input-stream
                        GZIPInputStream.)]
        (->> (charred/read-csv r :separator \tab)
             (remove #(re-find #"^#" (first %)))
             #_(take 100)
             (mapv vcf/vcf-row->map)))))

;; #### Total CNVs in gnomadCNV

(count gnomad-cnv)

;; #### Deletions ...

^{::clerk/visibility {:result :hide}}
(def gnomad-dels
  (->> gnomad-cnv
       (filter #(= "DEL" (:svtype %)))
       #_(take 10)
       (mapv #(assoc %
                     :overlaps
                     (overlaps/gene-overlaps-for-loci
                      object-db
                      [(vcf/->ga4gh-loc %)])))))


;; #### inspecting gnomAD dels

(count gnomad-dels)

;; Overall counts of gene disruptions in gnomAD CNV
(->> gnomad-dels
     (mapcat :overlaps)
     (map :overlap)
     frequencies
     (mapv (fn [[k v]] [(name k) v]))
     clerk/table)

(take 20 gnomad-dels)



^{::clerk/visibility {:result :hide}}
(defn lof-score [variant]
  (let [score-product (reduce * (map #(- 1 %) (:gene-scores variant)))]
    (Math/log (/ (- 1 score-product) score-product))))

^{::clerk/visibility {:result :hide}}
(def dels-with-lof-score
  (rdf/tx tdb
    (->> gnomad-dels
         (map #(assoc %
                      :complete-overlaps
                      (filterv (fn [g] (gene-disruption-set (:overlap g)))
                               (:overlaps %))))
         (filter #(seq (:complete-overlaps %)))
         (mapv #(assoc %
                       :gene-scores
                       (remove
                        nil?
                        (mapv (fn [o] (rdf/ld1-> (rdf/resource (:gene o) tdb)
                                                 [[:cg/feature :<] :cg/lower95CI]))
                              (:complete-overlaps %)))))
         (filter #(seq (:gene-scores %)))
         (mapv #(assoc % :lof-score (lof-score %))))))

;; #### ... with a complete overlap of a protein coding gene with a Genebayes score

(count dels-with-lof-score)

;; #### Relative size distribution

;; All gnomAD cnvs
(clerk/plotly
 {:data
  [{:x (mapv #(Long/parseLong (:svlen %)) gnomad-cnv)
    :type "histogram"}]})

;; All gnomAD cnvs < 1MB
(clerk/plotly
 {:data
  [{:x (->> (mapv #(Long/parseLong (:svlen %)) gnomad-cnv)
            (filter #(< % 1000000)))
    :type "histogram"}]})

;; All dels < 1MB
(clerk/plotly
 {:data
  [{:x (->> gnomad-cnv
            (filter #(= "DEL" (:svtype %)))
            (mapv #(Long/parseLong (:svlen %)))
            (filter #(< % 1000000)))
    :type "histogram"}]})

;; All dups < 1MB
(clerk/plotly
 {:data
  [{:x (->> gnomad-cnv
            (filter #(= "DUP" (:svtype %)))
            (mapv #(Long/parseLong (:svlen %)))
            (filter #(< % 1000000)))
    :type "histogram"}]})

;; #### Distribution of GeneBayes scores

(clerk/plotly
 {:data
  [{:x (mapv :lof-score dels-with-lof-score)
    :type "histogram"}]})

#_(clerk/plotly
 {:data
  [{:x (mapv #(count (:gene-scores %)) dels-with-lof-score)
    :type "histogram"}]})


;; ## Case Set (so far only ClinVar)

;; #### Dels in ClinVar with a complete overlap with a GeneBayes scored gene

(count variants-with-lof-score)

;; size distribution of ClinVar Variants?

;; #### ClinVar GeneBayes scores vs gnomAD

(clerk/plotly
 {:data
  [{:x (mapv :lof-score variants-with-lof-score)
    :type "histogram"
    :name "clinvar"
    :opacity 0.6}
   {:x (mapv :lof-score dels-with-lof-score)
    :type "histogram"
    :name "gnomAD"
    :opacity 0.6}]
  :layout {:barmode "overlay"}})

;; filter depending on classification in clinvar. 

;; add gene count, do we have the correct size buckets? Does this add anything to the score?

^{::clerk/visibility {:result :hide}}
(def variants-with-lof-score-and-inheritance
  (rdf/tx tdb
    (->> variants-with-lof-score
         (mapv #(assoc %
                       :inheritance
                       (if-let [i (rdf/ld1-> (:variant %)
                                             [[:cg/variant :<]
                                              [:cg/subject :<]
                                              :cg/inheritance])]
                         (rdf/->kw i)
                         :cg/NotFoundInheritance)
                       :classification (if-let [c (rdf/ld1-> (:variant %)
                                             [[:cg/variant :<]
                                              [:cg/subject :<]
                                              :cg/classification])]
                         (rdf/->kw c)
                         :cg/NoClassification)
                       :direction (if-let [c (rdf/ld1-> (:variant %)
                                                             [[:cg/variant :<]
                                                              [:cg/subject :<]
                                                              :cg/direction])]
                                         (rdf/->kw c)
                                         :cg/NoDirection))))))

^{::clerk/visibility {:result :hide}}
(def inheritance-frequencies
  (->> variants-with-lof-score-and-inheritance
       (map :inheritance)
       frequencies))

;; ClinVar Variants by inheritance
(->> variants-with-lof-score-and-inheritance
     (map :inheritance)
     frequencies
     (sort-by val)
     reverse
     (mapv (fn [[k v]] [(name k) v]))
     clerk/table)

^{::clerk/visibility {:result :hide}}
(def useable-inheritance
  #{:cg/DeNovoVariant
    :cg/MaternalInheritance
    :cg/PaternalInheritance
    :cg/Inherited
    :cg/Biparental})

^{::clerk/visibility {:result :hide}}
(def inherited
  #{:cg/MaternalInheritance
    :cg/PaternalInheritance
    :cg/Inherited
    :cg/Biparental})

^{::clerk/visibility {:result :hide}}
(def de-novo
  #{:cg/DeNovoVariant})

^{::clerk/visibility {:result :hide}}
(def variants-with-useable-inheritance
  (->> variants-with-lof-score-and-inheritance
       (filterv #(useable-inheritance (:inheritance %)))))

;; ClinVar variants with inheritance info
(rdf/tx tdb
  (->> variants-with-useable-inheritance
       (mapv #(rdf/ld1-> (:variant %)
                         [[:cg/variant :<]
                          [:cg/subject :<]
                          :cg/submitter
                          :rdfs/label]))
       frequencies
       (sort-by val)
       reverse
       clerk/table))

;; clinvar variants with useable inheritance

;; confirmation biases? when is inheritance information
;; tested for?

(clerk/plotly
 {:data
  [{:x (mapv :lof-score variants-with-lof-score)
    :type "histogram"
    :name "without inheritance"
    :opacity 0.6}
   {:x (->> variants-with-useable-inheritance
            (mapv :lof-score))
    :type "histogram"
    :name "with inheritance"
    :opacity 0.6}]
  :layout {:barmode "overlay"}})

(clerk/plotly
 {:data
  [{:x (mapv :lof-score variants-with-useable-inheritance)
    :type "histogram"
    :name "ClinVar (with inheritance info)"
    :opacity 0.6}
   {:x (->> dels-with-lof-score
            (mapv :lof-score))
    :type "histogram"
    :name "gnomAD"
    :opacity 0.6}]
  :layout {:barmode "overlay"}})

;; compare vs inherited / denovo 

(clerk/plotly
 {:data
  [{:x (->> variants-with-useable-inheritance
            (filter #(de-novo (:inheritance %)))
            (mapv :lof-score))
    :type "histogram"
    :name "ClinVar (with inheritance info)"
    :opacity 0.6}
   {:x (->> dels-with-lof-score
            (mapv :lof-score))
    :type "histogram"
    :name "gnomAD"
    :opacity 0.6}]
  :layout {:barmode "overlay"}})

(clerk/plotly
 {:data
  [{:x (->> variants-with-useable-inheritance
            (filter #(= :cg/Supports (:direction %)))
            (mapv :lof-score))
    :type "histogram"
    :name "ClinVar (with inheritance info)"
    :opacity 0.6}
   {:x (->> dels-with-lof-score
            (mapv :lof-score))
    :type "histogram"
    :name "gnomAD"
    :opacity 0.6}]
  :layout {:barmode "overlay"}})

;; #### Considering pathogenicity in ClinVar
^{::clerk/visibility {:result :hide}}
(defn lof-score-in-range [{:keys [lof-score]}]
  (and (< -11.0 lof-score)
       (< lof-score 3.5)))

(clerk/plotly
 {:data
  [{:x (->> variants-with-lof-score-and-inheritance
            (filter #(= :cg/Supports (:direction %)))
            (mapv :lof-score))
    :type "histogram"
    :name "ClinVar P/LP"
    :opacity 0.6}
   {:x (->> variants-with-lof-score-and-inheritance
            (filter #(= :cg/Refutes (:direction %)))
            (mapv :lof-score))
    :type "histogram"
    :name "ClinVar B/LB"
    :opacity 0.6}
   {:x (->> dels-with-lof-score
            (mapv :lof-score))
    :type "histogram"
    :name "gnomAD"
    :opacity 0.6}]
  :layout {:barmode "overlay"}})

;; #### Clipping extreme values

(clerk/plotly
 {:data
  [{:x (->> variants-with-lof-score-and-inheritance
            (filter #(= :cg/Supports (:direction %)))
            (filter lof-score-in-range)
            (mapv :lof-score))
    :type "histogram"
    :name "ClinVar P/LP"
    :opacity 0.6}
   {:x (->> variants-with-lof-score-and-inheritance
            (filter #(= :cg/Refutes (:direction %)))
            (filter lof-score-in-range)
            (mapv :lof-score))
    :type "histogram"
    :name "ClinVar B/LB"
    :opacity 0.6}
   {:x (->> dels-with-lof-score
            (filter lof-score-in-range)
            (mapv :lof-score))
    :type "histogram"
    :name "gnomAD"
    :opacity 0.6}]
  :layout {:barmode "overlay"}})

(rdf/tx tdb
  (->> variants-with-lof-score-and-inheritance
       (sort-by :lof-score)
       reverse
       (take 10)
       (mapv (fn [v] [(:lof-score v)
                      (-> (:variant v)
                          (hr/hybrid-resource {:tdb tdb :object-db object-db})
                          :rdfs/label)
                      #_(str (:variant v))]))
       clerk/table))


(->> variants-with-lof-score-and-inheritance
     (take 1))

 
;; Create a display that includes evaluation dates

;; analyze gnomadSV data set

;; distribution of 

;; denovo vs inherited

(clerk/plotly
 {:data
  [{:x (->> variants-with-useable-inheritance
            (filter #(de-novo (:inheritance %)))
            (mapv :lof-score))
    :type "histogram"
    :name "ClinVar (denovo)"
    :opacity 0.6}
   {:x (->> variants-with-useable-inheritance
            (filter #(inherited (:inheritance %)))
            (mapv :lof-score))
    :type "histogram"
    :name "ClinVar (inherited)"
    :opacity 0.6}
   {:x (->> dels-with-lof-score
            (filter lof-score-in-range)
            (mapv :lof-score))
    :type "histogram"
    :name "gnomAD"
    :opacity 0.6}]
  :layout {:barmode "overlay"}})

(def genebayes-scores 
  (let [q (rdf/create-query "select ?x where { ?x a :cg/LOFProbabilityEstimation } ")
        haplo-q (rdf/create-query "select ?str where 
{ ?prop :cg/feature ?feature ;
  a :cg/GeneticConditionMechanismProposition ;
  :cg/mechanism :cg/Haploinsufficiency .
  ?stmt :cg/subject ?prop ;
  :cg/evidenceStrength ?str . } ")
        gv-q (rdf/create-query "select ?moi where 
{ ?feature :owl/sameAs ?gene .
  ?prop :cg/subjectGene ?gene ;
   a :cg/GeneDiseaseValidityProposition ;
  :cg/modeOfInheritanceQualifier ?moi .
  ?stmt :cg/proposition ?prop ;
  :cg/classification ?str .  } ")
        gencc-q (rdf/create-query "select ?moi where 
{ ?prop :cg/gene ?feature ;
   a :cg/GeneValidityProposition ;
  :cg/modeOfInheritance ?moi . } ")]
    (rdf/tx tdb
      (->> (q tdb)
           (mapv (fn [s]
                   (when-let [f (rdf/ld1-> s [:cg/feature])]
                     {:symbol (rdf/ld1-> f [:skos/prefLabel])
                      :score (rdf/ld1-> s [:cg/lower95CI])
                      :dosage (some-> (haplo-q tdb {:feature f})
                                      first
                                      rdf/->kw)
                      :gv-moi (mapv rdf/->kw (gv-q tdb {:feature f}))
                      :gencc-moi (mapv rdf/->kw (gencc-q tdb {:feature f}))})))
           (remove nil?)
           (sort-by :score)
           reverse))))

;; top 100 genebayes scores

(->> genebayes-scores
     (map (fn [{:keys [symbol score dosage gv-moi gencc-moi]}]
            [symbol score dosage gv-moi gencc-moi]))
     (take 100)
     clerk/table)

#_(with-open [w (io/writer "/Users/tristan/Desktop/high-genebayes-without-dosage-score.csv")]
  (->> genebayes-scores
       (filter #(< 0.1 (:score %)))
       (remove #(:dosage %))
       (map (fn [{:keys [symbol score dosage gv-moi gencc-moi]}]
              [symbol score gv-moi gencc-moi]))
       (cons ["Gene" "GeneBayes Score" "ClinGen GV MOI" "GenCC MOI"])
       (charred/write-csv w)))

;; bottom 30 genebayes scores

(->> genebayes-scores
     reverse
     (map (fn [{:keys [symbol score dosage]}]
            [symbol score dosage]))
     (take 30)
     clerk/table)

;; bottom 30 genebayes scores with DS 3 score

(->> genebayes-scores
     reverse
     (filter #(and (< (:score %) 0.01)
                   (= :cg/DosageSufficientEvidence (:dosage %))))
     (map (fn [{:keys [symbol score dosage]}]
            [symbol score dosage]))
     (take 30)
     clerk/table)

;; ### GeneBayes score distribution

(clerk/plotly
 {:data
  [{:x (map :score genebayes-scores)
    :type "histogram"
    :name "GeneBayes score distribution"}]})

(defn variant-lof-score [lof-scores]
  (let [score-product (reduce * (map #(- 1 %) lof-scores))]
    (Math/log (/ (- 1 score-product) score-product))))


(variant-lof-score [0.00010438 0.00014076 0.283667 0.275075])

(variant-lof-score (repeat 50 0.0010438))

(let [q (rdf/create-query
         "
select ?feature where 
{ ?x a :cg/GeneDiseaseValidityProposition ; 
  :cg/subjectGene ?gene .
   ?feature :owl/sameAs ?gene .}")]
  (rdf/tx tdb
    (->> (q tdb)
         #_count
         (take 1)
         #_(mapv #(rdf/ld1-> % [:cg/subjectGene])))))


(->> variants-with-lof-score
     first)

;; LOF Score / Number of Genes

(clerk/plotly
 {:data [{:x (->> variants-with-lof-score-and-inheritance
                  (filter #(= :cg/Supports (:direction %)))
                  (take 1000)
                  (map :gene-count))
          :y (->> variants-with-lof-score-and-inheritance
                  (filter #(= :cg/Supports (:direction %)))
                  (take 1000)
                  (map :lof-score))
          :mode "markers"
          :marker {:size 2}
          :type "scatter"}
         {:x (->> variants-with-lof-score-and-inheritance
                  (filter #(= :cg/Refutes (:direction %)))
                  (take 1000)
                  (map :gene-count))
          :y (->> variants-with-lof-score-and-inheritance
                  (filter #(= :cg/Supports (:direction %)))
                  (take 1000)
                  (map :lof-score))
          :mode "markers"
          :marker {:size 2}
          :type "scatter"}]})

(->> variants-with-lof-score-and-inheritance
     (filter #(and (< (:lof-score %) -7.0 )
                   (< 35 (:gene-count %))
                   (= :cg/Supports (:direction %)))))


;; Comparison against Mayo set
(def mayo-variants
  (with-open [r (io/reader "/Users/tristan/data/mayo.csv")]
    (->> (charred/read-csv r)
         #_(take 5)
         (mapv first)
         iscn/variant-set)))

(def mayo-variants-with-lof-score
  (rdf/tx tdb
    (->> mayo-variants
         :observations
         (filter (fn [{:keys [svtype variant]}]
                   (and (= "copy number loss" svtype)
                        variant)))
         #_(take 5)
         (mapv #(assoc %
                       :overlaps
                       (overlaps/gene-overlaps-for-loci
                        object-db
                        [(get-in % [:variant :ga4gh/location])])))
         (mapv (fn [v] (assoc v
                              :disruptive-overlaps
                              (filterv #(gene-disruption-set (:overlap %))
                                       (:overlaps v)))))
         (filterv (fn [v] (seq (:disruptive-overlaps v))))
         (mapv #(assoc %
                       :gene-scores
                       (remove
                        nil?
                        (mapv (fn [o] (rdf/ld1-> (rdf/resource (:gene o) tdb)
                                                 [[:cg/feature :<] :cg/lower95CI]))
                              (:disruptive-overlaps %)))))
         (filterv (fn [v] (seq (:gene-scores v))))
         (mapv (fn [v] (assoc v :lof-score (lof-score v)))))))

(clerk/plotly
 {:data
  [#_{:x (mapv :lof-score variants-with-lof-score)
    :type "histogram"
    :name "clinvar"
      :opacity 0.6}
   {:x (mapv :lof-score mayo-variants-with-lof-score)
    :type "histogram"
    :name "mayo"
    :opacity 0.6}
   {:x (mapv :lof-score dels-with-lof-score)
    :type "histogram"
    :name "gnomAD"
    :opacity 0.6}]
  :layout {:barmode "overlay"
           :shapes [{:type "line"
                     :x0 sp95-gnomadcnv
                     :x1 sp95-gnomadcnv
                     :y0 0
                     :y1 1
                     :yref "paper"
                     :line {:color "black"
                            :width 2
                            :dash "dash"}}
                    {:type "line"
                     :x0 sp99-gnomadcnv
                     :x1 sp99-gnomadcnv
                     :y0 0
                     :y1 1
                     :yref "paper"
                     :line {:color "black"
                            :width 2
                            :dash "dash"}}
                    {:type "line"
                     :x0 sp999-gnomadcnv
                     :x1 sp999-gnomadcnv
                     :y0 0
                     :y1 1
                     :yref "paper"
                     :line {:color "black"
                            :width 2
                            :dash "dash"}}]
           :annotations [{:x sp95-gnomadcnv
                          :y 1
                          :yref "paper"
                          :text "95%"
                          :showarrow false
                          :yanchor "bottom"}
                         {:x sp99-gnomadcnv
                          :y 1
                          :yref "paper"
                          :text "99%"
                          :showarrow false
                          :yanchor "bottom"}
                         {:x sp999-gnomadcnv
                          :y 1
                          :yref "paper"
                          :text "99.9%"
                          :showarrow false
                          :yanchor "bottom"}]}})


(tap> mayo-variants-with-lof-score)

(clerk/plotly
   {:data
    [#_{:x (mapv :lof-score variants-with-lof-score)
        :type "histogram"
        :name "clinvar"
        :opacity 0.6}
     {:x (mapv :lof-score (filter #(= :de-novo (:inheritance %))
                                  mayo-variants-with-lof-score))
      :type "histogram"
      :name "mayo"
      :opacity 0.6}
     {:x (mapv :lof-score dels-with-lof-score)
      :type "histogram"
      :name "gnomAD"
      :opacity 0.6}]
    :layout {:barmode "overlay"
             :shapes [{:type "line"
                       :x0 sp95-gnomadcnv
                       :x1 sp95-gnomadcnv
                       :y0 0
                       :y1 1
                       :yref "paper"
                       :line {:color "black"
                              :width 2
                              :dash "dash"}}
                      {:type "line"
                       :x0 sp99-gnomadcnv
                       :x1 sp99-gnomadcnv
                       :y0 0
                       :y1 1
                       :yref "paper"
                       :line {:color "black"
                              :width 2
                              :dash "dash"}}
                      {:type "line"
                       :x0 sp999-gnomadcnv
                       :x1 sp999-gnomadcnv
                       :y0 0
                       :y1 1
                       :yref "paper"
                       :line {:color "black"
                              :width 2
                              :dash "dash"}}]
             :annotations [{:x sp95-gnomadcnv
                            :y 1
                            :yref "paper"
                            :text "95%"
                            :showarrow false
                            :yanchor "bottom"}
                           {:x sp99-gnomadcnv
                            :y 1
                            :yref "paper"
                            :text "99%"
                            :showarrow false
                            :yanchor "bottom"}
                           {:x sp999-gnomadcnv
                            :y 1
                            :yref "paper"
                            :text "99.9%"
                            :showarrow false
                            :yanchor "bottom"}]}})


^{::clerk/visibility {:result :hide}}
(defn sampling-probability [percentile variant-set]
  (-> (sort-by :lof-score  variant-set)
      (nth (math/round (* percentile (count variant-set))))
      :lof-score))

^{::clerk/visibility {:result :hide}}
(def sp95-gnomadcnv
  (sampling-probability 0.95 dels-with-lof-score))

^{::clerk/visibility {:result :hide}}
(def sp99-gnomadcnv
  (sampling-probability 0.99 dels-with-lof-score))

^{::clerk/visibility {:result :hide}}
(def sp999-gnomadcnv
  (sampling-probability 0.999 dels-with-lof-score))

;; #### Sampling probabilities

(clerk/table [["probability" "gnomAD CNV" "mayo CNVs"]
              ["25%"
               (sampling-probability 0.25 dels-with-lof-score)
               (sampling-probability 0.25 mayo-variants-with-lof-score)]
              ["50%"
               (sampling-probability 0.50 dels-with-lof-score)
               (sampling-probability 0.50 mayo-variants-with-lof-score)]
              ["75%"
               (sampling-probability 0.75 dels-with-lof-score)
               (sampling-probability 0.75 mayo-variants-with-lof-score)]
              ["95%" sp95-gnomadcnv (sampling-probability 0.95 mayo-variants-with-lof-score)]
              ["99%" sp99-gnomadcnv (sampling-probability 0.99 mayo-variants-with-lof-score)]
              ["99.9%" sp999-gnomadcnv (sampling-probability 0.999 mayo-variants-with-lof-score)]])

#_(rdf/tx tdb
    (->> gnomad-dels
         (map #(assoc %
                      :complete-overlaps
                      (filterv (fn [g] (gene-disruption-set (:overlap g)))
                               (:overlaps %))))
         (filter #(seq (:complete-overlaps %)))
         (mapv #(assoc %
                       :gene-scores
                       (remove
                        nil?
                        (mapv (fn [o] (rdf/ld1-> (rdf/resource (:gene o) tdb)
                                                 [[:cg/feature :<] :cg/lower95CI]))
                              (:complete-overlaps %)))))
         (filter #(seq (:gene-scores %)))
         (mapv #(assoc % :lof-score (lof-score %)))))

#_(def gnomad-dels
    (->> gnomad-cnv
         (filter #(= "DEL" (:svtype %)))
         #_(take 10)
         (mapv #(assoc %
                       :overlaps
                       (overlaps/gene-overlaps-for-loci
                        object-db
                        [(vcf/->ga4gh-loc %)])))))


(def gnomad-sv
  (with-open [r (-> "/Users/tristan/data/gnomad/gnomad-sv.vcf.gz"
                         io/input-stream
                         GZIPInputStream.)]
         (->> (charred/read-csv r :separator \tab)
              (remove #(re-find #"^#" (first %)))
              #_(take 100000)
              (map vcf/vcf-row->map)
              (filterv (fn [{:keys [alt filter svlen predicted_lof]}]
                        (and (= "<DEL>" alt)
                             svlen
                             (< 1000 (Long/parseLong svlen))
                             predicted_lof))))))

(def gnomad-sv-overlaps
  (mapv #(assoc %
                :overlaps
                (overlaps/gene-overlaps-for-loci
                 object-db
                 [(vcf/->ga4gh-loc %)]))
        gnomad-sv))

(def gnomad-sv-with-lof-score
  (rdf/tx tdb
    (->> gnomad-sv-overlaps
         (mapv (fn [v] (assoc v
                              :disruptive-overlaps
                              (filterv #(gene-disruption-set (:overlap %))
                                       (:overlaps v)))))
         (filterv (fn [v] (seq (:disruptive-overlaps v))))
         (mapv #(assoc %
                       :gene-scores
                       (remove
                        nil?
                        (mapv (fn [o] (rdf/ld1-> (rdf/resource (:gene o) tdb)
                                                 [[:cg/feature :<] :cg/lower95CI]))
                              (:disruptive-overlaps %)))))
         (filterv (fn [v] (seq (:gene-scores v))))
         (mapv (fn [v] (assoc v :lof-score (lof-score v)))))))


;; #### Sampling probabilities

;; variants with LOF score
(count gnomad-sv-with-lof-score)

(clerk/table [["probability" "gnomAD CNV" "gnomad SV" "mayo CNVs"]
              ["25%"
               (sampling-probability 0.25 dels-with-lof-score)
               (sampling-probability 0.25 gnomad-sv-with-lof-score)
               (sampling-probability 0.25 mayo-variants-with-lof-score)]
              ["50%"
               (sampling-probability 0.50 dels-with-lof-score)
               (sampling-probability 0.50 gnomad-sv-with-lof-score)
               (sampling-probability 0.50 mayo-variants-with-lof-score)]
              ["75%"
               (sampling-probability 0.75 dels-with-lof-score)
               (sampling-probability 0.75 gnomad-sv-with-lof-score)
               (sampling-probability 0.75 mayo-variants-with-lof-score)]
              ["95%"
               sp95-gnomadcnv
               (sampling-probability 0.95 gnomad-sv-with-lof-score)
               (sampling-probability 0.95 mayo-variants-with-lof-score)]
              ["99%"
               sp99-gnomadcnv
               (sampling-probability 0.99 gnomad-sv-with-lof-score)
               (sampling-probability 0.99 mayo-variants-with-lof-score)]
              ["99.9%"
               sp999-gnomadcnv
               (sampling-probability 0.999 gnomad-sv-with-lof-score)
               (sampling-probability 0.999 mayo-variants-with-lof-score) ]])
(def gnomad-sv-sp
  {:p95 (sampling-probability 0.95 gnomad-sv-with-lof-score)
   :p99 (sampling-probability 0.99 gnomad-sv-with-lof-score)
   :p999 (sampling-probability 0.999 gnomad-sv-with-lof-score)})

(clerk/plotly
 {:data
  [#_{:x (mapv :lof-score variants-with-lof-score)
      :type "histogram"
      :name "clinvar"
      :opacity 0.6}
   {:x (mapv :lof-score mayo-variants-with-lof-score)
    :type "histogram"
    :name "mayo"
    :opacity 0.6}
   {:x (mapv :lof-score gnomad-sv-with-lof-score)
    :type "histogram"
    :name "gnomAD SV"
    :opacity 0.6}]
  :layout {:barmode "overlay"
           :shapes [{:type "line"
                     :x0 (gnomad-sv-sp :p95)
                     :x1 (gnomad-sv-sp :p95)
                     :y0 0
                     :y1 1
                     :yref "paper"
                     :line {:color "black"
                            :width 2
                            :dash "dash"}}
                    {:type "line"
                     :x0 (gnomad-sv-sp :p99)
                     :x1 (gnomad-sv-sp :p99)
                     :y0 0
                     :y1 1
                     :yref "paper"
                     :line {:color "black"
                            :width 2
                            :dash "dash"}}
                    {:type "line"
                     :x0 (gnomad-sv-sp :p999)
                     :x1 (gnomad-sv-sp :p999)
                     :y0 0
                     :y1 1
                     :yref "paper"
                     :line {:color "black"
                            :width 2
                            :dash "dash"}}]
           :annotations [{:x (gnomad-sv-sp :p95)
                          :y 1
                          :yref "paper"
                          :text "95%"
                          :showarrow false
                          :yanchor "bottom"}
                         {:x (gnomad-sv-sp :p99)
                          :y 1
                          :yref "paper"
                          :text "99%"
                          :showarrow false
                          :yanchor "bottom"}
                         {:x (gnomad-sv-sp :p999)
                          :y 1
                          :yref "paper"
                          :text "99.9%"
                          :showarrow false
                          :yanchor "bottom"}]}})


(->> dels-with-lof-score
     (mapcat :overlaps)
     (map :overlap)
     frequencies)
#:cg{:ProbableGeneDisruption 20619, :SmallExonDeletion 1501, :CompleteOverlap 37139, :OverlapWithUnknownConsequence 3018, :FullyIntronic 698}

(->> mayo-variants-with-lof-score
     (filter (fn [v]
                (let [pos-strand-overlaps (filterv #(= "+" (:strand %)) (:overlaps v))
                      {:cg/keys [ProbableGeneDisruption SmallExonDeletion]}
                      (frequencies (map :overlap pos-strand-overlaps))]
                  (or (and (= 1 ProbableGeneDisruption) (= 1 SmallExonDeletion))
                      (= 2 ProbableGeneDisruption)))))
     (take 5)
     (into [])
     tap>)
