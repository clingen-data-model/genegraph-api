(ns genegraph.api.conflicts
  "Code for detecting and filtering conflicting interpretations."
  (:require [genegraph.framework.storage.rdf :as rdf]
            [genegraph.api.hybrid-resource :as hr]
            [clojure.set :as set]))



(defn path-conflicting-assertions [{:keys [tdb] :as context} args assertion]
  (let [candidate-assertion-query (rdf/create-query "
select ?a2 where {
?a a :cg/EvidenceStrengthAssertion ;
:cg/direction :cg/Supports ;
:cg/subject ?prop1 .
?prop1 a :cg/VariantPathogenicityProposition ;
:cg/variant ?variant1 .
?variant1 :ga4gh/copyChange ?copychange ;
:cg/CompleteOverlap ?gene .
?variant2 :cg/CompleteOverlap ?gene ;
:ga4gh/copyChange ?copychange .
?prop2 :cg/variant ?variant2 .
?a2 :cg/subject ?prop2 ;
:cg/direction ?a2dir .
filter (?a != ?a2) 
filter (?a2dir IN ( :cg/Inconclusive , :cg/Refutes ))
}")
        overlapping-genes-query (rdf/create-query "
select ?gene where {
?a a :cg/EvidenceStrengthAssertion ;
:cg/subject ?prop1 .
?prop1 a :cg/VariantPathogenicityProposition ;
:cg/variant ?variant1 .
?variant1 :ga4gh/copyChange ?copychange ;
:cg/CompleteOverlap ?gene .
}")
        candidate-assertions (candidate-assertion-query tdb {:a assertion})
        origin-genes (set (overlapping-genes-query tdb {:a assertion}))
        candidate-assertion-genes
        (reduce (fn [m a]
                  (assoc m a (set (overlapping-genes-query tdb {:a a}))))
                {}
                candidate-assertions)
        error-factor (* (count origin-genes) 0.1)]
    (->> candidate-assertion-genes
         (filter (fn [[a gs]]
                   (-> (set/difference origin-genes gs)
                       count
                       (<= error-factor))))
         (mapv #(hr/hybrid-resource (key %) context )))))

(defn conflicting-assertions [context args assertion]
  (path-conflicting-assertions context args assertion))


(defn candidate-assertions [{:keys [tdb]}]
  (let [q (rdf/create-query "
select ?a where {
?a a :cg/EvidenceStrengthAssertion ;
:cg/direction ?direction  ;
:cg/subject ?prop1 .
?prop1 a :cg/VariantPathogenicityProposition ;
:cg/variant ?variant1 .
}")
        overlapping-genes-query (rdf/create-query "
select ?gene where {
?a a :cg/EvidenceStrengthAssertion ;
:cg/subject ?prop1 .
?prop1 a :cg/VariantPathogenicityProposition ;
:cg/variant ?variant1 .
?variant1 :ga4gh/copyChange ?copychange ;
:cg/CompleteOverlap ?gene .
?gene a :so/GeneWithProteinProduct .
}")]
    (rdf/tx tdb
      (->> (q tdb)
           (map
            (fn [a]
              (let [v (rdf/ld1-> a [:cg/subject :cg/variant])]
                {:assertion (str a)
                 :direction (rdf/->kw (rdf/ld1-> a [:cg/direction]))
                 :copy-change (rdf/->kw (rdf/ld1-> v [:ga4gh/copyChange]))
                 :genes (set (map str (overlapping-genes-query tdb {:a a})))})))
           (filterv #(< 0 (count (:genes %))))))))

(def copy-changes #{:efo/copy-number-gain :efo/copy-number-loss})

(def non-path #{:cg/Inconclusive :cg/Refutes})

(def path #{:cg/Supports})

(defn assertion-subset [target-copy-change target-direction assertions]
  (filterv
   (fn [{:keys [direction copy-change]}]
     (and (target-direction direction) (= target-copy-change copy-change)))
   assertions))

(defn has-conflict? [path-assertion non-path-assertion error-factor]
  (let [non-overlapping-genes (set/difference (:genes path-assertion)
                                              (:genes non-path-assertion))]
    (< (/ (count non-overlapping-genes)
          (count (:genes path-assertion)))
       error-factor)))

(defn identify-conflicting-assertions [error-factor path-set non-path-set]
  (reduce
   (fn [conflicts path-assertion]
     (reduce
      (fn [all-conflicts new-conflict]
        (conj all-conflicts
              [(:assertion path-assertion)
               (:assertion new-conflict)]))
      conflicts
      (filter #(has-conflict? path-assertion % error-factor) non-path-set)))
   []
   path-set))

(defn construct-conflict-triples [conflict-list]
  (->> conflict-list
       (mapcat
        (fn [[path-iri non-path-iri]]
          [[(rdf/resource path-iri) :cg/conflictingInterpretation non-path-iri]
           [non-path-iri :cg/conflictingInterpretation (rdf/resource path-iri)]]))
       (into [])))

(comment
  (def assertions
    (let [tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])
          object-db @(get-in genegraph.user/api-test-app [:storage :object-db :instance])
          hybrid-db {:tdb tdb :object-db object-db}]
      (->> (candidate-assertions hybrid-db))))

  (def path-loss (assertion-subset :efo/copy-number-loss path assertions))
  (def non-path-loss (assertion-subset :efo/copy-number-loss non-path assertions))
  (count candidate-assertions)
  (count path-loss)
  (count non-path-loss)
  (def loss-conflicts
    (identify-conflicting-assertions 0.1 path-loss non-path-loss))

  (count loss-conflicts)
  )


(comment
  (let [tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])]
    (rdf/tx tdb
      (->> (conflicting-assertions {:tdb tdb}
                                   nil
                                   (rdf/resource "CVSCV:SCV000175618"))
           tap>)))
  )

(comment
  (let [tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])
        q (rdf/create-query "
select ?a2 where {
?a a :cg/EvidenceStrengthAssertion ;
:cg/direction :cg/Supports ;
:cg/subject ?prop1 .
?prop1 a :cg/VariantPathogenicityProposition ;
:cg/variant ?variant1 .
?variant1 :ga4gh/copyChange ?copychange ;
:cg/CompleteOverlap ?gene .
?variant2 :cg/CompleteOverlap ?gene .
?prop2 :cg/variant ?variant2 .
?a2 :cg/subject ?prop2 ;
:cg/direction ?a2dir .
filter (?a != ?a2) 
filter (?a2dir IN ( :cg/Inconclusive , :cg/Refutes ))
}")]
    (rdf/tx tdb
      (count (q tdb {:copyChange :efo/copy-number-gain}))))
  (+ 1 1)
  (.start
   (Thread.
    (let [tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])
          q (rdf/create-query "
select ?a where {
?a a :cg/EvidenceStrengthAssertion ;
:cg/direction :cg/Supports ;
:cg/subject ?prop1 .
?prop1 a :cg/VariantPathogenicityProposition ;
:cg/variant ?variant1 .
?variant1 :ga4gh/copyChange ?copyChange . }")
          q2 (rdf/create-query "
select ?a2 where {
?a a :cg/EvidenceStrengthAssertion ;
:cg/direction :cg/Supports ;
:cg/subject ?prop1 .
?prop1 a :cg/VariantPathogenicityProposition ;
:cg/variant ?variant1 .
?variant1 :ga4gh/copyChange ?copyChange ;
:cg/CompleteOverlap ?gene .
?variant2 :ga4gh/copyChange ?copyChange ;
:cg/CompleteOverlap ?gene .
?prop2 :cg/variant ?variant2 .
?a2 :cg/subject ?prop2 ;
:cg/direction ?a2dir .
filter (?a != ?a2) 
filter (?a2dir IN ( :cg/Inconclusive , :cg/Refutes ))
}")
          copy-change :efo/copy-number-gain]
      (rdf/tx tdb
        (->> (q tdb {:copyChange copy-change})
             (mapcat #(q2 tdb {:a % :copyChange copy-change}))
             #_(filter seq)
             set
             count
             (println "copy gain conflicts "))))))
  (def path-gain-gene-sets
    (let [tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])
          q (rdf/create-query "
select ?a where {
?a a :cg/EvidenceStrengthAssertion ;
:cg/direction ?direction  ;
:cg/subject ?prop1 .
?prop1 a :cg/VariantPathogenicityProposition ;
:cg/variant ?variant1 .
?variant1 :ga4gh/copyChange ?copyChange ;
:cg/CompleteOverlap ?gene .
}")
          q2 (rdf/create-query "
select ?gene where {
?a a :cg/EvidenceStrengthAssertion ;
:cg/direction ?direction  ;
:cg/subject ?prop1 .
?prop1 a :cg/VariantPathogenicityProposition ;
:cg/variant ?variant1 .
?variant1 :ga4gh/copyChange ?copyChange ;
:cg/CompleteOverlap ?gene .
}")]
      (rdf/tx tdb
        (mapv
         #(->> (q2 tdb {:a %
                        :copyChange :efo/copy-number-gain
                        :direction :cg/Supports})
               (mapv str)
               set)
         (q tdb {:copyChange :efo/copy-number-gain
                 :direction :cg/Supports})))))

  (def path-loss-gene-sets
    (let [tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])
          q (rdf/create-query "
select ?a where {
?a a :cg/EvidenceStrengthAssertion ;
:cg/direction ?direction  ;
:cg/subject ?prop1 .
?prop1 a :cg/VariantPathogenicityProposition ;
:cg/variant ?variant1 .
?variant1 :ga4gh/copyChange ?copyChange ;
:cg/CompleteOverlap ?gene .
}")
          q2 (rdf/create-query "
select ?gene where {
?a a :cg/EvidenceStrengthAssertion ;
:cg/direction ?direction  ;
:cg/subject ?prop1 .
?prop1 a :cg/VariantPathogenicityProposition ;
:cg/variant ?variant1 .
?variant1 :ga4gh/copyChange ?copyChange ;
:cg/CompleteOverlap ?gene .
}")]
      (rdf/tx tdb
        (mapv
         #(->> (q2 tdb {:a %
                        :copyChange :efo/copy-number-loss
                        :direction :cg/Supports})
               (mapv str)
               set)
         (q tdb {:copyChange :efo/copy-number-loss
                 :direction :cg/Supports})))))

  (def vusb-loss-gene-sets
    (let [tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])
          q (rdf/create-query "
select ?a where {
?a a :cg/EvidenceStrengthAssertion ;
:cg/direction ?direction  ;
:cg/subject ?prop1 .
?prop1 a :cg/VariantPathogenicityProposition ;
:cg/variant ?variant1 .
?variant1 :ga4gh/copyChange ?copyChange ;
:cg/CompleteOverlap ?gene .
filter ( ?direction IN ( :cg/Refutes , :cg/Inconclusive ))
}")
          q2 (rdf/create-query "
select ?gene where {
?a a :cg/EvidenceStrengthAssertion ;
:cg/direction ?direction  ;
:cg/subject ?prop1 .
?prop1 a :cg/VariantPathogenicityProposition ;
:cg/variant ?variant1 .
?variant1 :ga4gh/copyChange ?copyChange ;
:cg/CompleteOverlap ?gene .
filter ( ?direction IN ( :cg/Refutes , :cg/Inconclusive ))
}")]
      (rdf/tx tdb
        (mapv
         #(->> (q2 tdb {:a %
                        :copyChange :efo/copy-number-loss})
               (mapv str)
               set)
         (q tdb {:copyChange :efo/copy-number-loss})))))

  (def vusb-gain-gene-sets
    (let [tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])
          q (rdf/create-query "
select ?a where {
?a a :cg/EvidenceStrengthAssertion ;
:cg/direction ?direction  ;
:cg/subject ?prop1 .
?prop1 a :cg/VariantPathogenicityProposition ;
:cg/variant ?variant1 .
?variant1 :ga4gh/copyChange ?copyChange ;
:cg/CompleteOverlap ?gene .
filter ( ?direction IN ( :cg/Refutes , :cg/Inconclusive ))
}")
          q2 (rdf/create-query "
select ?gene where {
?a a :cg/EvidenceStrengthAssertion ;
:cg/direction ?direction  ;
:cg/subject ?prop1 .
?prop1 a :cg/VariantPathogenicityProposition ;
:cg/variant ?variant1 .
?variant1 :ga4gh/copyChange ?copyChange ;
:cg/CompleteOverlap ?gene .
filter ( ?direction IN ( :cg/Refutes , :cg/Inconclusive ))
}")]
      (rdf/tx tdb
        (mapv
         #(->> (q2 tdb {:a %
                        :copyChange :efo/copy-number-gain})
               (mapv str)
               set)
         (q tdb {:copyChange :efo/copy-number-gain})))))

  (count path-loss-gene-sets)
  (count path-gain-gene-sets)
  (count vusb-loss-gene-sets)
  (count vusb-gain-gene-sets)


  (reduce
   (fn [n vusb-set]
     (if (some #(= 0 (count (set/difference vusb-set %)))
               path-loss-gene-sets)
       (inc n)
       n))
   0
   vusb-loss-gene-sets)
  ;;7218

  (reduce
   (fn [n vusb-set]
     (if (some #(= 0 (count (set/difference vusb-set %)))
               path-gain-gene-sets)
       (inc n)
       n))
   0
   vusb-gain-gene-sets)
  ;; 16611

  (+ 7218 16611)
  23829
  (defn compute-conflicts [copy-change message])

  
  (/ 14017 60.0)
  ;; These numbers don't cover copy change--need to fix
  ;; 13969 -- Path call conflicts
  ;; 24769 -- VUS/B/LB conflicts

  (let [tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])
        q (rdf/create-query "
select ?a where {
?a a :cg/EvidenceStrengthAssertion ;
:cg/direction ?direction  ;
:cg/subject ?prop1 .
?prop1 a :cg/VariantPathogenicityProposition ;
:cg/variant ?variant1 .
}")
        ]
    (rdf/tx tdb
      (count (q tdb))))


    (let [tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])
          object-db @(get-in genegraph.user/api-test-app [:storage :object-db :instance])
          hybrid-db {:tdb tdb :object-db object-db}
          q (rdf/create-query "
select ?a where {
?a a :cg/EvidenceStrengthAssertion ;
:cg/direction ?direction  ;
:cg/subject ?prop1 .
?prop1 a :cg/VariantPathogenicityProposition ;
:cg/variant ?variant1 .
} limit 10")]
      (tap>
       (rdf/tx tdb
         (mapv
          (fn [a]
            (let [v (rdf/ld1-> a [:cg/subject :cg/variant])]
              {:assertion (str a)
               :direction (rdf/->kw (rdf/ld1-> a [:cg/direction]))
               :copy-change (rdf/->kw (rdf/ld1-> v [:ga4gh/copyChange]))
               :genes (set (concat (rdf/ld-> v [:cg/CompleteOverlap])
                                   (rdf/ld-> v [:cg/PartialOverlap])))}))
          (q tdb)))))
  )
