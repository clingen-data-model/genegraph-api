(ns genegraph.api.spec.ga4gh
  (:require [genegraph.framework.id :as id]))

(id/register-type {:type :ga4gh/SequenceLocation
                   :defining-attributes
                   [:ga4gh/sequenceReference :ga4gh/start :ga4gh/end]})

(id/register-type {:type :ga4gh/CopyNumberChange
                   :defining-attributes
                   [:ga4gh/location :ga4gh/copyChange]})

(id/register-type {:type :cg/VariantPathogenicityProposition
                   :defining-attributes
                   [:cg/variant :cg/condition]})

(comment

  (id/iri {:ga4gh/sequenceReference
           "https://identifiers.org/refseq:NC_000001.10"
           :ga4gh/start [100100 100110]
           :ga4gh/end [200102 200110]
           :type :ga4gh/SequenceLocation})

  (id/iri {:type :ga4gh/CopyNumberChange
           :ga4gh/copyChange :efo/copy-number-loss
           :ga4gh/location
           {:ga4gh/sequenceReference
            "https://identifiers.org/refseq:NC_000001.10"
            :ga4gh/start [100100 100110]
            :ga4gh/end [200102 200110]
            :type :ga4gh/SequenceLocation}})
  
  )
