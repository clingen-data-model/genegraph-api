(ns genegraph.api.frontend.pages.home
  (:require [genegraph.api.frontend.render :as r]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.http.response :as response]))

(defn home [ctx]
  (response/respond-with
   ctx
   200
   {"Content-Type" "text/html"}
   (str (r/render-page [:h1 {:class "text-3xl font-bold underline"} "Starting now"]))))

(def home-interceptor
  (interceptor/interceptor
   {:name :home-interceptor
    :enter (fn [e] (home e))}))

(def home-processor
  {:name :graphql-ready
   :type :processor
   :interceptors [home-interceptor]})

