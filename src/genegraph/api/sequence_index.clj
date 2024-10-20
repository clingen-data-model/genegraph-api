(ns genegraph.api.sequence-index)

;; format of sequence index

;; the sequence index is a multi-key index allowing
;; a user to scan for features using coordinates on
;; a reference sequence. The index is formatted thusly:

;; :sequences
;; :so/Gene
;; Sequence identifier string (i.e.  "https://identifiers.org/refseq:NC_000001.11"}
;; Coordinate integer  17369
;; entitiy iri (prevent overwriting other entities)

;; or

;; :sequences
;; #{:so/mRNA :so/Exon}
;; Gene identifier string
;; Sequence identifier string
;; Coordinate integer
;; entitiy iri (prevent overwriting other entities)

(defmulti sequence-feature->sequence-index
  "Accepts a map with a located sequence feature. Returns
  a set of entries to insert into a RocksDB backed sequence.
  index."
  :type)

(defn location->index-entries
  "Generate the index entries needed to make a specific feature
  discoverable."
  [{:ga4gh/keys [start end sequenceReference]}]
  (let [coords (remove nil? (flatten [start end]))]
    (mapv (fn [c] {:sequence-reference sequenceReference
                   :coordinate c})
          coords)))

;; Minimum width of a scan to identify overlaps that
;; do not cover the edge of a feature
(def min-search-width
  {:so/Gene 500000})

(defn location->search-params
  "Generate the search params needed to identify features
  that overlap with the given location."
  [{:ga4gh/keys [start end sequenceReference]}
   feature-type]
  (let [start-coord (if (int? start) start (apply min (remove nil? start)))
        end-coord (max (+ start-coord (get min-search-width feature-type 0))
                       (if (int? end) end (apply max (remove nil? end))))]
    {:start [:sequences
             feature-type
             sequenceReference
             start-coord]
     :end [:sequences
           feature-type
           sequenceReference
           end-coord]}))

(comment
  (location->index-entries
   {:type :ga4gh/SequenceLocation,
    :ga4gh/start 17369,
    :ga4gh/end 17436,
    :ga4gh/sequenceReference
    "https://identifiers.org/refseq:NC_000001.11",
    :iri "https://genegraph.clingen.app/1j6gkJ4Yze0"})

  (location->search-params
   {:type :ga4gh/SequenceLocation,
    :ga4gh/start 17369,
    :ga4gh/end 17436,
    :ga4gh/sequenceReference
    "https://identifiers.org/refseq:NC_000001.11",
    :iri "https://genegraph.clingen.app/1j6gkJ4Yze0"}
   :so/Gene)

  (location->index-entries
   {:type :ga4gh/SequenceLocation,
    :ga4gh/start [17358 17369],
    :ga4gh/end [17400 17436],
    :ga4gh/sequenceReference
    "https://identifiers.org/refseq:NC_000001.11",
    :iri "https://genegraph.clingen.app/1j6gkJ4Yze0"})

  (location->search-params
   {:type :ga4gh/SequenceLocation,
    :ga4gh/start [17358 17369],
    :ga4gh/end [17400 17436],
    :ga4gh/sequenceReference
    "https://identifiers.org/refseq:NC_000001.11",
    :iri "https://genegraph.clingen.app/1j6gkJ4Yze0"}
   :so/Gene)

  (location->index-entries
   {:type :ga4gh/SequenceLocation,
    :ga4gh/start [nil 17369],
    :ga4gh/end [17400 nil],
    :ga4gh/sequenceReference
    "https://identifiers.org/refseq:NC_000001.11",
    :iri "https://genegraph.clingen.app/1j6gkJ4Yze0"})

  (location->search-params
   {:type :ga4gh/SequenceLocation,
    :ga4gh/start [nil 17369],
    :ga4gh/end [17400 nil],
    :ga4gh/sequenceReference
    "https://identifiers.org/refseq:NC_000001.11",
    :iri "https://genegraph.clingen.app/1j6gkJ4Yze0"}
   :so/Gene)
  )
