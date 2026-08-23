(ns genegraph.api.iscn
  "Namespace for converting loosely structured files with an ISCN defined variant
  at the core"
  (:require [clojure.string :as str]
            [genegraph.api.ga4gh :as ga4gh]))
;; Captures, in order:
;;   1 build        e.g. hg19, GRCh37, GRCH37
;;   2 cytoband     e.g. 2q33.1q33.2, 5p15.2, Xp22.13
;;   3 start        e.g. 11,397,258 or 34466631
;;   4 end          e.g. 11,419,020 or 36307189
;;   5 copy-number  the digit after x (1-4)
;;   6 inheritance  mat, pat, or dn
;; the arr[build] prefix is optional so trailing comma-separated
;; variants on one line are also captured (with build = nil)
(def iscn-re
  #"(?:arr\[([^\]]+)\]\s*)?([XY\d]+)([pq][\dpq.]+)\s*\(\s*([\d,]+)[-_]([\d,]+)\s*\)\s*x(\d+)(?:\[[\d.]+\])?\s*(mat|pat|dn)")

(defn iscn->fields [iscn-expr]
  (re-find iscn-re iscn-expr))

;; a single expression may hold multiple comma-separated variants
(defn iscn->all-fields [iscn-expr]
  (re-seq iscn-re iscn-expr))

;;;; Converting parsed ISCN into GA4GH variants
;;;;
;;;; iscn->fields yields regex groups; genegraph.api.ga4gh takes an
;;;; intermediate description map and returns VRS entities. These fns bridge
;;;; the two, and do it over collections, since one expression may name several
;;;; variants and a file names many expressions.

(def copy-number->svtype
  "ISCN reports the copy number observed; GA4GH describes the direction of the
  change. Two copies is the diploid reference state and so describes no change
  at all -- it deliberately has no term here, and variants reporting it are
  reported as unconvertible rather than silently assigned a direction."
  {"0" "copy number loss"
   "1" "copy number loss"
   "3" "copy number gain"
   "4" "copy number gain"})

(def inheritance->allele-origin
  {"mat" :maternal
   "pat" :paternal
   "dn" :de-novo})

(defn- ->coordinate
  "ISCN coordinates are often written with thousands separators."
  [s]
  (str/replace s "," ""))

(defn fields->variant-description
  "Convert one iscn->fields match into the intermediate description format
  genegraph.api.ga4gh understands. The build is accepted separately because the
  arr[build] prefix is written once per expression rather than once per variant.
  Keys beyond the ones ga4gh requires ride along; its specs tolerate them."
  ([fields] (fields->variant-description fields (second fields)))
  ([[iscn _ chrom cytoband start end copy-number inheritance] build]
   {:iscn iscn
    :build build
    :chrom chrom
    :cytoband (str chrom cytoband)
    :start (->coordinate start)
    :end (->coordinate end)
    :copy-number (parse-long copy-number)
    :svtype (copy-number->svtype copy-number)
    :inheritance (inheritance->allele-origin inheritance)}))

(defn iscn->variant-descriptions
  "Parse every variant in one ISCN expression into intermediate descriptions.
  The arr[build] prefix appears at most once per expression, so variants written
  after it inherit its build."
  [iscn-expr]
  (let [matches (iscn->all-fields iscn-expr)
        build (some second matches)]
    (mapv #(fields->variant-description % build) matches)))

(defn description->observation
  "Build the GA4GH CopyNumberChange for one description, returning the
  description with the result assoced under :variant.

  ga4gh/->ga4gh-variant throws on anything it cannot represent -- a build or
  chromosome outside the reference, a copy number of two -- so the failure is
  captured under :error instead. A single unusable line should not cost the rest
  of the batch."
  [description]
  (try
    (assoc description :variant (ga4gh/->ga4gh-variant description))
    (catch clojure.lang.ExceptionInfo e
      (assoc description
             :error {:message (ex-message e)
                     :explanation (:explanation (ex-data e))}))))

(defn iscn->observations
  "Every variant in one ISCN expression, converted to GA4GH where possible."
  [iscn-expr]
  (mapv description->observation (iscn->variant-descriptions iscn-expr)))

(defn variant-set
  "Convert a collection of ISCN expressions into the set of distinct GA4GH
  variants they describe. Returns a map of

    :variants      the distinct :ga4gh/CopyNumberChange entities. Deduplicated
                   by value -- their :iri is content addressed, so the same
                   interval reported for two probands is one variant.
    :observations  one entry per parsed variant: the intermediate description,
                   including cytoband and inheritance, plus the :variant it
                   produced (or an :error explaining why it produced none).
                   This is where the link between a variant and the report it
                   came from survives; the variants themselves carry no
                   provenance.
    :unconvertible the observations that failed, split out for convenience.
    :unparsed      expressions the ISCN regex did not match at all.

  Nothing here throws; an expression that cannot be handled is reported."
  [iscn-exprs]
  (let [observations (into [] (mapcat iscn->observations) iscn-exprs)
        {converted true unconvertible false} (group-by (comp some? :variant)
                                                       observations)]
    {:variants (into #{} (map :variant) converted)
     :observations observations
     :unconvertible (vec unconvertible)
     :unparsed (vec (remove iscn->fields iscn-exprs))}))

(defn locations
  "The distinct GA4GH SequenceLocations in a variant set. Useful on its own for
  overlap queries, which work over loci rather than variants."
  [{:keys [variants]}]
  (into #{} (map :ga4gh/location) variants))


(comment
  (iscn->fields "arr[hg19] 5p15.2(11,397,258-11,419,020 )x1 mat")
  
  (tap> (map iscn->fields (concat mayo trillium)))
  (->> (concat mayo trillium)
       (map iscn->all-fields )
       (map count)
       frequencies)

  (iscn->observations
   "arr[hg19] 6p25.3p25.2(156,974-3,503,055)x1 mat,14q32.12q32.33(92,192,180-107,285,437)x3 mat")

  ;; 90 expressions -> 87 convertible variants, 85 of them distinct
  (let [{:keys [variants unconvertible unparsed]}
        (variant-set (concat mayo trillium))]
    {:variants (count variants)
     :unconvertible (mapv :iscn unconvertible)
     :unparsed unparsed})

  (tap> (variant-set (concat mayo trillium)))

  ;; the loci, ready for an overlap query
  (locations (variant-set mayo))
  )
;; mayo sample
(def mayo
  ["arr[hg19] 2q33.1q33.2(203235523x2,203245664-203326770x1,203332494x2) mat  Gender:  Female"
   "arr[hg19] 5p15.2(11,397,258-11,419,020 )x1 mat"
   "arr[hg19] 2q14.2(120,502,945-121,236,771)x1 mat"
   "arr[hg19] 2q14.2(120,502,945-121,236,771)x1 mat"
   "arr[hg19] 8p23.2(3,013,589-5,968,616)x1 mat"
   "arr[hg19] 4q34.1(174,867,666-175,759,626)x3 mat"
   "arr[hg19] 17p13.3(525-775,054)x1 mat"
   "arr[hg19] 20p12.1(16,581,402-17,327,672)x3 mat"
   "arr[hg19] Xp22.13(17,674,125-17,991,059)x2 mat"
   "arr[hg19] 15q11.2(22,770,421-23,291,159)x1 mat"
   "arr[hg19] 10p12.31(18,835,353-19,369,899)x3 mat"
   "arr[hg19] 10q22.3(79,258,849-79,893,201)x3 mat"
   "arr[hg19] Xq25(121,644,403-123,705,178)x2 mat"
   "arr[hg19] Xq25(121,644,403-123,705,178)x2 mat"
   "arr[hg19] 3q24(143,591,604-144,356,776)x1 mat"
   "arr[hg19] 3q24(143,591,604-144,356,776)x1 mat"
   "arr[hg19] 6p25.3p25.2(156,974-3,503,055)x1 mat,14q32.12q32.33(92,192,180-107,285,437)x3 mat  Gender: Male"])

;; Trillium sample
(def trillium
  ["arr[hg19] 17p12(13,109,513-13,646,829)x3dn "
   "arr[hg19] 3p24.2p24.1(25,939,688-27,385,719)x1pat"
   "arr[hg19] 11q14.1(81,273,666-84,395,140)x1dn"
   "arr[hg19] 1q31.2q32.1(193,359,387-199,276,370)x1dn "
   "arr[hg19] 5q31.2(136345401_136489038)x1mat"
   "arr[hg19] 16p11.2(29,439,299-30,190,539)x1dn"
   "arr[hg19] 1q21.1(145,382,387-145,833,025)x3dn"
   "arr[hg19] 7q11.23(72,643,903-74,361,068)x3dn"
   "arr[hg19] 14q11.2(21,556,466-21,937,621)x3dn"
   "arr[hg19] 2q12.3q13(108,540,608-110,524,289)x3dn"
   "arr[hg19] 3p26.3(2,482,823-2,665,437)x3mat"
   "arr[hg19] 20q13.33(61,830,263-62,908,679)x1dn"
   "arr[GRCh37] 17q12(34466631_36244358)x1dn"
   "arr[GRCh37] 2p24.2p24.1(18795283_22021330)x1dn"
   "arr[GRCh37] 15q11.2(22652330_23226254)x1dn"
   "arr[GRCh37] 9p24.3p24.1(46587_6910916)x1dn"
   "arr[GRCh37] 18q21.2(52943093_53017695)x1dn"
   "arr[GRCh37] 17q21.31(43703798_44212727)x1dn"
   "arr[GRCh37] 2q11.1q13(95778365_110897562)x4[0.4]dn"
   "arr[GRCh37] 22q13.33(51078251_51164602)x3dn"
   "arr[GRCh37] 3q29(196796090_196941682)x1dn"
   "arr[GRCh37] 1p36.12(21742614_27403761)x3dn"
   "arr[GRCh37] 14q23.2(67437493_67640666)x1dn"
   "arr[GRCh37] 17q22q23.1(55251617_57775463)x1dn"
   "arr[GRCh37] 2q37.3(237415521_243048760)x1dn"
   "arr[GRCh37] 8q24.4(145603114_146293414)x3[0.88] dn"
   "arr[GRCh37] 9p22.1p21.3(18866563_21199680)x3dn"
   "arr[GRCh37] 11q14.1q21(83405905_95099182)x1mat"
   "arr[GRCH37]17q12(34815551_36307189)x3dn"
   "arr[GRCH37]17q24.1q25.3(63162476_81060040)x3dn"
   "arr[GRCh37] 18p11.32p11.23(13034_7658679)x1dn"
   "arr[GRCh37] Xq13.1q13.2(71711944_72080026)x1dn"
   "arr[GRCh37] 9p24.1(5055434_5765984)x3pat"
   "arr[GRCh37] 15q11.2q13.1(23683783_28535266)x3dn"
   "arr[GRCh37] 7q21.11(78090357_85082956)x1dn"
   "arr[GRCh37] 22q11.21(19024651_21463730)x1dn"
   "arr[GRCh37] 22q11.21(18861748_21463730)x1dn"
   "arr[GRCh37] 3p24.3(19312697_19754793)x1dn"
   "arr[GRCh37] 1p32.1p31.1(59791970_70440562)x1dn"
   "arr[GRCh37] 11q24.1q25(122534504_134934063)x1dn"
   "arr[GRCh37] 16p11.2(29652488_30198151)x1dn"
   "arr[GRCh37] 15q11.1q13.2(20071673_30371774)x4dn"
   "arr[GRCh37] 15q13.2q13.3(30507461_32514341)x3dn"
   "arr[GRCh37] 16p11.2(29412503_30198151)x1dn"
   "arr[GRCh37] 3p14.1p14.2(63363166_65347423)x1 dn"
   "arr[GRCh37] 9q33.1(119578333_119613865)x1 dn"
   "arr[GRCh37] 16q11.2q12.1(46450037_50980935)x1dn"
   "arr[GRCh37] 9p13.1p24.3(46587_39179289)x3 dn"
   "arr[GRCh37] Xp22.31(6702509_7745286)x1 dn"
   "arr[GRCh37] 22q11.21q11.23(21797812_24643609)x1dn"
   "arr[GRCh37] 18q12.3(42,425,030_42,574,827)x1 dn"
   "arr[GRCh37] 3q27.1(183632423_183642794)x1dn"
   "arr[GRCh37] 5q14.1(78421780_78517143)x1mat"
   "arr[GRCh37] 5q14.1(78522895_78685117)x3mat"
   "arr[GRCh37] 5q14.1(80629372_80748607)x3mat"
   "arr[GRCh37] 8q21.11(77566481_77632017)x1dn"
   "arr[GRCh37] 4q22.3(98479139_98765347)x1mat"
   "arr[GRCh37] 7q11.23(75059331_75421326)x1dn"
   "arr[GRCh37] 22q11.21(18,844,632_21,463,730)x1dn"
   "arr[GRCh37] 11p15.1(20699857_20981894)x1dn"
   "arr[GRCh37] 15q11.1q13.2(20071673_30737344)x4dn"
   "arr[GRCh37] 15q13.2q13.3(30936285_32620127)x3dn"
   "arr[GRCh37] 10q22.3(81319458_81984357)x1dn"
   "arr[GRCh37] 4p16.3(49,450_2,499,117)x3dn"
   "arr[GRCh37] 18p11.32p11.31(13,034_5,930,480)x1dn"
   "arr[GRCh37] 15q11.1q13.1(20,071,673_28,323,770)x3dn"
   "arr[GRCh37] 10q25.2(113,855,270_114,565,739)x3dn"
   "arr[GRCh37] 22q13.2 (41,791,536_41,895,409)x1dn"
   "arr[GRCh37] 9p24.2(3058362_3237890)x1dn"
   "arr[GRCh37] 16p11.2(29614976_30198151)x1dn"
   "arr[GRCh37] 13q31.2q31.3(89610815_94506389)x1dn"
   "arr[GRCh37] 7q11.23(75163169_76244699)x1dn"
   "arr[GRCh37] 1q21.1q21.2(145063356_148937908)x1dn" ])
