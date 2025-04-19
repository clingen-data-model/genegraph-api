(ns build
  "Build this thing."
  (:require [clojure.tools.build.api :as b]
            [clojure.java.process :as process]
            [clojure.data.json :as json]
            [clojure.java.io :as io]))

(def defaults
  "The defaults to configure a build."
  {:class-dir  "target/classes"
   :java-opts  ["-Dclojure.main.report=stderr"]
   :main       'genegraph.api
   :path       "target"
   :project    "deps.edn"
   :target-dir "target/classes"
   :uber-file  "target/app.jar"
   :exclude [#"META-INF/license.*"]})

(defn uber
  "Throw or make an uberjar from source."
  [_]
  (let [{:keys [paths] :as basis} (b/create-basis defaults)
        project                   (assoc defaults :basis basis)]
    (b/delete      project)
    (b/copy-dir    (assoc project :src-dirs paths))
    (b/compile-clj (assoc project
                          :src-dirs ["src"]
                          :ns-compile ['genegraph.api]))
    (b/uber        project)))

(def app-name "genegraph-api")

(defn image-tag []
  (str
   "us-east1-docker.pkg.dev/"
   "clingen-dx/"
   "genegraph-prod/"
   app-name
   ":v"
   (b/git-count-revs {})))

(defn kubernetes-deployment []
  {:apiVersion "apps/v1"
   :kind "Deployment"
   :metadata {:name app-name}
   :spec
   {:selector {:matchLabels {:app app-name}}
    :template
    {:metadata {:labels {:app app-name}}
     :spec
     {:containers
      [{:name app-name
        :image (image-tag)
        :env [{:name "GENEGRAPH_PLATFORM" :value "prod"}
              {:name "GENEGRAPH_FUNCTION" :value "graphql-endpoint"}]
        :ports [{:name "genegraph-port" :containerPort 8888}]
        :readinessProbe {:httpGet {:path "/ready" :port "genegraph-port"}}
        :volumeMounts [{:mountPath "/data" :name "local-volume"}]
        :resources {:requests {:memory "8Gi" :cpu "500m"}
                    :limits {:memory "8Gi"}}}]
      :tolerations [{:key "kubernetes.io/arch"
                     :operator "Equal"
                     :value "arm64"
                     :effect "NoSchedule"}]
      :volumes [{:name "local-volume" :emptyDir {:sizeLimit "100Gi"}}]
      :affinity {:nodeAffinity {:requiredDuringSchedulingIgnoredDuringExecution
                                {:nodeSelectorTerms
                                 [{:matchExpressions
                                   [{:key "kubernetes.io/arch"
                                     :operator "In"
                                     :values ["arm64"]}]}]}}}}}}})

(defn kubernetes-service []
  {:apiVersion "v1"
   :kind "Service"
   :metadata {:name app-name}
   :spec {:selector {:app app-name}
          :type "NodePort"
          :ports [{:protocol "TCP"
                   :port 80
                   :targetPort 8888}]}})

(def cert-name
  (str app-name "-cert"))

(def domain-name
  "genegraph-api.prod.clingen.app")

(defn cert []
  {:apiVersion "networking.gke.io/v1"
   :kind "ManagedCertificate"
   :metadata {:name cert-name}
   :spec {:domains [domain-name]}})

(defn ingress []
  {:apiVersion "networking.k8s.io/v1"
   :kind "Ingress"
   :metadata {:name (str app-name "-ingress")
              :annotations {"kubernetes.io/ingress.global-static-ip-name" "global-prod-genegraph-api-ip"
                            "networking.gke.io/managed-certificates" cert-name
                            "kubernetes.io/ingress.class" "gce"}}
   :spec {:rules [{:host domain-name
                   :http {:paths [{:path "/api"
                                   :pathType "ImplementationSpecific"
                                   :backend {:service {:name app-name
                                                       :port {:number 80}}}}
                                  {:path "/ws"
                                   :pathType "ImplementationSpecific"
                                   :backend {:service {:name app-name
                                                       :port {:number 80}}}}
                                  {:path "/*"
                                   :pathType "ImplementationSpecific"
                                   :backend {:service {:name "genegraph-frontend"
                                                       :port {:number 80}}}}]}}]}})


(defn docker-push
  [_]
  (process/exec
   {:err :stdout}
   "docker"
   "buildx"
   "build"
   "."
   "--platform"
   "linux/arm64"
   "-t"
   (image-tag)
   "--push"))

(defn kubernetes-apply
  [_]
  (let [p (process/start {:err :inherit} "kubectl" "apply" "-f" "-")
        captured (process/io-task #(slurp (process/stdout p)))
        exit (process/exit-ref p)]
    (with-open [w (io/writer (process/stdin p))]
      (run! #(json/write (%) w)
            [kubernetes-deployment
             kubernetes-service
             cert
             ingress]))
    (if (zero? @(process/exit-ref p))
      (println @captured)
      (println "non-zero exit code"))))

(defn deploy
  [_]
  (uber nil)
  (docker-push nil)
  (kubernetes-apply nil))
