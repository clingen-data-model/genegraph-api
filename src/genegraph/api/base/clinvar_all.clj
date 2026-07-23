(ns genegraph.api.base.clinvar-all
  (:require [clojure.java.io :as io])
  (:import [java.util.regex Pattern]
           [java.io InputStream]
           [java.util.zip GZIPInputStream]))

(defn restream-seq
  "Lazily stream substrings matched by `re` from `is`. The Scanner splits
   the input on `delim` (typically a zero-width lookahead so the delimiter
   stays in the chunk), and `re` is applied to each chunk. Use a DOTALL
   regex when matches span multiple lines."
  [^Pattern delim ^Pattern re ^InputStream is]
  (let [s (doto (java.util.Scanner. is)
            (.useDelimiter delim))]
    ((fn step []
       (when (.hasNext s)
         (if-let [m (re-find re (.next s))]
           (cons m (lazy-seq (step)))
           (recur)))))))

(comment
  (time
   (with-open [is (-> "/Users/tristan/data/genegraph-base/clinvar.xml.gz"
                      io/input-stream
                      GZIPInputStream.)]
     (->> (restream-seq #"(?=<VariationArchive)"
                        #"(?s)<VariationArchive\b.*?</VariationArchive>"
                        is)
          (take 10000)
          count)))
  (* 300 6)
  )


