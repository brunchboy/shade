(ns shade.routes.login
  "Supports login and logout."
  (:require
   [buddy.hashers :as hashers]
   [ring.util.response :refer [redirect]]
   [shade.db.core :as db]
   [shade.layout :as layout]))

(defn login-page [request]
  (layout/render request "login.html" (select-keys request [:active?])))

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
            updated-session (assoc session :identity
                                   (into {} (select-keys user [:id :name :email :admin :last_login])))]
        (db/update-user-login-timestamp! user)
        (-> (redirect next-url)
            (assoc :session updated-session)))
      (layout/render request "login.html" {:error "Unrecognized Email or Password."}))))


(defn logout [_request]
  (-> (redirect "/login")
      (assoc :session {})))
