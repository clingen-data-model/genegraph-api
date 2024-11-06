(ns genegraph.api.hybrid-resource
  "Extensions and functions to support a hybrid database architecture"
  (:require [genegraph.framework.storage.rdf.types :as rdf-types]
            [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage :as storage])
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

  rdf-types/ThreadableData
  (ld-> [this ks] (rdf-types/ld-> (rdf-types/resource this) ks))
  (ld1-> [this ks] (rdf-types/ld1-> (rdf-types/resource this) ks))
  )

(defn resource->hybrid-resource [r db]
  (assoc (storage/read db [:objects (str r)])
         ::rdf/resource r))

(defn path->
  "Similar to ld-> function, but maps output to a hybrid resource
   populated by the data in (::db this)"
  [this ks db]
  (map #(resource->hybrid-resource % db) (rdf/ld-> this ks)))

(defn path1->
  "Similar to ld, ld1 functions, but maps output to a hybrid resource
   populated by the data in (::db this)"
  [this ks db]
  (first (path-> this ks db)))


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
