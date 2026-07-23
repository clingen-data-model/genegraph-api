(ns genegraph.api.lof-score
  (:require [genegraph.framework.storage.rdf :as rdf]
            [genegraph.api.hybrid-resource :as hr]))

(defn gene-lof-scores [variant]
  (let [q (rdf/create-query "
select ?s where {
?variant :cg/CompleteOverlap ?gene .
?s :cg/feature ?gene ;
a :cg/LOFProbabilityEstimation .
} ")]
    (mapv
     #(rdf/ld1-> % [:cg/lower95CI])
     (q variant {:variant variant}))))

(defn variant-lof-score [variant]
  (let [score-product (reduce * (map #(- 1 %) (gene-lof-scores variant)))]
    (Math/log (/ (- 1 score-product) score-product))))

(comment
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
limit 5")]
    (rdf/tx tdb
      (->> (q tdb {:change :efo/copy-number-loss})
           #_(mapv #(hr/hybrid-resource % hybrid-db))
           (mapv variant-lof-score)
           #_count
           tap>)))

  )
