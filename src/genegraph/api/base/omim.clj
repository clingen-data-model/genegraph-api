(ns genegraph.api.base.omim
  (:require [clojure.java.io :as io]
            [clojure.data.csv :as csv]))

(with-open [r (io/reader "/Users/tristan/code/genegraph-api/data/base/genegraph-base/omim-genemap.txt")]
  (->> (csv/read-csv r :separator \tab)
       (take 5)
       (into [])))
