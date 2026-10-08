(ns compare-gnomad-cnv-sv
  {:nextjournal.clerk/visibility {:code :hide}}
  (:require [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage :as storage]
            [genegraph.api.hybrid-resource :as hr]
            [genegraph.api.lof-score :as lof-score]
            [genegraph.api.overlaps :as overlaps]
            [genegraph.api.base.vcf :as vcf]
            [genegraph.api.iscn :as iscn]
            [genegraph.set-comparison :as set-comp]
            [genegraph.user :as u]
            [nextjournal.clerk :as clerk]
            [charred.api :as charred]
            [clojure.java.io :as io]
            [clojure.math :as math]))

;; #### gnomadCNV variants with score
(count (filter :lof-score set-comp/gnomad-cnv-scored))

;; #### gnomadSV variants with score
(count
 (filter :lof-score set-comp/gnomad-sv-scored))

;; #### gnomadCNV vs gnomadSV LOF scores
(clerk/plotly
 {:data
  [#_{:x (mapv :lof-score variants-with-lof-score)
      :type "histogram"
      :name "clinvar"
      :opacity 0.6}
   #_{:x (mapv :lof-score mayo-variants-with-lof-score)
      :type "histogram"
      :name "mayo"
      :opacity 0.6}
   #_{:x (mapv :lof-score gnomad-sv-with-lof-score)
      :type "histogram"
      :name "gnomAD SV"
      :opacity 0.6}
   {:x (mapv :lof-score (filter :lof-score set-comp/gnomad-cnv-scored))
    :type "histogram"
    :name "gnomAD CNV"
    :opacity 0.6}
   {:x (mapv :lof-score (filter :lof-score set-comp/gnomad-sv-scored))
    :type "histogram"
    :name "gnomAD SV"
    :opacity 0.6}]
  :layout {:barmode "overlay"}
  #_{:barmode "overlay"
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

;; #### gnomadCNV vs gnomadSV disrupted gene count

(clerk/plotly
 {:data
  [{:x (mapv #(count (:disruptive-overlaps %))
             (filter :lof-score set-comp/gnomad-cnv-scored))
    :type "histogram"
    :name "gnomAD CNV"
    :opacity 0.6}
   {:x (mapv #(count (:disruptive-overlaps %))
             (filter :lof-score set-comp/gnomad-sv-scored))
    :type "histogram"
    :name "gnomAD SV"
    :opacity 0.6}]
  :layout {:barmode "overlay"
           :xaxis {:range [0 20]}}})

;; #### gnomadCNV vs gnomadSV size

(clerk/plotly
 {:data
  [{:x (mapv #(Integer/parseInt (:svlen %))
             (filter :lof-score set-comp/gnomad-cnv-scored))
    :type "histogram"
    :name "gnomAD CNV"
    :opacity 0.6
    :xbins {:size 1000}}
   {:x (mapv #(Integer/parseInt (:svlen %))
             (filter :lof-score set-comp/gnomad-sv-scored))
    :type "histogram"
    :name "gnomAD SV"
    :opacity 0.6
    :xbins {:size 1000}}]
  :layout {:barmode "overlay"
           :xaxis {:range [0 500000]}}})

;; gnomad SV size distribution
(clerk/plotly
 {:data
  [{:x (mapv #(Integer/parseInt (:svlen %))
             (filter :lof-score set-comp/gnomad-sv-scored))
    :type "histogram"
    :name "gnomAD SV"
    :opacity 0.6
    :xbins {:size 1000}}]
  :layout {:barmode "overlay"
           :xaxis {:range [0 100000]}}})

;; gnomadCNV calls with endmin < posmax

(count (filter (fn [{:keys [endmin posmax]}]
                 (and endmin
                      posmax
                      (< (Integer/parseInt endmin)
                         (Integer/parseInt posmax))))
               set-comp/gnomad-cnv-scored))



;; #### gnomadSV stats

  ;; total in set
  2154486
  ;; dels only
  1197080
  ;; dels > 1kb
  293924
  ;; w plof
  36087
