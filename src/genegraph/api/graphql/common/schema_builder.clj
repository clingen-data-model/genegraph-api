(ns genegraph.api.graphql.common.schema-builder
  (:require [genegraph.framework.storage.rdf :as rdf]
            [genegraph.api.hybrid-resource :as hr]
            [com.walmartlabs.lacinia :as lacinia]
            [com.walmartlabs.lacinia.schema :as schema]
            [com.walmartlabs.lacinia.util :as util]))


(defn- is-list? [field]
  (and (seq? (:type field))
       (= 'list (first (:type field)))))

(defn- is-object? [field]
  (if (is-list? field)
    (keyword? (second (:type field)))
    (keyword? (:type field))))

(defn is-primitive? [field]
  (not (is-object? field)))

;; This has always been a performance concern. Will implement as-is for now
;; but it's probably worth optimizing the conversion of a Jena type into a GraphQL
;; type

(def type-query (rdf/create-query "select ?type where {?resource a / :rdfs/subClassOf * ?type}"))

(defn rdf-type [resource schema]
  (if resource
    (let [resource-types (->> (type-query resource {:resource resource})
                              (map rdf/->kw)
                              (remove nil?)
                              (into #{}))]
      (if-let [type-mapping (->> (:type-mappings schema)
                                 (filter #(resource-types (first %)))
                                 first)]
        (second type-mapping)
        (:default-type-mapping schema)))
    (:default-type-mapping schema)))

(defn edn-type [resource schema]
  (if (and (map? resource) (:type resource))
    (-> (filter #(= (:type resource) (first %))
                (:type-mappings schema))
        first
        second)
    false))

(defn resolve-type [resource schema]
  (or (edn-type resource schema)
      (rdf-type resource schema)))

;; TODO 11/12 Design replacement for path
;; leverage attr1-> function in hybrid-resource
;; 

(defn- construct-resolver-from-path [field]
  (if (:path field)
    (assoc field
           :resolve
           (fn [context _ value]
             ((if (is-list? field) hr/path-> hr/path1->)
              value
              (-> context
                  (select-keys [:tdb :object-db])
                  (assoc :primitive (is-primitive? field)))
              (:path field))))
    field))

(defn- attach-type-to-resolver-result [field schema] 
  (if-let [resolver-fn (:resolve field)]
    (if (is-object? field)
      (if (is-list? field)
        (assoc field
               :resolve
               (fn [context args value]
                 (map #(schema/tag-with-type % (resolve-type % schema))
                      (resolver-fn context args value))))
        (assoc field
               :resolve
               (fn [context args value]
                 (let [res (resolver-fn context args value)]
                   (schema/tag-with-type res (resolve-type res schema))))))
      field)
    (if (is-object? field)
      (if (is-list? field)
        (assoc field
               :resolve
               (fn [context args value]
                 (map #(schema/tag-with-type % (resolve-type % schema))
                      (get value (:name field)))))
        (assoc field
               :resolve
               (fn [context args value]
                 (let [res (get value (:name field))]
                   (schema/tag-with-type res (resolve-type res schema))))))
      field)))

(defn- update-fields [entity schema]
  (let [fields (reduce (fn [new-fields [field-name field]]
                         (assoc new-fields
                                field-name
                                (if (:skip-type-resolution entity)
                                  (construct-resolver-from-path field)
                                  (attach-type-to-resolver-result
                                   (construct-resolver-from-path field) schema))))
                       {}
                       (:fields entity))]
    (assoc entity :fields fields)))

(defn- compose-object [entity schema]
  (let [interfaces (-> schema :interfaces (select-keys (:implements entity)) vals)
        interface-defined-fields (reduce merge (map :fields interfaces))
        updated-fields (update-fields entity schema)]
    (assoc (select-keys entity 
                        [:description :fields :implements])
           :fields
           (merge interface-defined-fields (:fields updated-fields)))))

(defn- compose-interface [entity schema]
  (select-keys (update-fields entity schema)
               [:description :fields]))

(defn- compose-query [entity schema]
  (let [query (select-keys entity [:type :args :resolve :description])]
    (if (:skip-type-resolution entity)
      query
      (attach-type-to-resolver-result query schema))))

(defn- compose-enum [entity]
  (select-keys entity [:description :values]))

(defn- compose-input-object [entity]
  (select-keys entity [:description :fields]))

(defn- add-entity-to-schema [schema entity]
  (case (:graphql-type entity)
    :interface (assoc-in schema
                         [:interfaces (:name entity)]
                         (compose-interface entity schema))
    :object (assoc-in schema
                      [:objects (:name entity)]
                      (compose-object entity schema))
    :query (assoc-in schema
                     [:objects :Query :fields (:name entity)]
                     (compose-query entity schema))
    :mutation (assoc-in schema
                        [:objects :Mutation :fields (:name entity)]
                        (compose-query entity schema))
    :enum (assoc-in schema
                    [:enums (:name entity)]
                    (compose-enum entity))
    :input-object (assoc-in schema
                            [:input-objects (:name entity)]
                            (compose-input-object entity))
    (merge schema entity)))

(defn schema-description [entities]
  (reduce add-entity-to-schema {} entities))

(defn schema
  ([entities]
   (schema/compile (schema-description entities)))
  ([entities options]
   (schema/compile (schema-description entities) options)))

(defn print-schema [schema]
  (binding [schema/*verbose-schema-printing* true]
    (clojure.pprint/pprint schema)))

(defn query 
  "Function not used except for evaluating queries in the REPL
  may consider moving into test namespace in future"
  ([schema query-str]
   (rdf/tx (lacinia/execute schema query-str nil nil)))
  ([schema query-str variables]
   (rdf/tx (lacinia/execute schema query-str variables nil))))
