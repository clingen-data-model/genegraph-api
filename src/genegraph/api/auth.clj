(ns genegraph.api.auth
  (:require [io.pedestal.interceptor :as interceptor]
            [charred.api :as charred]
            [buddy.sign.jwt :as jwt]
            [buddy.core.keys :as keys]
            [clojure.string :as str]))

;; public keys for jwt validation
(def dev-key
  (keys/str->public-key
   "-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA2uI4AnLWZzP0OL+Cv09B
ppvxUAXH82KtDsrUAUvEVlO7terqVmLH8bt9YHcBdlyJ5/lpl0U+HP4Zl/sLjGft
gOLSCC6/hqobuw0T4psgmZ01Q+TQ+YmJSiBcdj7DTQoyQYzwUN/+iqZ5UdzFV6QR
Xtu7+Y8ZHFgPLC9AfhekS1g8WkzgG7D/iMif9GMeDowCWglY7f5SKN/ylohcxMXg
d7PEMaTWpzTg4GBOJ0KDq1nVHZ/FdUFMZLqxWKb/xMxT8D09itOW3okxqISvNO1D
FyZCBN9rOhUI0o2r6P1RJoKTzUTcuzjcYYkoXqF9ZtxzbX0M4k/simxxSv2FZ1RL
8wIDAQAB
-----END PUBLIC KEY-----
"))

(def prod-key
  (keys/str->public-key
   "-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAyuCiiKQ7rqtAgmnJw2Wy
vHTaa8O+jxpf/Ocddr7r8mX1uXR30DUZi0Gt7TVPLTAaCowpYhYjto2Sg+I7pWnW
a2QuSHrN28PoJk+D+psumQi6wm0HKV1p3sPgXF84JSnUf+aDLj3FnPd1cVSRtVuN
1UWST8oYYapP6cltXNSs5KNzuepz0usY3vZuldgtEDn2pee0ZBrdpHEl6YuSDNZ/
6kctMdRG1HFugIFa3P2s6hbCF4OcLqiD53a9SEzbS9UyNy1oqvtd89pF/FXR0zUp
7JL7EoW6bJzuRYDNh94FEVr2D3Wy7B5Y3l5+7YdC79JQz4E83X1vzPnZPvMw/2tO
0QIDAQAB
-----END PUBLIC KEY-----
"))

(defn e->cookie [ctx]
  (get-in ctx [:request :headers "cookie"]))

(defn cookie->map [cookie]
  (->> (str/split cookie #";")
       (map #(str/split % #"="))
       (into {})))

(defn session->jwt [m]
  (-> (update-keys m str/trim)
      (get "__session")
      (jwt/unsign dev-key {:alg :rs256})))

(defn authenticate [e]
  (try
    (-> e e->cookie cookie->map session->jwt)
    (catch Exception e nil)))

(defn auth-interceptor-fn [e]
  (tap> (authenticate e))
  e)

(def auth-interceptor
  (interceptor/interceptor
   {:name ::auth-interceptor
    :enter (fn [e] (auth-interceptor-fn e))}))


