(ns genegraph.api.hybrid-resource
  "Extensions and functions to support a hybrid database architecture"
  (:require [genegraph.framework.storage.rdf.types :as rdf-types]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage :as storage]
            [io.pedestal.log :as log])
  (:import [org.apache.jena.rdf.model Model Resource ModelFactory
            ResourceFactory Statement Property]))

(extend-type clojure.lang.IPersistentMap
  rdf-types/AsResource
  (rdf-types/resource
    ([m] (or (::rdf/resource m)
             (rdf-types/resource (:iri m))))
    ([m model] (if-let [r (::rdf/resource m)]
                 (rdf-types/resource r model)
                 (rdf-types/resource (:iri m) model))))

  rdf-types/AsRDFNode
  (rdf-types/to-rdf-node [m]
    (rdf-types/resource m))

  rdf-types/AsModel
  (rdf-types/model [m]
    (rdf-types/model (::rdf/resource m)))

  rdf-types/ThreadableData
  (ld-> [this ks] (rdf-types/ld-> (rdf-types/resource this) ks))
  (ld1-> [this ks] (rdf-types/ld1-> (rdf-types/resource this) ks)))

(defn resource->hybrid-resource
  "Take Jena resource r, and reference to RocksDB 
   object store db, and return "
  [r {:keys [object-db tdb]}]
  (let [o (storage/read object-db [:objects (str r)])]
    (if (= ::storage/miss o)
      {::rdf/resource r
       :iri (str r)}
      (assoc o ::rdf/resource r))))

(defprotocol AsHybridResource
  (hybrid-resource [v opts]))

(extend-type Resource
  AsHybridResource
  (hybrid-resource [r {:keys [object-db tdb]}]
    (let [o (storage/read object-db [:objects (str r)])]
      (if (= ::storage/miss o)
        {::rdf/resource r
         :iri (str r)}
        (assoc o ::rdf/resource r)))))

(extend-type java.lang.String
  AsHybridResource
  (hybrid-resource [v {:keys [object-db tdb]}]
    (let [o (storage/read object-db [:objects v])
          r (rdf/resource v tdb)]
      (if (= ::storage/miss o)
        {::rdf/resource r
         :iri v}
        (assoc o ::rdf/resource r)))))

(extend-type clojure.lang.IPersistentMap
  AsHybridResource
  (hybrid-resource [{:keys [iri] :as o} {:keys [object-db tdb]}]
    (if iri
      (assoc o ::rdf/resource (rdf/resource iri tdb))
      (assoc o ::rdf/resource (rdf/resource (rdf/blank-node) tdb)))))

(defn resource? [r]
  (instance? Resource r))

(defn path1->
  "Return first attribute matching from hybrid resource. Map keys are
  prioritized. Return a hybrid value unless a primitive is explicitly
  specified."
  [hr opts attrs]
  (if-let [attr (or (some #(get hr %) attrs)
                    (rdf/ld1->* hr attrs))]
    (cond
      (:primitive opts) attr
      (string? attr) (resource->hybrid-resource (rdf/resource attr (:tdb opts))
                                                opts)
      (resource? attr) (resource->hybrid-resource attr
                                                  opts))))

(defn path->
  "Return first attribute matching from hybrid resource. Map keys are
  prioritized. Return a hybrid value unless a primitive is explicitly
  specified."
  [hr opts attrs]
  (let [values (remove nil?
                       (concat (map #(get hr %) attrs)
                               (rdf/ld->* hr attrs)))]
    (if (:primitive opts)
      values
      (set
       (map (fn [v]
              (if (string? v)
                (resource->hybrid-resource (rdf/resource v (:tdb opts)) opts)
                (resource->hybrid-resource v opts)))
            values)))))


(comment
  (let [model (ModelFactory/createDefaultModel)]
    [(rdf/resource {::rdf/resource (rdf/resource :cg/Benign)})
     (rdf/resource {:iri "http://dataexchange.clinicalgenome.org/terms/Benign"})
     (rdf/resource {::rdf/resource (rdf/resource :cg/Benign)} model)
     (rdf/resource {:iri "http://dataexchange.clinicalgenome.org/terms/Benign"} model)])

  (let [model (rdf/statements->model [[:cg/Benign :rdf/type :rdfs/Class]])]
    [(rdf/ld-> {::rdf/resource (rdf/resource :cg/Benign model)} [:rdf/type])
     (rdf/ld1-> {::rdf/resource (rdf/resource :cg/Benign model)} [:rdf/type])])

  (let []
    (rdf/tx tdb
      (->> (q tdb {:type :cg/GeneticConditionMechanismProposition})
           first
           )))

  (let [db @(get-in genegraph.user/api-test-app [:storage :object-db :instance])
        tdb @(get-in genegraph.user/api-test-app [:storage :api-tdb :instance])
        q (rdf/create-query "
select ?variant where 
{ ?assertion :cg/evidenceStrength :cg/DosageSufficientEvidence ;
  :cg/subject ?dosageProp .
  ?dosageProp :cg/mechanism :cg/Haploinsufficiency ;
  a :cg/GeneticConditionMechanismProposition ;
  :cg/feature ?feature .
  ?variant :cg/CompleteOverlap ?feature ;
  :ga4gh/copyChange :efo/copy-number-loss .
  ?pathProp :cg/variant ?variant .
  ?pathAssertion :cg/subject ?pathProp ;
  :cg/direction :cg/Refutes .
  FILTER NOT EXISTS { ?pathAssertion :cg/reviewStatus :cg/Flagged }
}
")]
    (rdf/tx tdb
      (let [r (path1-> (first (q tdb)) [:cg/CompleteOverlap] db)]
        (tap> r)
        (rdf/ld1-> r [:skos/prefLabel]))))
  )
