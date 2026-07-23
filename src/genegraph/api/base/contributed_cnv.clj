(ns genegraph.api.base.contributed-cnv
  (:require [charred.api :as charred]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.edn :as edn]
            [io.pedestal.log :as log]
            [genegraph.framework.id :as id]
            [genegraph.framework.storage :as storage]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.api.rdf-conversion :as rdf-conversion]))

(defn iscn->m [iscn]
  )

(comment
  (def trillium-path "/Users/tristan/data/genegraph-base/trillium-cnvs.csv")
  ;; Trillium columns
  ["Site"
   "Internal Reference"
   "Variant Class"
   "Assembly"
   "ISCN"
   "Chromosome"
   "Genomic Start"
   "Genomic End"
   "Zygosity"
   "Inheritance"
   "Pathogenicity"
   "Pathogenicity Comment (Optional)"
   "Year Last Evaluated"
   "Other notes (optional)"]

  ;; Mayo just has ISCN in one column 
  
  (with-open [r (io/reader trillium-path)]
    (->> (charred/read-csv r)
         (take 5)
         (into [])
         tap>))
  )
