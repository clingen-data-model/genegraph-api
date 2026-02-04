(ns genegraph.api.assertion-annotation
  (:require [io.pedestal.interceptor :as interceptor]
            [genegraph.framework.event :as event]
            [genegraph.framework.storage.rdf :as rdf]
            [clojure.string :as str]))

(defn update-evidence-ref [iri]
  (rdf/resource
   (str/replace iri
                "http://dataexchange.clinicalgenome.org/dci/"
                "https://genegraph.clinicalgenome.org/r/")))

(defn update-term [iri]
  (if (string? iri)
    (rdf/resource
     (str/replace iri
                  "http://dataexchange.clinicalgenome.org/terms/"
                  "https://genegraph.clinicalgenome.org/terms/"))
    iri))

(defn annotation->model [ann]
  (let [iri (:iri ann)
        contrib-iri (rdf/resource (str iri "contrib"))]
    (rdf/statements->model
     (concat
      [[iri :rdf/type :cg/AssertionAnnotation]
       [iri :cg/contributions contrib-iri]
       [iri :cg/subject (rdf/resource (:subject ann))]
       [iri :dc/description (:description ann "")]
       [iri :cg/classification (update-term (:classification ann :cg/NoAssessment))]
       [contrib-iri :cg/agent (rdf/resource (:agent ann))]
       [contrib-iri :cg/role :cg/Author]
       [contrib-iri :cg/date (:date ann)]]
      (map (fn [evidence]
             [iri :cg/evidence (update-evidence-ref evidence)])
           (:evidence ann))))))

(defn process-annotation-fn [e]
  (tap> (::event/data e))
  (event/store e
               :api-tdb
               (get-in e [::event/data :iri])
               (annotation->model (::event/data e))))

(def process-annotation
  (interceptor/interceptor
   {:name ::process-curation
    :enter (fn [e] (process-annotation-fn e))}))
