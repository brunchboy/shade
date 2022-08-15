(ns shade.routes.profile
  "Supports the profile page."
  (:require [buddy.hashers :as hashers]
            [clojure.string :as str]
            [ring.util.response :refer [redirect]]
            [shade.auth :as auth]
            [shade.db.core :as db]
            [shade.layout :as layout]))

(defn profile-page [request]
  (let [user-id (get-in request [:session :identity :id])
        rooms   (db/list-rooms-for-user {:user user-id})
        macros  (db/list-macros-for-user {:user user-id})]
    (layout/render request "profile.html"
                   (merge (select-keys request [:active?])
                          {:user   (db/get-user {:id user-id})
                           :rooms  rooms
                           :macros macros}))))


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
        rooms    (db/list-rooms-for-user {:user (:id user)})
        macros   (db/list-macros-for-user {:user (:id user)})
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
                   (conj "Current password was not correct.")

                   (and (not (str/blank? new-pw))
                        (not (<= 12 (.length new-pw))))
                   (conj "New password must be at least 12 characters long.")

                   (and (not (str/blank? new-pw))
                        (not (re-matches #".*[a-z].*" new-pw)))
                   (conj "New password must contain a lowercase letter.")

                   (and (not (str/blank? new-pw))
                        (not (re-matches #".*[A-Z].*" new-pw)))
                   (conj "New password must contain an uppercase letter.")

                   (and (not (str/blank? new-pw))
                        (not (re-matches #".*[0-9].*" new-pw)))
                   (conj "New password must contain a number.")


                   (and (not (str/blank? new-pw))
                        (not (re-matches #".*[^0-9A-Za-z].*" new-pw)))
                   (conj "New password must contain a special character that is not a letter or number."))]
    (if (seq errors)
      (layout/render request "profile.html"
                     (merge (select-keys request [:active?])
                            {:user         (merge user {:name  name
                                                        :email email})
                             :new-password new-pw
                             :error        (str/join " " errors)
                             :rooms        rooms
                             :macros       macros}))
      (do
        (db/update-user! (merge user
                                {:name  name
                                 :email email}
                                (when-not (str/blank? new-pw)
                                  {:pass (hashers/derive new-pw)})))
        (when-not (str/blank? new-pw) (auth/clear-user-sessions user))
        (let [updated-session (update session :identity merge {:name  name
                                                               :email email})]
          (-> (redirect "/")
              (assoc :session updated-session)))))))
