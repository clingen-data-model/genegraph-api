(ns genegraph.api.base.gene-refseq
  (:require [charred.api :as charred]
            [clojure.java.io :as io])
  (:import [java.util.zip GZIPInputStream]))

(comment
  (let [path "/Users/tristan/data/genegraph-base/gene2refseq.gz"]
    (with-open [r (-> path
                      io/input-stream
                      GZIPInputStream.
                      io/reader)]
      (->> (charred/read-csv r :separator \tab)
           (take 20)
           (into [])
           tap>))))

(comment
  (let [path "/Users/tristan/data/genegraph-base/Homo_sapiens.gene_info.gz"]
    (with-open [r (-> path
                      io/input-stream
                      GZIPInputStream.
                      io/reader)]
      (->> (charred/read-csv r :separator \tab)
           count))))
