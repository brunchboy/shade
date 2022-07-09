(ns shade.routes.home
  (:require
   [shade.layout :as layout]
   [shade.db.core :as db]
   [shade.routes.websocket :as ws]
   [clojure.java.io :as io]
   [clojure.string :as str]
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

(defn profile-page [request]
  (layout/render request "profile.html"
                 {:user (db/get-user {:id (get-in request [:session :identity :id])})}))

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
            updated-session (assoc session :identity
                                   (into {} (select-keys user [:id :name :email :admin :last_login])))]
        (db/update-user-login-timestamp! user)
        (-> (redirect next-url)
            (assoc :session updated-session)))
      (layout/render request "login.html" {:error "Unrecognized Email or Password."}))))

(defn profile-update
  "Validate name is present, email is present and unique, then update password if present.
  On successful update, also update user information in the session and redirect to the
  home page. On failed validation, renders the profile page."
  [{:keys [form-params] :as request}]
  (let [name     (form-params "name")
        email    (form-params "email")
        password (form-params "password")
        new-pw   (form-params "new_password")
        session  (:session request)
        user     (db/get-user {:id (get-in session [:identity :id])})
        errors   (cond-> []
                   (str/blank? name)
                   (conj "Name cannot be empty.")

                   (str/blank? email)
                   (conj "Email cannot be empty.")

                   (and (not (str/blank? email))
                        (let [match (db/get-user-by-email {:email email})]
                          (and (some? match)
                               (not= (:id match) (get-in session [:identity :id])))))
                   (conj "That email is in use by another user.")

                   (and (str/blank? new-pw)
                        (not (str/blank? password)))
                   (conj "You cannot set your password to be empty.")

                   (and (not (str/blank? new-pw))
                        (not (hashers/check password (:pass user))))
                   (conj "Current password was not correct."))]
    (if (seq errors)
      (layout/render request "profile.html"
                     {:user         {:name  name
                                     :email email}
                      :new-password new-pw
                      :error        (str/join " " errors)})
      (do
        (db/update-user! (merge user
                                {:name  name
                                 :email email}
                                (when-not (str/blank? new-pw)
                                  {:pass (hashers/derive new-pw)})))
        (let [updated-session (update session :identity merge {:name  name
                                                               :email email})]
          (-> (redirect "/")
              (assoc :session updated-session)))))))

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
   ["/profile" {:get  profile-page
                :post profile-update}]
   ["/run/:id" {:get run-macro}]])
