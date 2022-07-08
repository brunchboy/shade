(ns shade.routes.home
  (:require
   [shade.layout :as layout]
   [shade.db.core :as db]
   [shade.routes.websocket :as ws]
   [clojure.java.io :as io]
   [shade.middleware :as middleware]
   [buddy.auth :as auth]
   [buddy.hashers :as hashers]
   [ring.util.response :refer [redirect content-type]]
   [ring.util.http-response :as response]
   [ring.util.json-response :refer [json-response]]))

(defn home-page [request]
  (println "home" (:session request))  ; TODO: Remove
  (layout/render request "home.html"
                 {:macros (db/list-macros-for-user {:user (get-in request [:session :identity :id])})}))

(defn about-page [request]
  (layout/render request "about.html"))

(defn login-page [request]
  (println "login" (:session request))  ; TODO: Remove
  (layout/render request "login.html"))

(defn run-macro [{:keys [path-params session]}]
  []
  (ws/run-macro (java.util.UUID/fromString (:id path-params)) (get-in session [:identity :id]))
  (json-response {:action "Macro run"}))

(defn login-authenticate
  "Check request username and password against authdata
  username and passwords.
  On successful authentication, set appropriate user
  into the session and redirect to the value of
  (:next (:query-params request)). On failed
  authentication, renders the login page."
  [request]
  (let [email    (get-in request [:form-params "email"])
        password (get-in request [:form-params "password"])
        session  (:session request)
        user     (db/get-user-by-email {:email email})]
    (if (and user
             (hashers/check password (:pass user)))
      (let [next-url        (get-in request [:query-params "next"] "/")
            updated-session (assoc session :identity (into {} (select-keys user [:id :name :email :admin])))]
        (-> (redirect next-url)
            (assoc :session updated-session)))
      (layout/render request "login.html" {:error "Unrecognized Email or Password."}))))

(defn logout [_request]
  (-> (redirect "/login")
      (assoc :session {})))

(defn home-routes []
  [""
   {:middleware [middleware/wrap-csrf
                 middleware/wrap-formats]}
   ["/" {:get home-page}]
   ["/about" {:get about-page}]
   ["/login" {:get  login-page
              :post login-authenticate}]
   ["/logout" {:get logout}]
   ["/run/:id" {:get run-macro}]])
