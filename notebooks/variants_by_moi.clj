(ns variants-by-moi
  {:nextjournal.clerk/visibility {:code :hide}}
  (:require [genegraph.notebooks :as nb]
            [nextjournal.clerk :as clerk]
            [genegraph.framework.storage.rdf :as rdf]
            [fastmath.stats :as stats]))

^{:nextjournal.clerk/visibility {:result :hide}}
(def gv-assertions
  (rdf/tx nb/db
    ((rdf/create-query "
select ?x where { ?x a :sepio/GeneValidityEvidenceLevelAssertion ;
 :sepio/has-evidence ?ev }")
     nb/db)))

^{:nextjournal.clerk/visibility {:result :hide}}
(def proband-query
  (rdf/create-query "
select ?proband where {
 ?assertion :sepio/has-evidence * / ^ :sepio/has-variant ? ?proband .
 ?proband a :sepio/ProbandWithVariantEvidenceItem . }"))



^{:nextjournal.clerk/visibility {:result :hide}}
(def class-moi-probands
  (rdf/tx nb/db
    (mapv
     (fn [a]
       {:moi (rdf/ld1-> a [:sepio/has-subject :sepio/has-qualifier :rdfs/label])
        :classification (rdf/ld1-> a [:sepio/has-object :rdfs/label])
        :probands (count (proband-query nb/db {:assertion a}))})
     gv-assertions)))

;; Number of curations

(clerk/plotly {:data [{:x (map :classification class-moi-probands)
                       :type "histogram"}]})

(clerk/plotly {:data [{:x (map :moi class-moi-probands)
                       :type "histogram"}]})

#_(clerk/plotly {:data [{:x (map :probands class-moi-probands)
                       :type "histogram"}]})


^{:nextjournal.clerk/visibility {:result :hide}}
(defn proband-distribution [class-moi-probands]
  (clerk/plotly {:data [{:x (->> class-moi-probands
                                 (filter #(= "Autosomal dominant inheritance"
                                             (:moi %)))
                                 (map :probands))
                         :autobinx false
                         :xbins {:start 0
                                 :end 40
                                 :size 1}
                         :type "histogram"
                         :name "Autosomal dominant"}
                        {:x (->> class-moi-probands
                                 (filter #(= "Autosomal recessive inheritance"
                                             (:moi %)))
                                 (map :probands))
                         :type "histogram"
                         :opacity 0.7
                         :name "Autosomal recessive"}
                        {:x (->> class-moi-probands
                                 (filter #(= "X-linked inheritance"
                                             (:moi %)))
                                 (map :probands))
                         :type "histogram"
                         :opacity 0.7
                         :name "X-linked"}]
                 :layout {:xaxis {:title "Probands"}
                          :yaxis {:title "Curations"}
                          :barmode "overlay"}}))

;; Proband distribution, all curations
(proband-distribution class-moi-probands)

;; Definitive or strong
(proband-distribution (filter #(#{"definitive evidence"
                                  "strong evidence"}
                                (:classification %))
                              class-moi-probands))

;; Moderate evidence
(proband-distribution (filter #(#{"moderate evidence"}
                                (:classification %))
                              class-moi-probands))

;; Limited evidence
(proband-distribution (filter #(#{"limited evidence"}
                                (:classification %))
                              class-moi-probands))

;; No evidence
(proband-distribution (filter #(#{"no known disease relationship"}
                                (:classification %))
                              class-moi-probands))


;; stats -- probands per curation
^{:nextjournal.clerk/visibility {:result :hide}}
(defn stats-table [class-moi-probands]
  (clerk/table
   (clerk/use-headers
    (cons
     ["Mode of Inheritance"
      "mean"
      "median"
      "min"
      "max"]
     (map
      (fn [[l v]] (let [probands (mapv :probands v)]
                    [l
                     (stats/mean probands)
                     (stats/median probands)
                     (stats/minimum probands)
                     (stats/maximum probands)]))
      (group-by :moi class-moi-probands))))))


(stats-table class-moi-probands)

;; only definitive/strong
(stats-table  (filter #(#{"definitive evidence"
                          "strong evidence"}
                        (:classification %))
                      class-moi-probands))

;; only moderate
(stats-table  (filter #(#{"moderate evidence"}
                        (:classification %))
                      class-moi-probands))

;; only limited
(stats-table  (filter #(#{"limited evidence"}
                        (:classification %))
                      class-moi-probands))

;; only no known relationship
(stats-table  (filter #(#{"no known disease relationship"}
                        (:classification %))
                      class-moi-probands))
