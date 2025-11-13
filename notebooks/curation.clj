(ns curation
  (:require [genegraph.user :as u]
            [nextjournal.clerk :as clerk]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.api.filter :as filter]
            [clojure.set :as set]))

(def tdb @(get-in u/api-test-app [:storage :api-tdb :instance]))


(def cnv-query (rdf/create-query "
select ?scv where {
?scv a :cg/EvidenceStrengthAssertion ;
:cg/direction :cg/Supports ;
:cg/subject ?prop .
?prop a :cg/VariantPathogenicityProposition .
}
"))

(def fq
  (filter/compile-filter-query
   [:bgp ['x :rdf/type :cg/EvidenceStrengthAssertion]]
   [{:filter :proposition_type
     :argument "CG:VariantPathogenicityProposition"}]))

;; CNV Counts
(rdf/tx tdb
  (->> (cnv-query tdb)
       count))

(rdf/tx tdb
  (->> (fq tdb)
       count))
(def evaluation-dates
  (rdf/tx tdb
    (->> (fq tdb)
         #_(take 5)
         (map #(rdf/ld1-> % [:cg/dateLastEvaluated]))
         (remove nil?)
         (map #(subs % 0 4))
         (filterv #(< (compare"2010" %) 0)))))

(clerk/plotly
 {:data [{:x evaluation-dates
          :type "histogram"}]})

(def submitter-frequencies
  (rdf/tx tdb
    (->> (fq tdb)
         #_(take 5)
         (map #(rdf/ld1-> % [:cg/submitter :rdfs/label]))
         frequencies)))


(let [sorted-frequencies (->> submitter-frequencies
                              (sort-by val)
                              reverse)
      top-10 (into [] (take 10 sorted-frequencies))
      others (reduce
              (fn [a [_ n]] (+ a n))
              0
              (drop 10 sorted-frequencies))
      combined (conj top-10 ["other" others])]
  (clerk/plotly
   {:data [{:labels (map (fn [[l _]] (subs l 0 (min (count l) 40 ))) combined)
            :values (map second combined)
            :type "pie"}]}))

(def dosage-conflicts
  (filter/compile-filter-query
   [:bgp ['x :rdf/type :cg/EvidenceStrengthAssertion]]
   [{:filter :proposition_type
     :argument "CG:VariantPathogenicityProposition"}
    {:filter :copy_change
     :argument "EFO:0030067"}
    {:filter :complete_overlap_with_feature_set
     :argument "CG:HaploinsufficiencyFeatures"}
    {:filter :assertion_direction
     :argument "CG:Supports"
     #_#_:operation "not_exists"}]))

(let [q (filter/compile-filter-query
         [:bgp ['x :rdf/type :cg/EvidenceStrengthAssertion]]
         [{:filter :proposition_type
           :argument "CG:VariantPathogenicityProposition"}
          {:filter :copy_change
           :argument "EFO:0030067"}
          {:filter :complete_overlap_with_feature_set
           :argument "CG:HaploinsufficiencyFeatures"}
          {:filter :assertion_direction
           :argument "CG:Supports"
           :operation :not_exists}])]
  (rdf/tx tdb
    (->> (q tdb)
         count)))


(let [q (rdf/create-query "
select ?x where { ?x a :cg/AssertionAnnotation }
")]
  (rdf/tx tdb      
    (->> (q tdb)
         (mapv #(-> (rdf/ld1-> % [:cg/classification])
                   str))
         frequencies)))

(def cnv-query (rdf/create-query "
select ?scv where {
?scv a :cg/EvidenceStrengthAssertion ;
:cg/direction :cg/Supports ;
:cg/subject ?prop .
?prop a :cg/VariantPathogenicityProposition .
}
"))

(def pathogenic-submission-genes
  (rdf/tx tdb
    (->> (cnv-query tdb)
         (reduce
          (fn [a x]
            (assoc a
                   x
                   (set
                    (concat (rdf/ld-> x [:cg/subject :cg/variant :cg/CompleteOverlap])
                            (rdf/ld-> x [:cg/subject :cg/variant :cg/PartialOverlap])))))
          {})
         #_(into []))))


(def vusb-cnv-query (rdf/create-query "
select ?scv where {
?scv a :cg/EvidenceStrengthAssertion ;
:cg/direction ?direction ;
:cg/subject ?prop .
?prop a :cg/VariantPathogenicityProposition .
FILTER (?direction IN ( :cg/Refutes , :cg/Inconclusive ))
}
"))

(def vusb-submission-genes
  (rdf/tx tdb
    (->> (vusb-cnv-query tdb)
         (reduce
          (fn [a x]
            (assoc a
                   x
                   (set
                    (concat (rdf/ld-> x [:cg/subject :cg/variant :cg/CompleteOverlap])
                            (rdf/ld-> x [:cg/subject :cg/variant :cg/PartialOverlap])))))
          {})
         #_(into []))))

(defn has-conflict? [[cnv gene-set]]
  (some #(seq (set/difference gene-set %)) (vals vusb-submission-genes)))

(has-conflict? (first pathogenic-submission-genes))

(first pathogenic-submission-genes)

