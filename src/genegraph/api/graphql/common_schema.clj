(ns genegraph.api.graphql.common-schema
  "Convert the common schema into the format that can be ingested and handled by the schema builder. Should be able to combine this with the schema produced by schema builder."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:import [java.io PushbackReader]))


(def common-schema
  (with-open [r (-> "/Users/tristan/code/genegraph-schema/resources/schema.edn"
                    io/reader
                    PushbackReader.)]
    (edn/read r)))

(def properties
  (reduce (fn [m p] (assoc m (:id p) p))
          {}
          (filter #(= :rdf/Property (:type %))  common-schema)))

(->> common-schema
     (filter #(and (= :rdf/Property (:type %)) (not (:range %))))

     )

(->> common-schema
     (map :range)
     set)

#{nil :cg/Agent :cg/Proposition :Boolean :Integer :Number :cg/Family :skos/Concept :cg/EvidenceItem :rdfs/Class :cg/Cohort :cg/Resource :cg/EvidenceLine :cg/VariationDescriptor :cg/ProbandStudyResult :String :cg/Contribution}


(do
  (defn kw->gql-type [k]
    (keyword (name k)))

  (def primitive-types
    {:String 'String
     :Float 'Float
     :Int 'Int
     :Boolean 'Boolean
     :ID 'ID})

  (defn range->gql-type [range]
    (kw->gql-type range))
  
  (defn property->graphql-field [p arity]
    (let [field (get properties p)
          base-type (range->gql-type (:range field))]
      [p {:description (:description field)
          :type (if (= :oneOf arity)
                  base-type
                  '(list base-type))}]))
  
  (defn fields-for [c]
    (into {}
          (concat (map #(property->graphql-field % :oneOf) (:oneOf c))
                  (map #(property->graphql-field % :oneOf) (:zeroOrOneOf c))
                  (map #(property->graphql-field % :manyOf) (:zeroOrMoreOf c))
                  (map #(property->graphql-field % :manyOf) (:oneOrMoreOf c)))))
  
  (defn compose-objects [] 
    (->> common-schema
         (filter #(= :rdfs/Class (:type %)))
         (reduce (fn [m c]
                   (assoc m
                          (kw->gql-type (:id c))
                          (-> (select-keys c [:description])
                              (assoc :implements [:Resource])
                              (assoc :fields (fields-for c)))))
                 {})))
  (tap> (compose-objects)))





