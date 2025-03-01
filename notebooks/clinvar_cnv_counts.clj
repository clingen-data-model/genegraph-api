(ns clinvar-cnv-counts
  {:nextjournal.clerk/visibility {:code :hide :result :hide}}
  (:require [nextjournal.clerk :as clerk]
            [genegraph.api.hybrid-resource :as hr]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage.rocksdb :as rocksdb]
            [genegraph.user :as gg]
            [genegraph.api.filter :as f]
            [genegraph.api.sequence-index :as idx]
            [genegraph.api.base.clinvar :as cv-load]
            [clojure.set :as set]))

(def tdb @(get-in gg/api-test-app [:storage :api-tdb :instance]))
(def object-db @(get-in gg/api-test-app [:storage :object-db :instance]))
(def hybrid-db {:tdb tdb :object-db object-db})

(def assertion-bgp
  [:bgp ['x :rdf/type :cg/EvidenceStrengthAssertion]])


(def haplo-conflicts
  (let [filters [{:filter :proposition_type
                  :argument :cg/VariantPathogenicityProposition}
                 {:filter :complete_overlap_with_feature_set
                  :argument "CG:HaploinsufficiencyFeatures"}
                 {:filter :copy_change
                  :argument :efo/copy-number-loss}
                 {:filter :assertion_direction
                  :argument :cg/Supports
                  :operation :not_exists}]
        q (f/compile-filter-query assertion-bgp filters)]
    (rdf/tx tdb
      (->> (q tdb #_{::rdf/params {:limit 3}})
           (map str)
           set))))

(def possible-haplo-conflicts
  (let [filters [{:filter :proposition_type
                  :argument :cg/VariantPathogenicityProposition}
                 {:filter :complete_overlap_with_feature_set
                  :argument "CG:HaploinsufficiencyFeatures"
                  :operation :not_exists}
                 {:filter :partial_overlap_with_feature_set
                  :argument "CG:HaploinsufficiencyFeatures"}
                 {:filter :copy_change
                  :argument :efo/copy-number-loss}
                 {:filter :assertion_direction
                  :argument :cg/Supports
                  :operation :not_exists}]
        q (f/compile-filter-query assertion-bgp filters)]
    (rdf/tx tdb
      (->> (q tdb #_{::rdf/params {:limit 3}})
           (map str)
           set))))

(def triplo-conflicts
  (let [filters [{:filter :proposition_type
                  :argument :cg/VariantPathogenicityProposition}
                 {:filter :complete_overlap_with_feature_set
                  :argument "CG:TriplosensitivityFeatures"}
                 {:filter :copy_change
                  :argument :efo/copy-number-gain}
                 {:filter :assertion_direction
                  :argument :cg/Supports
                  :operation :not_exists}]
        q (f/compile-filter-query assertion-bgp filters)]
    (rdf/tx tdb
      (->> (q tdb #_{::rdf/params {:limit 3}})
           (map str)
           set))))


(let [filters [{:filter :proposition_type
                :argument :cg/VariantPathogenicityProposition}
               {:filter :copy_change
                :argument :efo/copy-number-loss}
               {:filter :assertion_direction
                :argument :cg/Supports
                :operation :not_exists}]
      q (f/compile-filter-query assertion-bgp filters)
      vus-b-losses (rdf/tx tdb
                     (->> (q tdb {::rdf/params {:limit 3}})
                          (mapv #(mapv
                                 :ga4gh/location
                                 (-> (hr/hybrid-resource % hybrid-db)
                                     (hr/path1->  hybrid-db [:cg/subject])
                                     (hr/path1->  hybrid-db [:cg/variant])
                                     :cg/includedVariants)))))]
  vus-b-losses
  #_[(count vus-b-losses)
     (count (set/difference vus-b-losses haplo-conflicts))]
)


(def vus-b-genes
  (let [filters [{:filter :proposition_type
                  :argument :cg/VariantPathogenicityProposition}
                 {:filter :copy_change
                  :argument :efo/copy-number-loss}
                 {:filter :assertion_direction
                  :argument :cg/Supports
                  :operation :not_exists}
                 {:filter :complete_overlap_with_feature_set
                  :argument "CG:HaploinsufficiencyFeatures"
                  :operation :not_exists}
                 {:filter :partial_overlap_with_feature_set
                  :argument "CG:HaploinsufficiencyFeatures"
                  :operation :not_exists}]
        q (f/compile-filter-query assertion-bgp filters)
        vus-b-genes (rdf/tx tdb
                      (->> (q tdb #_{::rdf/params {:limit 3}})
                           (map #(->> (rdf/ld-> % [:cg/subject
                                                    :cg/variant
                                                    :cg/CompleteOverlap])
                                       (map str)
                                       set))
                           (filterv #(< 0 (count %)))))]
    vus-b-genes
    #_[(count vus-b-losses)
       (count (set/difference vus-b-losses haplo-conflicts))]
    ))

(count vus-b-genes)


(def path-genes
  (let [filters [{:filter :proposition_type
                  :argument :cg/VariantPathogenicityProposition}
                 {:filter :copy_change
                  :argument :efo/copy-number-loss}
                 {:filter :assertion_direction
                  :argument :cg/Supports
                  :operation :exists}]
        q (f/compile-filter-query assertion-bgp filters)]

    (rdf/tx tdb
      (->> (q tdb #_{::rdf/params {:limit 3}})
           (map #(->> (rdf/ld-> % [:cg/subject
                                   :cg/variant
                                   :cg/CompleteOverlap])
                      (map str)
                      set))
           (filterv #(< 0 (count %)))))))

(count path-genes)

(def loss-conflict-vus-b-count
  (->> vus-b-genes
       #_(take 10)
       (filter (fn [s]
                 (some #(set/superset? s %) path-genes)))
       count))

(def loss-conflict-p-count
 (->> path-genes
      #_(take 10)
      (filter (fn [s]
                (some #(set/subset? s %) vus-b-genes)))
      count))


(def trip-vus-b-genes
  (let [filters [{:filter :proposition_type
                  :argument :cg/VariantPathogenicityProposition}
                 {:filter :copy_change
                  :argument :efo/copy-number-gain}
                 {:filter :assertion_direction
                  :argument :cg/Supports
                  :operation :not_exists}
                 {:filter :complete_overlap_with_feature_set
                  :argument "CG:TriplosensitivityFeatures"
                  :operation :not_exists}]
        q (f/compile-filter-query assertion-bgp filters)]
    (rdf/tx tdb
      (->> (q tdb #_{::rdf/params {:limit 3}})
           (map #(->> (rdf/ld-> % [:cg/subject
                                   :cg/variant
                                   :cg/CompleteOverlap])
                      (map str)
                      set))
           (filterv #(< 0 (count %)))))))

(count trip-vus-b-genes)

(def trip-path-genes
  (let [filters [{:filter :proposition_type
                  :argument :cg/VariantPathogenicityProposition}
                 {:filter :copy_change
                  :argument :efo/copy-number-gain}
                 {:filter :assertion_direction
                  :argument :cg/Supports
                  :operation :exists}]
        q (f/compile-filter-query assertion-bgp filters)]
    (rdf/tx tdb
      (->> (q tdb #_{::rdf/params {:limit 3}})
           (map #(->> (rdf/ld-> % [:cg/subject
                                   :cg/variant
                                   :cg/CompleteOverlap])
                      (map str)
                      set))
           (filterv #(< 0 (count %)))))))

(count trip-path-genes)


(def trip-conflict-vus-b-count
  (->> trip-vus-b-genes
       #_(take 10)
       (filter (fn [s]
                 (some #(set/superset? s %) trip-path-genes)))
       count))

(def trip-conflict-p-count
  (->> trip-path-genes
       #_(take 10)
       (filter (fn [s]
                 (some #(set/subset? s %) trip-vus-b-genes)))
       count))

^{:nextjournal.clerk/visibility {:result :show}}
(def total-cnvs
  (let [filters [{:filter :proposition_type
                  :argument :cg/VariantPathogenicityProposition}]
        q (f/compile-filter-query assertion-bgp filters)]
    (rdf/tx tdb
      (count (q tdb)))))

^{:nextjournal.clerk/visibility {:result :show}}
(let [non-dosage-conflict-count (+ trip-conflict-p-count
                                   trip-conflict-vus-b-count
                                   loss-conflict-vus-b-count
                                   loss-conflict-p-count)
      dosage-conflicts (+ (count haplo-conflicts)
                          (count triplo-conflicts))
      possible-haplo-conflicts (count possible-haplo-conflicts)
      other-cnvs (- total-cnvs
                    non-dosage-conflict-count
                    dosage-conflicts
                    possible-haplo-conflicts)]
  (clerk/plotly {:data [{:values [non-dosage-conflict-count
                                  dosage-conflicts
                                  possible-haplo-conflicts
                                  other-cnvs]
                         :labels ["conflicts outside dosage map"
                                  "definite conflicts with dosage map"
                                  "possible conflicts with dosage map"
                                  "others"]
                         :type "pie"}]}))
