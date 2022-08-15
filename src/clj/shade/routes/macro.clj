(ns shade.routes.macro
  "Supports the creation, editing, running, and querying of shade
  macros."
  (:require [clojure.string :as str]
            [conman.core :as conman]
            [shade.db.core :as db]
            [shade.layout :as layout]
            [shade.routes.websocket :as ws]
            [ring.util.http-response :as response]
            [ring.util.json-response :refer [json-response]]
            [ring.util.response :refer [redirect]])
  (:import  (java.util UUID)))

(defn macro-states [request]
  (let [user-id   (get-in request [:session :identity :id])
        macros    (db/list-macros-enabled-for-user {:user user-id})]
    (response/ok (map #(select-keys % [:id :in-effect :rooms]) (ws/macros-in-effect macros user-id)))))

(defn run-macro [{:keys [path-params session params]}]
  (ws/run-macro (UUID/fromString (:id path-params)) (get-in session [:identity :id])
                (when-let [room (:room params)] (UUID/fromString room)))
  (json-response {:action "Macro run"}))

(defn set-macro-visibility [{:keys [path-params session]}]
  (let [user                               (:identity session)
        {:keys [macro-id user-id visible]} path-params
        user-id                            (UUID/fromString user-id)
        macro-id                           (UUID/fromString macro-id)]
    (if (or (= user-id (:id user))
            (:admin user))
      ;; User is allowed to make this change.
      (do
        (if (Boolean/valueOf visible)
          (db/create-user-macro! {:user user-id :macro macro-id})   ; Enabling this macro.
          (db/delete-user-macro! {:user user-id :macro macro-id}))  ; Disabling it.
        (json-response {:action "Visibility updated."}))
      ;; User is not allowed to make this change
      (layout/error-page {:status 401 :title "401 - Unauthorized"}))))

(defn macro-page [{:keys [path-params session] :as request}]
  (let [user-id  (get-in session [ :identity :id])
        macro-id (when-let [id (:id path-params)] (UUID/fromString id))
        rooms    (db/list-rooms-for-user {:user user-id})
        macro    (when macro-id (db/get-macro {:id macro-id}))
        entries  (when macro (db/get-all-macro-entries {:macro macro-id}))
        shades   (ws/shades-for-macro-editor entries)]
    (if (and macro-id (not macro))
      (layout/error-page {:status 404 :title "404 - Macro not found"})
      (layout/render request "admin-macro.html"
                     (merge (select-keys request [:active?])
                            {:user    (db/get-user {:id user-id})
                             :rooms   rooms
                             :macro   macro
                             :shades  shades
                             :preview (reduce (fn [acc shade]
                                                (if-let [level (:macro-level shade)]
                                                  (assoc acc (:id shade) level)
                                                  acc))
                                              {}
                                              shades)})))))

(defn list-macros-page [{:keys [session] :as request}]
  (let [user-id (get-in session [ :identity :id])
        macros  (db/list-macros)
        rooms   (db/list-rooms-for-user {:user user-id})]
    (layout/render request "admin-macros.html"
                   (merge (select-keys request [:active?])
                          {:user   (db/get-user {:id user-id})
                           :macros macros
                           :rooms  rooms}))))

(defn- merge-macro-form
  "Updates a list of current shade positions to reflect what values were
  set on a failed form submission, and which shades were to be included."
  [shades entries]
  (map (fn [shade]
         (let [entry (get entries (:id shade))]
           (cond-> shade
             (:level entry)
             (assoc :level (:level entry))

             (:enabled entry)
             (assoc :macro-level (:level entry)))))
       shades))

(defn- parse-macro-form
  "Converts the form parameters from the macro editor into a map from
  shade ID to level and enabled information."
  [form-params]
  (reduce (fn [acc [k v]]
            (cond
              (re-matches #"enabled-.*" k)
              (assoc-in acc [(UUID/fromString (subs k 8)) :enabled] (boolean (= v "on")))

              (re-matches #"level-.*" k)
              (assoc-in acc [(UUID/fromString (subs k 6)) :level] (Long/parseLong v))

              :else acc))
          {}
          form-params))

(defn create-macro-entries
  "Creates the necessary macro entries to save a macro that has been
  edited or created."
  [macro-id entries]
  (doseq [[shade-id entry] entries]
    (when (:enabled entry)
      (db/create-macro-entry! (merge (select-keys entry [:level])
                                     {:macro macro-id
                                      :shade shade-id})))))

(defn macro-save [{:keys [path-params form-params session] :as request}]
  (let [user-id  (get-in session [ :identity :id])
        user     (db/get-user {:id user-id})
        macro-id (when-let [id (:id path-params)] (UUID/fromString id))
        rooms    (db/list-rooms-for-user {:user user-id})
        name     (form-params "name")
        entries  (parse-macro-form form-params)
        errors   (cond-> []
                   (str/blank? name)
                   (conj "Name cannot be empty.")

                   (when-let [existing (db/get-macro-by-name {:name name})]
                     (not= (:id existing) macro-id))
                   (conj "Another macro with this name already exists.")

                   (not (:admin user))
                   (conj "Macros can only be viewed."))]
    (if (seq errors)
      (layout/render request "admin-macro.html"
                     (merge (select-keys request [:active?])
                            {:user   user
                             :rooms  rooms
                             :macro  {:id   macro-id
                                      :name name}
                             :shades (merge-macro-form (ws/shades-for-macro-editor nil) entries)
                             :error  (str/join " " errors)}))
      (conman/with-transaction [db/*db*]
        (if macro-id
          (do  ; Updating an existing macro
            (db/update-macro! {:id   macro-id
                               :name name})
            (db/delete-macro-entries! {:macro macro-id})
            (create-macro-entries macro-id entries))
          (let [macro-id (:id (db/create-macro! {:name name}))]  ; Creating a new macro
            (create-macro-entries macro-id entries)
            (db/create-user-macro! {:user  user-id
                                    :macro macro-id})))
        (redirect "/admin")))))

(defn delete-macro-page [{:keys [path-params session] :as request}]
  (let [user-id  (get-in session [ :identity :id])
        rooms    (db/list-rooms-for-user {:user user-id})
        macro-id (when-let [id (:id path-params)] (UUID/fromString id))
        macro    (when macro-id (db/get-macro {:id macro-id}))]
    (if-not macro
      (layout/error-page {:status 404 :title "404 - Macro not found"})
      (layout/render request "admin-delete-macro.html"
                     (merge (select-keys request [:active?])
                            {:user   (db/get-user {:id user-id})
                             :rooms  rooms
                             :macro  macro})))))

(defn macro-delete [{:keys [path-params]}]
  (let [macro-id (when-let [id (:id path-params)] (UUID/fromString id))]
    (if-not macro-id
      (layout/error-page {:status 404 :title "404 - Macro not found"})
      (do
        (db/delete-macro! {:id macro-id} )
        (redirect "/admin/macros")))))
