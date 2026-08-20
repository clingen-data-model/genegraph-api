(ns genegraph.api.ga4gh-test
  (:require [clojure.test :refer [deftest testing is are]]
            [clojure.spec.alpha :as spec]
            [genegraph.framework.id :as id]
            [genegraph.api.shared-data :as shared-data]
            [genegraph.api.ga4gh :as ga4gh]))

;;;; location-size

(deftest location-size-test
  (testing "size is the difference between end and start"
    (is (= 15 (ga4gh/location-size {:ga4gh/start 10 :ga4gh/end 25}))))
  (testing "a zero-length location has size 0"
    (is (= 0 (ga4gh/location-size {:ga4gh/start 100 :ga4gh/end 100}))))
  (testing "realistic coordinates"
    (is (= 168856
           (ga4gh/location-size
            {:type :ga4gh/SequenceLocation
             :ga4gh/sequenceReference "https://identifiers.org/refseq:NC_000002.12"
             :ga4gh/start 241988449
             :ga4gh/end 242157305})))))

;;;; max-size

(deftest max-size-test
  (testing "returns the largest location size"
    (is (= 100
           (ga4gh/max-size
            {:ga4gh/location [{:ga4gh/start 0 :ga4gh/end 10}
                              {:ga4gh/start 0 :ga4gh/end 100}
                              {:ga4gh/start 50 :ga4gh/end 90}]}))))
  (testing "nil locations are ignored"
    (is (= 95
           (ga4gh/max-size
            {:ga4gh/location [{:ga4gh/start 0 :ga4gh/end 10}
                              nil
                              {:ga4gh/start 5 :ga4gh/end 100}]}))))
  (testing "a single location"
    (is (= 171876
           (ga4gh/max-size
            {:type :cg/DosageRegion
             :ga4gh/location
             [{:ga4gh/sequenceReference "https://identifiers.org/refseq:NC_000002.11"
               :ga4gh/start 242930600
               :ga4gh/end 243102476}]}))))
  (testing "no usable locations throws -- max has no identity element"
    (is (thrown? clojure.lang.ArityException
                 (ga4gh/max-size {:ga4gh/location []})))
    (is (thrown? clojure.lang.ArityException
                 (ga4gh/max-size {:ga4gh/location [nil]})))
    (is (thrown? clojure.lang.ArityException
                 (ga4gh/max-size {})))))

;;;; build-str->build

(deftest build-str->build-test
  (testing "recognized build strings map to keywords"
    (are [s expected] (= expected (ga4gh/build-str->build s))
      "hg19"   :grch37
      "hg38"   :grch38
      "grch37" :grch37
      "grch38" :grch38
      "19"     :grch37
      "38"     :grch38))
  (testing "matching is case insensitive"
    (are [s expected] (= expected (ga4gh/build-str->build s))
      "HG19"   :grch37
      "GRCh38" :grch38
      "GRCh37" :grch37
      "Hg38"   :grch38))
  (testing "unrecognized builds pass through unchanged"
    (is (= "hg17" (ga4gh/build-str->build "hg17")))
    (is (= "GRCh36" (ga4gh/build-str->build "GRCh36")))
    (is (= "" (ga4gh/build-str->build "")))))

;;;; seq-id

(deftest seq-id-test
  (testing "bare chromosome names resolve against the requested build"
    (is (= "https://identifiers.org/refseq:NC_000001.10" (ga4gh/seq-id "1" "19")))
    (is (= "https://identifiers.org/refseq:NC_000001.11" (ga4gh/seq-id "1" "38"))))
  (testing "the chr prefix is stripped"
    (is (= (ga4gh/seq-id "1" "hg38") (ga4gh/seq-id "chr1" "hg38")))
    (is (= "https://identifiers.org/refseq:NC_000001.11" (ga4gh/seq-id "chr1" "hg38"))))
  (testing "sex chromosomes"
    (is (= "https://identifiers.org/refseq:NC_000023.10" (ga4gh/seq-id "chrX" "grch37")))
    (is (= "https://identifiers.org/refseq:NC_000024.10" (ga4gh/seq-id "chrY" "grch38"))))
  (testing "every chromosome in shared-data is reachable through seq-id"
    (doseq [[build chrs] shared-data/chr-to-ref
            [chr ref] chrs]
      (is (= ref (ga4gh/seq-id chr (name build)))
          (str "bare " chr " on " build))
      (is (= ref (ga4gh/seq-id (str "chr" chr) (name build)))
          (str "prefixed chr" chr " on " build))))
  (testing "unknown chromosome yields nil"
    (is (nil? (ga4gh/seq-id "chrZZ" "hg38")))
    (is (nil? (ga4gh/seq-id "23" "hg38"))))
  (testing "unknown build yields nil"
    (is (nil? (ga4gh/seq-id "1" "hg17")))))

;;;; ->long

(deftest ->long-test
  (testing "strings are parsed"
    (is (= 42 (ga4gh/->long "42")))
    (is (= -7 (ga4gh/->long "-7"))))
  (testing "non-strings pass through untouched"
    (is (= 42 (ga4gh/->long 42)))
    (is (nil? (ga4gh/->long nil))))
  (testing "unparseable strings throw"
    (is (thrown? NumberFormatException (ga4gh/->long "not-a-number")))))

;;;; ->ga4gh-loc

(deftest ->ga4gh-loc-exact-coordinates-test
  (testing "definite start/end produce scalar coordinates"
    (let [loc (ga4gh/->ga4gh-loc {:build "38" :chrom "chr2" :start 100 :end 200})]
      (is (= :ga4gh/SequenceLocation (:type loc)))
      (is (= "https://identifiers.org/refseq:NC_000002.12"
             (:ga4gh/sequenceReference loc)))
      (is (= 100 (:ga4gh/start loc)))
      (is (= 200 (:ga4gh/end loc)))))
  (testing "start/end win over the min/max range fields"
    (let [loc (ga4gh/->ga4gh-loc {:build "38" :chrom "2"
                                  :start 100 :end 200
                                  :start-min "1" :start-max "2"
                                  :end-min "3" :end-max "4"})]
      (is (= 100 (:ga4gh/start loc)))
      (is (= 200 (:ga4gh/end loc))))))

(deftest ->ga4gh-loc-range-coordinates-test
  (testing "distinct min/max produce a [min max] range"
    (let [loc (ga4gh/->ga4gh-loc {:build "19" :chrom "1"
                                  :start-min "900" :start-max "1000"
                                  :end-min "2000" :end-max "2010"})]
      (is (= "https://identifiers.org/refseq:NC_000001.10"
             (:ga4gh/sequenceReference loc)))
      (is (= [900 1000] (:ga4gh/start loc)))
      (is (= [2000 2010] (:ga4gh/end loc)))))
  (testing "min/max strings are coerced to longs"
    (let [loc (ga4gh/->ga4gh-loc {:build "19" :chrom "1"
                                  :start-min "900" :start-max "1000"
                                  :end-min "2000" :end-max "2010"})]
      (is (every? #(instance? Long %) (:ga4gh/start loc)))
      (is (every? #(instance? Long %) (:ga4gh/end loc)))))
  (testing "already-long min/max values are accepted"
    (is (= (ga4gh/->ga4gh-loc {:build "19" :chrom "1"
                               :start-min "900" :start-max "1000"
                               :end-min "2000" :end-max "2010"})
           (ga4gh/->ga4gh-loc {:build "19" :chrom "1"
                               :start-min 900 :start-max 1000
                               :end-min 2000 :end-max 2010}))))
  (testing "equal min and max collapse to a scalar coordinate"
    (let [loc (ga4gh/->ga4gh-loc {:build "38" :chrom "2"
                                  :start-min "100" :start-max "100"
                                  :end-min "200" :end-max "200"})]
      (is (= 100 (:ga4gh/start loc)))
      (is (= 200 (:ga4gh/end loc))))
    (testing "and are identical to the definite-coordinate form"
      (is (= (ga4gh/->ga4gh-loc {:build "38" :chrom "2" :start 100 :end 200})
             (ga4gh/->ga4gh-loc {:build "38" :chrom "2"
                                 :start-min "100" :start-max "100"
                                 :end-min "200" :end-max "200"})))))
  (testing "one bounded end and one definite end can be mixed"
    (let [loc (ga4gh/->ga4gh-loc {:build "38" :chrom "2"
                                  :start 100
                                  :end-min "200" :end-max "300"})]
      (is (= 100 (:ga4gh/start loc)))
      (is (= [200 300] (:ga4gh/end loc))))))

(deftest ->ga4gh-loc-unresolvable-sequence-test
  (testing "an unresolvable build throws rather than emitting a nil
            sequenceReference"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ga4gh/->ga4gh-loc {:build "hg17" :chrom "1" :start 1 :end 2}))))
  (testing "an unresolvable chromosome throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ga4gh/->ga4gh-loc {:build "hg38" :chrom "chrZZ"
                                     :start 1 :end 2}))))
  (testing "the thrown exception carries the offending value and an explanation"
    (let [ex (try (ga4gh/->ga4gh-loc {:build "hg17" :chrom "1" :start 1 :end 2})
                  (catch clojure.lang.ExceptionInfo e e))
          {:keys [spec value explanation]} (ex-data ex)]
      (is (= "Not a valid location description" (ex-message ex)))
      (is (= ::ga4gh/location-description spec))
      (is (= "hg17" (:build value)))
      (is (re-find #":build" explanation)))))

(deftest ->ga4gh-loc-invalid-description-test
  (testing "an incomplete coordinate range throws"
    (are [description] (thrown? clojure.lang.ExceptionInfo
                                (ga4gh/->ga4gh-loc description))
      {:build "38" :chrom "1" :start-min "100" :end 200}
      {:build "38" :chrom "1" :start 100 :end-max "200"}
      {:build "38" :chrom "1" :start 100}
      {:build "38" :chrom "1" :end 200}
      {:build "38" :chrom "1"}
      {}))
  (testing "a non-numeric coordinate throws before Long/parseLong sees it"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ga4gh/->ga4gh-loc {:build "38" :chrom "1"
                                     :start "one hundred" :end "200"}))))
  (testing "reversed coordinates fail the output spec"
    (let [ex (try (ga4gh/->ga4gh-loc {:build "38" :chrom "2" :start 200 :end 100})
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= "Did not construct a valid GA4GH SequenceLocation" (ex-message ex)))
      (is (= :ga4gh/SequenceLocation (:spec (ex-data ex)))))))

(deftest ->ga4gh-loc-iri-test
  (let [loc {:build "19" :chrom "1"
             :start-min "900" :start-max "1000"
             :end-min "2000" :end-max "2010"}]
    (testing "the iri is the content hash of the location itself"
      (let [{:keys [iri] :as l} (ga4gh/->ga4gh-loc loc)]
        (is (= iri (id/iri (dissoc l :iri))))
        (is (re-matches #"https://genegraph\.clinicalgenome\.org/r/[\w-]+" iri))))
    (testing "the iri is stable across calls"
      (is (= (:iri (ga4gh/->ga4gh-loc loc))
             (:iri (ga4gh/->ga4gh-loc loc)))))
    (testing "differing coordinates produce differing iris"
      (is (not= (:iri (ga4gh/->ga4gh-loc loc))
                (:iri (ga4gh/->ga4gh-loc (assoc loc :end-max "2011"))))))
    (testing "differing builds produce differing iris"
      (is (not= (:iri (ga4gh/->ga4gh-loc loc))
                (:iri (ga4gh/->ga4gh-loc (assoc loc :build "38"))))))
    (testing "differing chromosomes produce differing iris"
      (is (not= (:iri (ga4gh/->ga4gh-loc loc))
                (:iri (ga4gh/->ga4gh-loc (assoc loc :chrom "2"))))))
    (testing "equivalent build spellings produce the same iri"
      (is (= (:iri (ga4gh/->ga4gh-loc (assoc loc :build "hg19")))
             (:iri (ga4gh/->ga4gh-loc (assoc loc :build "GRCh37")))
             (:iri (ga4gh/->ga4gh-loc (assoc loc :build "19"))))))))

;;;; ->efo-term

(deftest ->efo-term-test
  (testing "loss synonyms"
    (are [s] (= :efo/copy-number-loss (ga4gh/->efo-term s))
      "DEL" "Deletion" "copy number loss"))
  (testing "gain synonyms"
    (are [s] (= :efo/copy-number-gain (ga4gh/->efo-term s))
      "DUP" "Duplication" "copy number gain"))
  (testing "unrecognized terms are nil; lookup is case sensitive"
    (is (nil? (ga4gh/->efo-term "INV")))
    (is (nil? (ga4gh/->efo-term "del")))
    (is (nil? (ga4gh/->efo-term nil)))))

;;;; ->ga4gh-variant

(deftest ->ga4gh-variant-test
  (let [v {:build "19" :chrom "1"
           :start-min "900" :start-max "1000"
           :end-min "2000" :end-max "2010"
           :svtype "DEL"}
        variant (ga4gh/->ga4gh-variant v)]
    (testing "shape of the produced copy number change"
      (is (= :ga4gh/CopyNumberChange (:type variant)))
      (is (= :efo/copy-number-loss (:ga4gh/copyChange variant))))
    (testing "the nested location is exactly what ->ga4gh-loc produces"
      (is (= (ga4gh/->ga4gh-loc v) (:ga4gh/location variant))))
    (testing "the iri is the content hash of the variant"
      (is (= (:iri variant) (id/iri (dissoc variant :iri))))
      (is (not= (:iri variant) (:iri (:ga4gh/location variant)))))
    (testing "duplications map to copy number gain"
      (is (= :efo/copy-number-gain
             (:ga4gh/copyChange (ga4gh/->ga4gh-variant (assoc v :svtype "DUP"))))))
    (testing "copy change participates in the iri"
      (is (not= (:iri variant)
                (:iri (ga4gh/->ga4gh-variant (assoc v :svtype "DUP"))))))
    (testing "location participates in the iri"
      (is (not= (:iri variant)
                (:iri (ga4gh/->ga4gh-variant (assoc v :chrom "2"))))))
    (testing "the iri is stable across calls"
      (is (= (:iri variant) (:iri (ga4gh/->ga4gh-variant v)))))
    (testing "svtype synonyms are interchangeable"
      (is (= (ga4gh/->ga4gh-variant (assoc v :svtype "DEL"))
             (ga4gh/->ga4gh-variant (assoc v :svtype "Deletion"))
             (ga4gh/->ga4gh-variant (assoc v :svtype "copy number loss")))))))

(deftest ->ga4gh-variant-unsupported-svtype-test
  (let [base {:build "38" :chrom "1" :start 1 :end 2}]
    (testing "an unmapped svtype throws rather than yielding a nil copyChange"
      (are [svtype] (thrown? clojure.lang.ExceptionInfo
                             (ga4gh/->ga4gh-variant (assoc base :svtype svtype)))
        "INV" "INS" "del" ""))
    (testing "a missing svtype throws"
      (is (thrown? clojure.lang.ExceptionInfo
                   (ga4gh/->ga4gh-variant base))))
    (testing "the thrown exception names the description spec"
      (let [ex (try (ga4gh/->ga4gh-variant (assoc base :svtype "INV"))
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (= "Not a valid variant description" (ex-message ex)))
        (is (= ::ga4gh/variant-description (:spec (ex-data ex))))))))

(deftest ->ga4gh-variant-invalid-description-test
  (testing "a bad build is rejected by the variant, not just the location"
    (let [ex (try (ga4gh/->ga4gh-variant {:build "hg17" :chrom "1"
                                          :start 1 :end 2 :svtype "DEL"})
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= ::ga4gh/variant-description (:spec (ex-data ex))))))
  (testing "reversed coordinates fail on the nested location"
    (let [ex (try (ga4gh/->ga4gh-variant {:build "38" :chrom "2"
                                          :start 200 :end 100 :svtype "DEL"})
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :ga4gh/SequenceLocation (:spec (ex-data ex)))))))

;;;; Specs -- intermediate variant description (input)

(def ^:private a-variant-description
  {:build "19" :chrom "1"
   :start-min "900" :start-max "1000"
   :end-min "2000" :end-max "2010"
   :svtype "DEL"})

(deftest variant-description-spec-test
  (testing "a well formed description conforms"
    (is (spec/valid? ::ga4gh/variant-description a-variant-description))
    (is (spec/valid? ::ga4gh/variant-description
                     {:build "GRCh38" :chrom "chrX" :start 1 :end 2
                      :svtype "Duplication"})))
  (testing "the build must map to a known assembly"
    (are [build] (spec/valid? ::ga4gh/variant-description
                              (assoc a-variant-description :build build))
      "hg19" "hg38" "GRCh37" "grch38" "19" "38")
    (are [build] (not (spec/valid? ::ga4gh/variant-description
                                   (assoc a-variant-description :build build)))
      "hg17" "GRCh36" "" "b37"))
  (testing "the svtype must map to an EFO copy change term"
    (are [svtype] (spec/valid? ::ga4gh/variant-description
                               (assoc a-variant-description :svtype svtype))
      "DEL" "DUP" "Deletion" "Duplication" "copy number loss" "copy number gain")
    (are [svtype] (not (spec/valid? ::ga4gh/variant-description
                                    (assoc a-variant-description :svtype svtype)))
      "INV" "INS" "del" "")
    (is (not (spec/valid? ::ga4gh/variant-description
                          (dissoc a-variant-description :svtype)))))
  (testing "the chrom must resolve against the given build"
    (is (not (spec/valid? ::ga4gh/variant-description
                          (assoc a-variant-description :chrom "chrZZ"))))
    (is (not (spec/valid? ::ga4gh/variant-description
                          (assoc a-variant-description :chrom "23")))))
  (testing "each end needs either a definite coordinate or a complete range"
    (are [description] (not (spec/valid? ::ga4gh/variant-description description))
      (dissoc a-variant-description :start-min)
      (dissoc a-variant-description :start-max)
      (dissoc a-variant-description :end-min)
      (dissoc a-variant-description :end-max)
      (dissoc a-variant-description :start-min :start-max :end-min :end-max))
    (is (spec/valid? ::ga4gh/variant-description
                     (-> a-variant-description
                         (dissoc :start-min :start-max)
                         (assoc :start 900)))))
  (testing "coordinates are digit strings or non-negative integers"
    (are [start] (not (spec/valid? ::ga4gh/variant-description
                                   (assoc a-variant-description :start-min start)))
      "nine hundred" "-900" -900 900.5 nil))
  (testing "location-description is the same shape without the svtype"
    (is (spec/valid? ::ga4gh/location-description
                     (dissoc a-variant-description :svtype)))
    (is (not (spec/valid? ::ga4gh/location-description
                          (assoc a-variant-description :build "hg17"))))))

;;;; Specs -- GA4GH VRS entities (output)

(deftest sequence-location-spec-test
  (testing "->ga4gh-loc output conforms, for both range and definite coordinates"
    (is (spec/valid? :ga4gh/SequenceLocation
                     (ga4gh/->ga4gh-loc a-variant-description)))
    (is (spec/valid? :ga4gh/SequenceLocation
                     (ga4gh/->ga4gh-loc {:build "38" :chrom "2"
                                         :start 100 :end 200}))))
  (testing "every chromosome/build pair produces a conforming location"
    (doseq [[build chrs] shared-data/chr-to-ref
            chr (keys chrs)]
      (is (spec/valid? :ga4gh/SequenceLocation
                       (ga4gh/->ga4gh-loc {:build (name build) :chrom chr
                                           :start 1 :end 2}))
          (str chr " on " build))))
  (testing "an unresolvable sequence reference does not conform"
    (is (not (spec/valid? :ga4gh/SequenceLocation
                          (assoc (ga4gh/->ga4gh-loc a-variant-description)
                                 :ga4gh/sequenceReference nil)))))
  (testing "the sequence reference must be a known RefSeq IRI"
    (is (not (spec/valid? :ga4gh/SequenceLocation
                          (assoc (ga4gh/->ga4gh-loc a-variant-description)
                                 :ga4gh/sequenceReference
                                 "https://identifiers.org/refseq:NC_999999.1")))))
  (testing "start must not follow end"
    (let [loc (ga4gh/->ga4gh-loc a-variant-description)]
      (is (not (spec/valid? :ga4gh/SequenceLocation
                            (assoc loc :ga4gh/start 3000))))
      (is (not (spec/valid? :ga4gh/SequenceLocation
                            (assoc loc :ga4gh/start [3000 4000]))))))
  (testing "an indefinite coordinate must be an ordered pair"
    (are [start] (not (spec/valid? :ga4gh/SequenceLocation
                                   (assoc (ga4gh/->ga4gh-loc a-variant-description)
                                          :ga4gh/start start)))
      [1000 900] [900] [900 1000 1100] "900" -900 nil))
  (testing "required keys and type"
    (are [k] (not (spec/valid? :ga4gh/SequenceLocation
                               (dissoc (ga4gh/->ga4gh-loc a-variant-description) k)))
      :ga4gh/sequenceReference :ga4gh/start :ga4gh/end :iri)
    (is (not (spec/valid? :ga4gh/SequenceLocation
                          (assoc (ga4gh/->ga4gh-loc a-variant-description)
                                 :type :ga4gh/CopyNumberChange))))))

(deftest copy-number-change-spec-test
  (testing "->ga4gh-variant output conforms"
    (is (spec/valid? :ga4gh/CopyNumberChange
                     (ga4gh/->ga4gh-variant a-variant-description)))
    (is (spec/valid? :ga4gh/CopyNumberChange
                     (ga4gh/->ga4gh-variant
                      (assoc a-variant-description :svtype "DUP")))))
  (testing "a nil copyChange, as an unmapped svtype would produce, does not conform"
    (is (not (spec/valid? :ga4gh/CopyNumberChange
                          (assoc (ga4gh/->ga4gh-variant a-variant-description)
                                 :ga4gh/copyChange nil)))))
  (testing "copyChange must be an EFO copy number term"
    (are [term] (not (spec/valid? :ga4gh/CopyNumberChange
                                  (assoc (ga4gh/->ga4gh-variant a-variant-description)
                                         :ga4gh/copyChange term)))
      :efo/copy-number-change "DEL" nil))
  (testing "an invalid nested location invalidates the variant"
    (is (not (spec/valid? :ga4gh/CopyNumberChange
                          (update (ga4gh/->ga4gh-variant a-variant-description)
                                  :ga4gh/location dissoc :ga4gh/start)))))
  (testing "the location must be a single location, not a collection"
    (let [variant (ga4gh/->ga4gh-variant a-variant-description)]
      (is (not (spec/valid? :ga4gh/CopyNumberChange
                            (update variant :ga4gh/location vector))))))
  (testing "required keys and type"
    (are [k] (not (spec/valid? :ga4gh/CopyNumberChange
                               (dissoc (ga4gh/->ga4gh-variant a-variant-description) k)))
      :ga4gh/copyChange :ga4gh/location :iri)
    (is (not (spec/valid? :ga4gh/CopyNumberChange
                          (assoc (ga4gh/->ga4gh-variant a-variant-description)
                                 :type :ga4gh/SequenceLocation)))))
  (testing "a location is not a copy number change and vice versa"
    (is (not (spec/valid? :ga4gh/SequenceLocation
                          (ga4gh/->ga4gh-variant a-variant-description))))
    (is (not (spec/valid? :ga4gh/CopyNumberChange
                          (ga4gh/->ga4gh-loc a-variant-description))))))

(deftest location-collection-spec-test
  (testing ":ga4gh/location accepts a single location or a sequence of them"
    (let [loc (ga4gh/->ga4gh-loc a-variant-description)]
      (is (spec/valid? :ga4gh/location loc))
      (is (spec/valid? :ga4gh/location [loc]))
      (testing "including the nils max-size tolerates"
        (is (spec/valid? :ga4gh/location [loc nil])))
      (testing "but not invalid members"
        (is (not (spec/valid? :ga4gh/location
                              [loc (dissoc loc :ga4gh/start)]))))))
  (testing "specs preserve values rather than leaking spec/or branch tags"
    (let [variant (ga4gh/->ga4gh-variant a-variant-description)]
      (is (= variant (spec/conform :ga4gh/CopyNumberChange variant)))
      (is (= a-variant-description
             (spec/conform ::ga4gh/variant-description a-variant-description))))))
