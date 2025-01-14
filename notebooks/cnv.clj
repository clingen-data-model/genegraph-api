(ns cnv
  {:nextjournal.clerk/visibility {:code :hide}}
  (:require [nextjournal.clerk :as clerk]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage :as storage]
            [genegraph.user :as u]))

;; Total copy number variants

(let [tdb @(get-in u/api-test-app [:storage :api-tdb :instance])
      object-db @(get-in u/api-test-app [:storage :object-db :instance])
      q (rdf/create-query "
select ?v where {
?v a :cg/CanonicalVariant .
}
")]
  (rdf/tx tdb
    (->> (q tdb)
         count)))

^{:nextjournal.clerk/visibility {:result :hide}}
(defn select-evaluation [scv]
  (->> (:cg/contributions scv)
       (filter #(= :cg/Evaluator (:cg/role %)))
       first))

^{:nextjournal.clerk/visibility {:result :hide}}
(def tdb @(get-in u/api-test-app [:storage :api-tdb :instance]))

^{:nextjournal.clerk/visibility {:result :hide}}
(def object-db @(get-in u/api-test-app [:storage :object-db :instance]))

^{:nextjournal.clerk/visibility {:result :hide}}
(defn read-object [id]
  (storage/read object-db [:objects id]))

^{:nextjournal.clerk/visibility {:result :hide}}
(defn scv-variant [scv]
  (read-object
   (:cg/variant
    (read-object (:cg/subject scv)))))

^{:nextjournal.clerk/visibility {:result :hide}}
(def recent-submissions
  (let [tdb @(get-in u/api-test-app [:storage :api-tdb :instance])
        object-db @(get-in u/api-test-app [:storage :object-db :instance])
        q (rdf/create-query "
select ?a where {
 ?a a :cg/EvidenceStrengthAssertion ;
 :cg/subject ?p .
 ?p a :cg/VariantPathogenicityProposition .
}")]
    (rdf/tx tdb
      (->> (q tdb)
           (map #(storage/read object-db [:objects (str %)]))
           (filter (fn [scv]
                     (< 0 (compare
                           (-> scv select-evaluation :cg/date)
                           "2020-01-01"))))
           (into [])))))

^{:nextjournal.clerk/visibility {:result :hide}}
(defn evaluation-year [scv]
  (-> scv
      select-evaluation
      :cg/date
      (subs 0 4)))

;; totals by year, by variant type

(let [cnvs (->> recent-submissions
                (map (fn [scv] {:year (evaluation-year scv)
                                :copy-change
                                (:ga4gh/copyChange (scv-variant scv))})))
      gains (filter #(= :efo/copy-number-gain (:copy-change %)) cnvs)
      losses (filter #(= :efo/copy-number-loss (:copy-change %)) cnvs)
      gains-by-year (sort-by key (frequencies (map :year gains)))
      losses-by-year (sort-by key (frequencies (map :year losses)))]
  (clerk/plotly {:data [{:x (map key gains-by-year)
                         :y (map val gains-by-year)
                         :name "copy number gain"
                         :type "bar"}
                        {:x (map key losses-by-year)
                         :y (map val losses-by-year)
                         :name "copy number loss"
                         :type "bar"}]
                 :layout {:barmode "stack"}}))

^{:nextjournal.clerk/visibility {:result :hide}}
(def classification-label
  {:cg/Pathogenic "Pathogenic"
   :cg/LikelyPathogenic "Likely Pathogenic"
   :cg/UncertainSignificance "Uncertain Significance"
   :cg/LikelyBenign "Likely Benign"
   :cg/Benign "Benign"})

^{:nextjournal.clerk/visibility {:result :hide}}
(defn plot-scvs-by-classification [scvs]
  (let [scvs-by-classification (group-by :cg/classification scvs)]
    (clerk/plotly
     {:data 
      (map (fn [[acmg-class scvs]]
             (let [by-year (sort-by key (frequencies (map evaluation-year scvs)))]
               {:x (map key by-year)
                :y (map val by-year)
                :name (classification-label acmg-class "Other")
                :type "bar"}))
           scvs-by-classification)
      :layout {:barmode "stack"}})))

;; Copy number gain classifications by year

(plot-scvs-by-classification
 (filter #(= :efo/copy-number-gain
             (:ga4gh/copyChange (scv-variant %)))
         recent-submissions))

;; Copy number loss classifications by year

(plot-scvs-by-classification
 (filter #(= :efo/copy-number-loss
             (:ga4gh/copyChange (scv-variant %)))
         recent-submissions))

(frequencies (map :cg/reviewStatus recent-submissions))

^{:nextjournal.clerk/visibility {:result :hide}}
(def review-status-label
  {:cg/CriteriaProvided "Criteria Provided"
   :cg/ExpertPanel "Expert Panel"
   :cg/Flagged "Flagged"
   :cg/NoCriteria "No Criteria Provided"
   :cg/NoClassification "Other"})

^{:nextjournal.clerk/visibility {:result :hide}}
(defn plot-scvs-by-review-status [scvs]
  (let [scvs-by-classification (group-by :cg/reviewStatus scvs)]
    (clerk/plotly
     {:data 
           (map (fn [[acmg-class scvs]]
             (let [by-year (sort-by key (frequencies (map evaluation-year scvs)))]
               {:x (map key by-year)
                :y (map val by-year)
                :name (review-status-label acmg-class "Other")
                :type "bar"}))
           scvs-by-classification)
      :layout {:barmode "stack"}})))

(plot-scvs-by-review-status recent-submissions)

(def recent-pathogenic-copy-number-gains
  (let [tdb @(get-in u/api-test-app [:storage :api-tdb :instance])
        object-db @(get-in u/api-test-app [:storage :object-db :instance])
        q (rdf/create-query "
select ?assertion ?variant where {
?variant a :cg/CanonicalVariant ;
:ga4gh/copyChange :efo/copy-number-gain .
?p :cg/variant ?variant ;
a :cg/VariantPathogenicityProposition .
?assertion :cg/subject ?p ;
:cg/direction :cg/Supports .

}
")]
    (rdf/tx
     tdb
     (->> (q tdb {::rdf/params {:type :table}})
          #_(map read-object)
          (filter (fn [assertion-variant]
                    (< 0 (compare
                          (-> assertion-variant
                              :assertion
                              read-object
                              select-evaluation
                              :cg/date)
                          "2020-01-01"))))
          (into [])))))

(count recent-pathogenic-copy-number-gains)


(def path-copy-gain-gene-counts
  (rdf/tx
      tdb
    (->> recent-pathogenic-copy-number-gains
         (mapv #(-> %
                    :variant
                    (rdf/ld-> [:cg/CompleteOverlap])
                    count)))))

(clerk/plotly
 {:data [{:x (->> path-copy-gain-gene-counts
                  (filter #(< % 200))
                  (remove #(= 0 %)))
          
          :type "histogram"}]})




(def copy-gain-zero-gene
  (rdf/tx
      tdb
    (->> recent-pathogenic-copy-number-gains
         (filter #(= 0
                     (-> %
                         :variant
                         (rdf/ld-> [:cg/CompleteOverlap])
                         count)))
         (into []))))

(count copy-gain-zero-gene)

(def copy-gain-lp-sufficient
  (rdf/tx
      tdb
    (->> recent-pathogenic-copy-number-gains
         (filter #(<= 50 
                      (-> %
                          :variant
                          (rdf/ld-> [:cg/CompleteOverlap])
                          count)))
         (into []))))

(count copy-gain-lp-sufficient)
