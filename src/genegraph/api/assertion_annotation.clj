(ns genegraph.api.assertion-annotation
  (:require [io.pedestal.interceptor :as interceptor]
            [genegraph.framework.event :as event]
            [genegraph.framework.storage.rdf :as rdf]))

(defn annotation->model [ann]
  (let [iri (:iri ann)
        contrib-iri (rdf/resource (str iri "contrib"))]
    (rdf/statements->model
     (concat
      [[iri :rdf/type :cg/AssertionAnnotation]
       [iri :cg/contributions contrib-iri]
       [iri :cg/subject (rdf/resource (:subject ann))]
       [iri :dc/description (:description ann "")]
       [iri :cg/classification (rdf/resource (:classification ann))]
       [contrib-iri :cg/agent (rdf/resource (:agent ann))]
       [contrib-iri :cg/role :cg/Author]
       [contrib-iri :cg/date (:date ann)]]
      (map (fn [evidence]
             [iri :cg/evidence evidence])
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
