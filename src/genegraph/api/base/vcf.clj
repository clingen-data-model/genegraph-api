(ns genegraph.api.base.vcf
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [charred.api :as charred])
  (:import [java.util.zip GZIPInputStream]))

(defn info-field->map [info-field]
  (->> (str/split info-field #";")
       (map #(str/split % #"="))
       (map (fn [s]
              (if (= 1 (count s))
                (conj s true)
                s)))
       (into {})))

(defn vcf-row->map [[chrom pos id ref alt qual filter info]]
  (let [row-map {:chrom chrom
                 :pos pos
                 :id id
                 :ref ref
                 :alt alt
                 :qual qual
                 :filter filter
                 :info info}]
    #_(info-field->map info)
    row-map))

(comment
  
  (with-open [r (-> "/Users/tristan/Downloads/gnomad-cnv.vcf.gz"
                    io/input-stream
                    GZIPInputStream.)]
    (->> (charred/read-csv r :separator \tab)
         (remove #(re-find #"^#" (first %)))
         (take 5)
         (mapv vcf-row->map)
         tap>))

  (with-open [r (-> "/Users/tristan/Downloads/gnomad-sv.vcf.gz"
                    io/input-stream
                    GZIPInputStream.)]
    (->> (charred/read-csv r :separator \tab)
         (remove #(re-find #"^#" (first %)))
         (map vcf-row->map)
         (filter #(= "<DEL>" (:alt %)))
         #_(take 5)
         (map #(info-field->map (:info %)))
         (map #(get % "SVLEN"))
         (remove nil?)
         (map #(Long/parseLong %))
         (remove #(< % 1000))
         #_(into [])
         count
         tap>))

  

  )
