(ns shade.routes.home
  (:require
   [shade.layout :as layout]
   [shade.db.core :as db]
   [clojure.java.io :as io]
   [shade.middleware :as middleware]
   [buddy.auth :as auth]
   [buddy.hashers :as hashers]
   [ring.util.response :refer [redirect content-type]]
   [ring.util.http-response :as response]))

(defn home-page [request]
  (layout/render request "home.html" {:docs (-> "docs/docs.md" io/resource slurp)}))

(defn about-page [request]
  (layout/render request "about.html"))

(defn login-page [request]
  (layout/render request "login.html"))

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
   ["/login" {:get login-page
              :post login-authenticate}]
   ["/logout" {:get logout}]])
