(ns genegraph.api.workos
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.interceptor :as interceptor]
            [ring.util.response :as response]
            [cheshire.core :as json]
            [hato.client :as hc]
            [buddy.sign.jwt :as jwt]
            [buddy.core.keys :as keys]))

;; Configuration
(def workos-config
  {:client-id (System/getenv "WORKOS_CLIENT_ID")
   :api-key (System/getenv "WORKOS_API_KEY")
   :redirect-uri (or (System/getenv "WORKOS_REDIRECT_URI") 
                     "http://localhost:8888/auth/callback")
   :base-url "https://api.workos.com"})

;; Helper functions
(defn build-authorization-url
  [provider state]
  (str (:base-url workos-config) "/sso/authorize"
       "?client_id=" (:client-id workos-config)
       "&redirect_uri=" (java.net.URLEncoder/encode (:redirect-uri workos-config) "UTF-8")
       "&response_type=code"
       "&provider=" provider
       (when state (str "&state=" state))))

(defn exchange-code-for-profile
  [code]
  (try
    (let [response (hc/post
                    (str (:base-url workos-config) "/sso/token")
                    {:headers {"Authorization" (str "Bearer " (:api-key workos-config))
                               "Content-Type" "application/json"}
                     :body (json/generate-string 
                            {:client_id (:client-id workos-config)
                             :client_secret (:api-key workos-config)
                             :code code
                             :grant_type "authorization_code"})
                     :as :json})]
      (:body response))
    (catch Exception e
      (println "Error exchanging code:" (.getMessage e))
      nil)))

(defn get-user-profile
  [access-token]
  (try
    (let [response (hc/get
                     (str (:base-url workos-config) "/user_management/users/me")
                     {:headers {"Authorization" (str "Bearer " access-token)}
                      :as :json})]
      (:body response))
    (catch Exception e
      (println "Error fetching user profile:" (.getMessage e))
      nil)))

;; Session helpers
(defn create-session-token
  [user-data secret]
  (jwt/sign {:user-id (:id user-data)
             :email (:email user-data)
             :exp (.getTime (java.util.Date. (+ (System/currentTimeMillis) 
                                                  (* 24 60 60 1000))))} ; 24 hours
            secret))

;; Handlers

;; when I run this code workos gives a connection_strategy_invalid error
(defn login-handler
  "Initiates SSO login flow"
  [{:keys [query-params] :as request}]
  (tap> query-params)
  (let [provider (get query-params :provider #_"GoogleOAuth" "MicrosoftOAuth")
        state (str (java.util.UUID/randomUUID))
        auth-url (build-authorization-url provider state)]
    (-> (response/redirect auth-url)
        (assoc-in [:session :oauth-state] state))))



;; To debug the WorkOS `connection_strategy_invalid` error, I need to see what's in `query-params` and how you're building the authorization URL. The error suggests that the connection strategy you're passing to WorkOS isn't valid.

;; Can you share:

;; 1. What `(tap> query-params)` shows?
;; 2. Your `build-authorization-url` function implementation?

;; Common causes:
;; - The `provider` parameter needs to match WorkOS's expected connection strategy format (e.g., `"GoogleOAuth"` not `"Google"`)
;; - WorkOS expects specific strategy values like `"GoogleOAuth"`, `"MicrosoftOAuth"`, `"GitHubOAuth"`, etc.
;; - You might need to use a connection ID instead of a provider name

;; WorkOS typically expects either:
;; - A `connection` parameter with a connection ID, or  
;; - A `provider` parameter with a valid OAuth provider identifier

;; Try checking the exact provider value being sent and compare it against [WorkOS's documentation](https://workos.com/docs/sso/guide/initiate-sso).

(defn callback-handler
  "Handles OAuth callback from WorkOS"
  [{:keys [query-params session] :as request}]
  (tap> query-params)
  (let [code (get query-params :code)
        state (get query-params :state)
        stored-state (get session :oauth-state)]
    
    (cond
      (not code)
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:error "Missing authorization code"})}
      
      (and state stored-state (not= state stored-state))
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:error "Invalid state parameter"})}
      
      :else
      (if-let [token-response (exchange-code-for-profile code)]
        (let [profile (:profile token-response)
              session-token (create-session-token 
                             profile 
                             (or (System/getenv "JWT_SECRET") "change-me"))]
          (-> (response/redirect "/dashboard")
              (assoc-in [:session :user] profile)
              (assoc-in [:session :token] session-token)
              (update :session dissoc :oauth-state)))
        {:status 500
         :headers {"Content-Type" "application/json"}
         :body (json/generate-string {:error "Failed to authenticate"})}))))

(defn logout-handler
  "Logs out the user"
  [request]
  (-> (response/redirect "/")
      (assoc :session nil)))

(defn me-handler
  "Returns current user information"
  [{:keys [session] :as request}]
  (if-let [user (:user session)]
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (json/generate-string user)}
    {:status 401
     :headers {"Content-Type" "application/json"}
     :body (json/generate-string {:error "Not authenticated"})}))

;; Interceptors
(def require-auth
  "Interceptor to ensure user is authenticated"
  (interceptor/interceptor
    {:name ::require-auth
     :enter (fn [context]
              (if (get-in context [:request :session :user])
                context
                (assoc context :response
                       {:status 401
                        :headers {"Content-Type" "application/json"}
                        :body (json/generate-string {:error "Authentication required"})})))}))

;; Routes
(def routes
  #{["/auth/login" :get login-handler :route-name ::login]
    ["/auth/callback" :get callback-handler :route-name ::callback]
    ["/auth/logout" :post logout-handler :route-name ::logout]
    ["/auth/me" :get [require-auth me-handler] :route-name ::me]})

;; Service map additions
(defn add-auth-routes
  [service-map]
  (update service-map ::http/routes
          #(into % routes)))

(comment
  ;; Usage example:
  ;; In your service definition:
  (def service
    (-> {::http/routes routes
         ::http/type :jetty
         ::http/port 8080}
        (http/default-interceptors)
        (add-auth-routes)))
  )

;; Key features:

;; 1. **Login Handler** - Initiates SSO flow with WorkOS
;; 2. **Callback Handler** - Processes OAuth callback, exchanges code for tokens
;; 3. **Logout Handler** - Clears session
;; 4. **Me Handler** - Returns current user info
;; 5. **Auth Interceptor** - Protects routes requiring authentication
;; 6. **Session Management** - JWT token creation and session handling
;; 7. **State Validation** - CSRF protection via state parameter

;; Add dependencies to your `deps.edn`:
;; ```clojure
;; {io.pedestal/pedestal.service {:mvn/version "0.6.3"}
;;  io.pedestal/pedestal.jetty {:mvn/version "0.6.3"}
;;  clj-http/clj-http {:mvn/version "3.12.3"}
;;  cheshire/cheshire {:mvn/version "5.12.0"}
;;  buddy/buddy-sign {:mvn/version "3.5.351"}
;;  buddy/buddy-core {:mvn/version "1.11.423"}}
;; ```
