(ns genegraph.api.ga4gh
  "Convenience methods for working with ga4gh defined
  or related entities")

(defn location-size
  "Calculate the size of a sequence location"
  [{:ga4gh/keys [start end]}]
  (- end start))

(defn max-size
  "Calculate the maximum length of a canonica
  location. For this fn, a canonical location
  can be any entity containing a sequence of
  SequenceLocations under :ga4gh/location"
  [{:ga4gh/keys [location]}]
  (->> (remove nil? location)
       (map location-size)
       (apply max)))


(comment
  (location-size
   {:type :ga4gh/SequenceLocation,
    :ga4gh/sequenceReference
    "https://identifiers.org/refseq:NC_000002.12",
    :ga4gh/start 241988449,
    :ga4gh/end 242157305,
    :iri "https://genegraph.clingen.app/cT5GFfCC0z8"})
  
  (max-size
   {:type :cg/DosageRegion,
    :iri "http://dataexchange.clinicalgenome.org/dci/region-ISCA-37470",
    :ga4gh/location
    [{:type :ga4gh/SequenceLocation,
      :ga4gh/sequenceReference
      "https://identifiers.org/refseq:NC_000002.12",
      :ga4gh/start 241988449,
      :ga4gh/end 242157305,
      :iri "https://genegraph.clingen.app/cT5GFfCC0z8"}
     {:type :ga4gh/SequenceLocation,
      :ga4gh/sequenceReference
      "https://identifiers.org/refseq:NC_000002.11",
      :ga4gh/start 242930600,
      :ga4gh/end 243102476,
      :iri "https://genegraph.clingen.app/FgiirDgTvVU"}]})
  )
