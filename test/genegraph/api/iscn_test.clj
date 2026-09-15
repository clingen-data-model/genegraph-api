(ns genegraph.api.iscn-test
  (:require [clojure.test :refer [deftest testing is are]]
            [clojure.spec.alpha :as spec]
            [genegraph.api.ga4gh :as ga4gh]
            [genegraph.api.iscn :as iscn]))

(def ^:private a-deletion
  "arr[hg19] 5p15.2(11,397,258-11,419,020 )x1 mat")

(def ^:private two-variants-one-line
  "arr[hg19] 6p25.3p25.2(156,974-3,503,055)x1 mat,14q32.12q32.33(92,192,180-107,285,437)x3 mat  Gender: Male")

(deftest copy-number->svtype-test
  (testing "copy number maps to the direction of the change"
    (are [copy-number svtype] (= svtype (iscn/copy-number->svtype copy-number))
      "0" "copy number loss"
      "1" "copy number loss"
      "3" "copy number gain"
      "4" "copy number gain"))
  (testing "two copies is the reference state, not a change"
    (is (nil? (iscn/copy-number->svtype "2"))))
  (testing "every term produced is one ga4gh accepts"
    (is (every? (set (keys ga4gh/->efo-term))
                (vals iscn/copy-number->svtype)))))

(deftest fields->variant-description-test
  (let [description (iscn/fields->variant-description
                     (iscn/iscn->fields a-deletion))]
    (testing "the pieces ga4gh needs are pulled out of the match"
      (is (= "hg19" (:build description)))
      (is (= "5" (:chrom description)))
      (is (= "11397258" (:start description)))
      (is (= "11419020" (:end description)))
      (is (= "copy number loss" (:svtype description))))
    (testing "thousands separators are stripped from coordinates"
      (is (every? #(re-matches #"\d+" %) [(:start description)
                                          (:end description)])))
    (testing "the ISCN specific detail rides along"
      (is (= "5p15.2" (:cytoband description)))
      (is (= 1 (:copy-number description)))
      (is (= :maternal (:inheritance description)))
      (is (= a-deletion (:iscn description))))
    (testing "the result is a description ga4gh will accept"
      (is (spec/valid? ::ga4gh/variant-description description))))
  (testing "inheritance is read from the expression"
    (are [expr origin] (= origin (:inheritance
                                  (iscn/fields->variant-description
                                   (iscn/iscn->fields expr))))
      "arr[hg19] 5p15.2(11,397,258-11,419,020)x1 mat" :maternal
      "arr[hg19] 3p24.2p24.1(25,939,688-27,385,719)x1pat" :paternal
      "arr[hg19] 11q14.1(81,273,666-84,395,140)x1dn" :de-novo))
  (testing "underscore separated coordinates parse the same as hyphenated ones"
    (is (= (dissoc (iscn/fields->variant-description
                    (iscn/iscn->fields "arr[GRCh37] 17q12(34466631-36244358)x1dn"))
                   :iscn)
           (dissoc (iscn/fields->variant-description
                    (iscn/iscn->fields "arr[GRCh37] 17q12(34466631_36244358)x1dn"))
                   :iscn)))))

(deftest iscn->variant-descriptions-test
  (testing "one variant per match"
    (is (= 1 (count (iscn/iscn->variant-descriptions a-deletion))))
    (is (= 2 (count (iscn/iscn->variant-descriptions two-variants-one-line)))))
  (testing "a variant written after the arr[build] prefix inherits the build"
    (let [[first-variant second-variant]
          (iscn/iscn->variant-descriptions two-variants-one-line)]
      (is (nil? (second (second (iscn/iscn->all-fields two-variants-one-line))))
          "the second match carries no build of its own")
      (is (= "hg19" (:build first-variant) (:build second-variant)))
      (is (= ["6" "14"] [(:chrom first-variant) (:chrom second-variant)]))))
  (testing "an expression the regex does not match yields nothing"
    (is (= [] (iscn/iscn->variant-descriptions (first iscn/mayo))))))

(deftest description->observation-test
  (testing "a convertible description gains a ga4gh variant"
    (let [{:keys [variant] :as observation}
          (iscn/description->observation
           (iscn/fields->variant-description (iscn/iscn->fields a-deletion)))]
      (is (spec/valid? :ga4gh/CopyNumberChange variant))
      (is (= :efo/copy-number-loss (:ga4gh/copyChange variant)))
      (is (= "https://identifiers.org/refseq:NC_000005.9"
             (get-in variant [:ga4gh/location :ga4gh/sequenceReference])))
      (is (= 11397258 (get-in variant [:ga4gh/location :ga4gh/start])))
      (is (= 11419020 (get-in variant [:ga4gh/location :ga4gh/end])))
      (is (nil? (:error observation)))
      (testing "and keeps the description it was built from"
        (is (= "5p15.2" (:cytoband observation)))
        (is (= :maternal (:inheritance observation))))))
  (testing "an unconvertible description reports rather than throws"
    (let [{:keys [variant error]}
          (iscn/description->observation
           (iscn/fields->variant-description
            (iscn/iscn->fields "arr[hg19] Xq25(121,644,403-123,705,178)x2 mat")))]
      (is (nil? variant))
      (is (= "Not a valid variant description" (:message error)))
      (is (re-find #":svtype" (:explanation error)))))
  (testing "a build outside the reference is reported, not thrown"
    (let [{:keys [variant error]}
          (iscn/description->observation
           (iscn/fields->variant-description
            (iscn/iscn->fields "arr[T2T] 5p15.2(11,397,258-11,419,020)x1 mat")))]
      (is (nil? variant))
      (is (some? error)))))

(deftest variant-set-test
  (let [{:keys [variants observations unconvertible unparsed]}
        (iscn/variant-set (concat iscn/mayo iscn/trillium))]
    (testing "every converted variant is a valid CopyNumberChange"
      (is (every? #(spec/valid? :ga4gh/CopyNumberChange %) variants)))
    (testing "variants are deduplicated by content"
      (is (= (count variants) (count (into #{} (map :iri) variants))))
      (is (< (count variants)
             (count (filter :variant observations)))
          "the sample repeats intervals across probands"))
    (testing "the unconvertible are the copy-number-two calls"
      (is (= 3 (count unconvertible)))
      (is (every? #(= 2 (:copy-number %)) unconvertible))
      (is (every? :error unconvertible)))
    (testing "an expression outside the regex is reported as unparsed"
      (is (= [(first iscn/mayo)] unparsed)))
    (testing "observations account for every parsed variant"
      (is (= (count observations)
             (+ (count (filter :variant observations))
                (count unconvertible)))))
    (testing "provenance survives on the observation, not the variant"
      (is (every? :iscn observations))
      (is (not-any? :iscn variants))))
  (testing "an empty batch is not an error"
    (is (= {:variants #{} :observations [] :unconvertible [] :unparsed []}
           (iscn/variant-set [])))))

(deftest locations-test
  (let [variant-set (iscn/variant-set iscn/trillium)
        locs (iscn/locations variant-set)]
    (testing "every location is a valid SequenceLocation"
      (is (every? #(spec/valid? :ga4gh/SequenceLocation %) locs)))
    (testing "one location per distinct variant here, since none share an interval"
      (is (= (count (:variants variant-set)) (count locs))))
    (testing "the locations are the ones the variants point at"
      (is (= locs (into #{} (map :ga4gh/location) (:variants variant-set)))))))
