(ns genegraph.api.auth
  (:require [genegraph.framework.storage.rdf :as rdf]
            [genegraph.framework.storage :as storage]
            [io.pedestal.interceptor :as interceptor])
  (:import [com.google.firebase FirebaseApp FirebaseOptions FirebaseOptions$Builder]
           [com.google.firebase.auth FirebaseAuth FirebaseToken]
           [com.google.auth.oauth2 GoogleCredentials]))

;; note
;; first request comes through as unauthenticated
;; may need to deal with that case on frontend, especially
;; when user is definitely authenticated

;; also will probably want to consider caching some of this stuff
;; but performance seems acceptable for now

(defonce firebase-app
  (-> (FirebaseOptions/builder)
      (.setCredentials (GoogleCredentials/getApplicationDefault))
      (.setProjectId "som-clingen-projects")
      .build
      FirebaseApp/initializeApp))

(defn authenticated? [e]
  (not (get #{:unauthenticated-request :failed-verification nil}
            (::user e))))

(defn add-user [e]
  (assoc
   e
   ::user
   (if-let [t (get-in e [:request :headers "authorization"])]
     (try
       (.verifyIdToken (FirebaseAuth/getInstance) t)
       (catch Exception e :failed-verification))
     :unauthenticated-request)))

(defn add-email [e]
  (if (authenticated? e)
    (assoc e ::email (.getEmail (::user e))
             ::uid (.getUid (::user e)))
    e))

(defn add-groups [e]
  (if (authenticated? e)
    (let [tdb (get-in e [::storage/storage :api-tdb])
          in-clingen-query (rdf/create-query "
select ?x where
{ ?x :schema/email ?email ;
     :dc/source :cg/GPM . }")]
      (rdf/tx tdb
        (if (seq (in-clingen-query tdb {:email (::email e)}))
          (assoc e ::groups #{:cg/ClinGen})
          e)))
    e))

(defn auth-interceptor-fn [e]
  (-> e
      add-user
      add-email
      add-groups))

(def auth-interceptor
  (interceptor/interceptor
   {:name ::auth-interceptor
    :enter (fn [e] (auth-interceptor-fn e))}))

;; returns exception when token expires
#_(-> (.verifyIdToken (FirebaseAuth/getInstance)
                    "eyJhbGciOiJSUzI1NiIsImtpZCI6ImE1YTAwNWU5N2NiMWU0MjczMDBlNTJjZGQ1MGYwYjM2Y2Q4MDYyOWIiLCJ0eXAiOiJKV1QifQ.eyJuYW1lIjoiVHJpc3RhbiBOZWxzb24iLCJwaWN0dXJlIjoiaHR0cHM6Ly9saDMuZ29vZ2xldXNlcmNvbnRlbnQuY29tL2EvQUNnOG9jTFIzU2QybVp3LWwtS1J0Z3N6WVdjYzdCS1JGeHYzTXhhMzh1QW0xUmp1MmVUNzhBPXM5Ni1jIiwiaXNzIjoiaHR0cHM6Ly9zZWN1cmV0b2tlbi5nb29nbGUuY29tL3NvbS1jbGluZ2VuLXByb2plY3RzIiwiYXVkIjoic29tLWNsaW5nZW4tcHJvamVjdHMiLCJhdXRoX3RpbWUiOjE3NTc2NzgwNDUsInVzZXJfaWQiOiIzd1dzZkFVRVNBWndmcDBZaEMxYUR6d095UHgxIiwic3ViIjoiM3dXc2ZBVUVTQVp3ZnAwWWhDMWFEendPeVB4MSIsImlhdCI6MTc1OTkyNzYzOCwiZXhwIjoxNzU5OTMxMjM4LCJlbWFpbCI6InRobmVsc29uQGdlaXNpbmdlci5lZHUiLCJlbWFpbF92ZXJpZmllZCI6dHJ1ZSwiZmlyZWJhc2UiOnsiaWRlbnRpdGllcyI6eyJnb29nbGUuY29tIjpbIjEwNDUyMTI5NzgxMTI0MTIwMDQ3MCJdLCJlbWFpbCI6WyJ0aG5lbHNvbkBnZWlzaW5nZXIuZWR1Il19LCJzaWduX2luX3Byb3ZpZGVyIjoiZ29vZ2xlLmNvbSJ9fQ.QNxIpiCu6sevPDPlaeF1NckXnEvGaEZPH3BBtnsXasAm5Tbguyzhnz2pgZ_w876za48TVMD29MUFQknA_ASt6aTGVeSPeW78AiMKurSf_UOeoi7f0wkI5mX3llsecTdexLXPf0ig_c4GvwpXwaedc8u3n1F9eaqCmw282yG_UxA-EusIdvUqMRdP5arcLZ_MiMMJ0XOxo7ubwfWj7COQg9RvvRwE6cHPF5KJtunMoUMdCeN-Qz-Y08vj8oOBeXTuxn5BpuE1OurlxhrLzJXTBlGjB6HoS3TbaIZl0iDVttrEjLB0yKpYY7bxexpTIHVh5SS-7K2H56bumcZkIgYTeQ")

    .getUid)
