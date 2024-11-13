(ns genegraph.api.graphql.schema.resource
  "Definitions for model of RDFResource objects"
  (:require [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage :as storage]
            [genegraph.api.hybrid-resource :as hr]
            [clojure.string :as s]))


(defn subject-of [_ args value]
  (concat (rdf/ld-> value [[:sepio/has-subject :<]])
          (rdf/ld-> value [[:sepio/has-object :<]])))

(defn- description [_ _ value]
  (or (rdf/ld1-> value [:dc/description])
      (rdf/ld1-> value [:iao/definition])))

(def type-query (rdf/create-query "select ?type where {?resource a /  :rdfs/subClassOf * ?type}"))

(defn rdf-types [args value]
  (if (:inferred args)
    (type-query {:resource value})
    (rdf/ld-> value [:rdf/type])))

(defn hybrid-types [{:keys [tdb object-db] :as opts} args value]
  (let [rdf-types (mapv
                   #(hr/resource->hybrid-resource % opts)
                   (rdf-types args value))]
    (if (seq rdf-types) 
      rdf-types ; type in Jena
      [(hr/resource->hybrid-resource
        (rdf/resource (:type value) tdb)
        opts)])))

(def resource-interface
  {:name :Resource
   :graphql-type :interface
   :description "An RDF Resource; type common to all addressable entities in Genegraph"
   :fields {:iri {:type 'String
                  :description "The IRI for this resource."
                  :resolve (fn [_ _ value]
                             (if (map? value)
                               (:iri value)
                               (str value)))}
            :curie {:type 'String
                    :description "The CURIE internal to Genegraph for this resource."
                    :resolve (fn [_ _ value]
                               (if (map? value)
                                 (rdf/curie (:iri value))
                                 (rdf/curie value)))}
            :label {:type 'String
                    :description "The label for this resouce."
                    :path [:skos/prefLabel
                           :rdfs/label
                           :foaf/name
                           :dc/title]}
            :type {:type '(list :Resource)
                   :description "The types for this resource."
                   :args {:inferred {:type 'Boolean}}
                   :resolve hybrid-types}
            :description {:type 'String
                          :description "Textual description of this resource"
                          ;; :path [:dc/description]
                          :resolve description}
            ;; :source {:type :BibliographicResource
            ;;          :description "A related resource from which the described resource is derived."
            ;;          :path [:dc/source]}
            ;; :used_as_evidence_by {:type :Statement
            ;;                       :description "Statements that use this resource as evidence"
            ;;                       :path [[:sepio/has-evidence :<]]}
            ;; :in_scheme {:type '(list :ValueSet)
            ;;             :description "Relates a resource (for example a concept) to a concept scheme in which it is included."
            ;;             :path [:skos/is-in-scheme]}
            ;; :subject_of {:type '(list :Statement)
            ;;              :description "Assertions (or propositions) that have this resource as a subject (or object)."
            ;;              :resolve subject-of}
            :is_about {:type '(list :Resource)
                       :description "A (currently) primitive relation that relates an information artifact to an entity. (IAO:0000136)"
                       :path [:iao/is-about]}}})

(def generic-resource
  {:name :GenericResource
   :graphql-type :object
   :description "A generic implementation of an RDF Resource, suitable when no other type can be found or is appropriate"
   :implements [:Resource]})

(def resource-query
  {:name :resource
   :graphql-type :query
   :description "Find a resource by IRI or CURIE"
   :args {:iri {:type 'String}}
   :type :Resource
   :resolve (fn [context args _]
              (hr/resource->hybrid-resource (rdf/resource (:iri args) (:tdb context))
                                            (select-keys context [:object-db :tdb])))})


