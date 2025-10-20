(ns genegraph.api.gpm
  (:require [genegraph.framework.event :as event]
            [genegraph.api.rdf-conversion :as rdfc]
            [io.pedestal.interceptor :as interceptor]
            [clojure.set :as set]))

(def agent-base "https://genegraph.clinicalgenome.org/agent/")

(def gpm-keys->gg-keys
  {:id :iri
   :email :schema/email
   :first_name :schema/givenName
   :last_name :schema/familyName})

(defn gpm-person->gg-person [data]
  (-> (get-in data [:data :person])
      (select-keys (keys gpm-keys->gg-keys))
      (set/rename-keys gpm-keys->gg-keys)
      (update :iri #(str agent-base %))
      (assoc :type :schema/Person
             :dc/source :cg/GPM)))

(defn valid-event? [e]
  (get #{"created" "person_created" "person_updated" "updated"}
       (get-in e [::event/data :event_type])))

(defn import-gdm-person-fn [e]
  (if (valid-event? e)
    (let [p (gpm-person->gg-person (::event/data e))]
      (event/store e
                   :api-tdb
                   (:iri p)
                   (rdfc/map->model p)))
    e))

(def import-gdm-person
  (interceptor/interceptor
   {:name ::import-gdm-person
    :enter (fn [e] (import-gdm-person-fn e))}))
