(ns genegraph.api.ga4gh
  "Convenience methods for working with ga4gh defined
  or related entities"
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as spec]
            [genegraph.framework.id :as id]
            [genegraph.api.shared-data :as shared-data]))

(id/register-type {:type :ga4gh/SequenceLocation
                   :defining-attributes
                   [:ga4gh/sequenceReference :ga4gh/start :ga4gh/end]})

(id/register-type {:type :ga4gh/CopyNumberChange
                   :defining-attributes
                   [:ga4gh/location :ga4gh/copyChange]})

(id/register-type {:type :cg/VariantPathogenicityProposition
                   :defining-attributes
                   [:cg/variant :cg/condition]})

(defn validate!
  "Return value when it conforms to spec, otherwise throw. Specs are resolved
  from the registry at call time, so the specs relied on here may be (and are)
  registered further down the file."
  [spec value message]
  (if (spec/valid? spec value)
    value
    (throw (ex-info message
                    {:spec spec
                     :value value
                     :explanation (spec/explain-str spec value)
                     ::spec/problems (::spec/problems
                                      (spec/explain-data spec value))}))))

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

(def build-str->build-kw
  {"hg19" :grch37
   "hg38" :grch38
   "grch38" :grch38
   "grch37" :grch37
   "19" :grch37
   "38" :grch38})

(defn build-str->build [str]
  (if-let [b (build-str->build-kw (str/lower-case str))]
    b
    str))

(defn seq-id [chr-string build-str]
  (let [build (build-str->build build-str)
        chr-digits (or (second (re-find #"chr(.+)$" chr-string)) chr-string)]
    (get (get shared-data/chr-to-ref build) chr-digits)))

(defn ->long [s]
  (if (string? s)
    (Long/parseLong s)
    s))

(defn ->ga4gh-loc
  "Convert a location described in the intermediate format into a GA4GH
  SequenceLocation. Throws if the description, or the location built from
  it, does not conform to spec."
  [{:keys [build chrom end-min end-max end start start-min start-max]
    :as location-description}]
  (validate! ::location-description
             location-description
             "Not a valid location description")
  (let [start-min (->long start-min)
        start-max (->long start-max)
        end-min (->long end-min)
        end-max (->long end-max)
        end (->long end)
        start (->long start)
        loc {:ga4gh/sequenceReference (seq-id chrom build)
             :ga4gh/start (if (and (not start) (not= start-min start-max))
                            [start-min start-max]
                            (or start start-min))
             :ga4gh/end (if (and (not end) (not= end-min end-max))
                          [end-min end-max]
                          (or end end-min))
             :type :ga4gh/SequenceLocation}]
    (validate! :ga4gh/SequenceLocation
               (assoc loc :iri (id/iri loc))
               "Did not construct a valid GA4GH SequenceLocation")))

(def ->efo-term
  {"DEL" :efo/copy-number-loss
   "DUP" :efo/copy-number-gain
   "Deletion" :efo/copy-number-loss
   "copy number loss" :efo/copy-number-loss
   "copy number gain" :efo/copy-number-gain
   "Duplication" :efo/copy-number-gain})

(defn ->ga4gh-variant
  "Convert a variant based on an intermediate description format into GA4GH VRS format.
  Currently only supports copy number variants. Throws if the description, or the
  variant built from it, does not conform to spec."
  [{:keys [svtype] :as variant-description}]
  (validate! ::variant-description
             variant-description
             "Not a valid variant description")
  (let [variant {:type :ga4gh/CopyNumberChange
                 :ga4gh/copyChange (->efo-term svtype)
                 :ga4gh/location (->ga4gh-loc variant-description)}]
    (validate! :ga4gh/CopyNumberChange
               (assoc variant :iri (id/iri variant))
               "Did not construct a valid GA4GH CopyNumberChange")))

;;;; Specs
;;;;
;;;; Two shapes are described here: the intermediate variant description
;;;; accepted by ->ga4gh-loc / ->ga4gh-variant (unqualified keys, coordinates
;;;; possibly still strings), and the GA4GH VRS entities they produce
;;;; (:ga4gh/ qualified keys, coordinates coerced to longs).
;;;;
;;;; The spec/or forms below are wrapped in spec/nonconforming: spec/keys
;;;; conforms the values of qualified keys, and the resulting branch tags
;;;; would otherwise be what the map-level predicates see.

;; Shared

(spec/def ::iri (spec/and string? #(re-matches #"https?://\S+" %)))

(def sequence-references
  "The set of RefSeq sequence IRIs a chromosome/build pair can resolve to."
  (into #{} (mapcat vals) (vals shared-data/chr-to-ref)))

(def copy-change-terms
  "EFO terms svtypes are translated into."
  (into #{} (vals ->efo-term)))

;;; Intermediate variant description (input)

(spec/def ::build
  (spec/and string? #(contains? build-str->build-kw (str/lower-case %))))

(spec/def ::chrom string?)

(spec/def ::svtype (set (keys ->efo-term)))

(spec/def ::coordinate
  (spec/nonconforming
   (spec/or :long (spec/and int? #(<= 0 %))
            :string (spec/and string? #(re-matches #"\d+" %)))))

(spec/def ::start ::coordinate)
(spec/def ::end ::coordinate)
(spec/def ::start-min ::coordinate)
(spec/def ::start-max ::coordinate)
(spec/def ::end-min ::coordinate)
(spec/def ::end-max ::coordinate)

(defn resolvable-sequence?
  "True when the chrom/build pair names a sequence known to shared-data.
  Guards against ->ga4gh-loc silently emitting a nil sequenceReference."
  [{:keys [chrom build]}]
  (some? (seq-id chrom build)))

(spec/def ::location-description
  (spec/and (spec/keys :req-un [::build
                                ::chrom
                                (or ::start (and ::start-min ::start-max))
                                (or ::end (and ::end-min ::end-max))])
            resolvable-sequence?))

(spec/def ::variant-description
  (spec/and ::location-description
            (spec/keys :req-un [::svtype])))

;;; GA4GH VRS entities (output)

(spec/def ::residue-coordinate (spec/and int? #(<= 0 %)))

(spec/def ::coordinate-range
  (spec/and (spec/tuple ::residue-coordinate ::residue-coordinate)
            (fn [[min-coord max-coord]] (<= min-coord max-coord))))

(spec/def ::definite-or-indefinite-coordinate
  (spec/nonconforming
   (spec/or :definite ::residue-coordinate
            :indefinite ::coordinate-range)))

(spec/def :ga4gh/sequenceReference sequence-references)
(spec/def :ga4gh/start ::definite-or-indefinite-coordinate)
(spec/def :ga4gh/end ::definite-or-indefinite-coordinate)
(spec/def :ga4gh/copyChange copy-change-terms)

(defn- lower-bound [coordinate]
  (if (vector? coordinate) (first coordinate) coordinate))

(defn- upper-bound [coordinate]
  (if (vector? coordinate) (second coordinate) coordinate))

(defn- start-precedes-end? [{:ga4gh/keys [start end]}]
  (<= (upper-bound start) (lower-bound end)))

(defn- type-is [t]
  (fn [entity] (= t (:type entity))))

(spec/def :ga4gh/SequenceLocation
  (spec/and (spec/keys :req [:ga4gh/sequenceReference :ga4gh/start :ga4gh/end]
                       :req-un [::iri])
            (type-is :ga4gh/SequenceLocation)
            start-precedes-end?))

;; :ga4gh/location holds a single location on a variant, but a sequence of
;; them on canonical entities such as :cg/DosageRegion (cf. max-size).
(spec/def :ga4gh/location
  (spec/nonconforming
   (spec/or :location :ga4gh/SequenceLocation
            :locations (spec/coll-of (spec/nilable :ga4gh/SequenceLocation)
                                     :kind sequential?))))

(defn- single-location? [entity]
  (spec/valid? :ga4gh/SequenceLocation (:ga4gh/location entity)))

(spec/def :ga4gh/CopyNumberChange
  (spec/and (spec/keys :req [:ga4gh/copyChange :ga4gh/location]
                       :req-un [::iri])
            (type-is :ga4gh/CopyNumberChange)
            single-location?))

;;; Function specs. Inert unless instrumented or checked.

(spec/fdef ->ga4gh-loc
  :args (spec/cat :location-description ::location-description)
  :ret :ga4gh/SequenceLocation)

(spec/fdef ->ga4gh-variant
  :args (spec/cat :variant-description ::variant-description)
  :ret :ga4gh/CopyNumberChange)

(spec/fdef location-size
  :args (spec/cat :location :ga4gh/SequenceLocation)
  :ret nat-int?)

(spec/fdef build-str->build
  :args (spec/cat :str string?))

(spec/fdef seq-id
  :args (spec/cat :chr-string string? :build-str string?)
  :ret (spec/nilable sequence-references))

(comment
  (->ga4gh-loc {:build "19"
                :chrom "1"
                :start-min "900"
                :start-max "1000"
                :end-min "2000"
                :end-max "2010"})
  
  (->ga4gh-variant {:build "19"
                    :chrom "1"
                    :start-min "900"
                    :start-max "1000"
                    :end-min "2000"
                    :end-max "2010"
                    :svtype "DEL"})
  
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
