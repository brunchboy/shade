(ns shade.routes.sunblock
  "Supports the creation, editing, running, and querying of sunblock
  groups."
  (:require [clojure.string :as str]
            [shade.db.core :as db]
            [shade.layout :as layout]
            [ring.util.http-response :as response]
            [ring.util.json-response :refer [json-response]]
            [ring.util.response :refer [redirect]])
  (:import  (java.util UUID)))

#_(defn macro-page [{:keys [path-params session] :as request}]
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

(defn list-groups-page [{:keys [session] :as request}]
  (let [user-id (get-in session [ :identity :id])
        groups  (db/list-sunblock-groups)
        rooms   (db/list-rooms-for-user {:user user-id})]
    (layout/render request "admin-sunblock-groups.html"
                   (merge (select-keys request [:active?])
                          {:user   (db/get-user {:id user-id})
                           :groups (mapv (fn [group]
                                           (assoc group :count (count (db/get-sunblock-group-shades
                                                                       {:sunblock_group (:id group)}))))
                                        groups)
                           :rooms  rooms}))))

(defn assign-shades-page [{:keys [session] :as request}]
  (let [user-id (get-in session [ :identity :id])
        groups  (db/list-sunblock-groups)
        shades  (filter #(= (:kind %) "blackout") (db/list-shades))
        rooms   (db/list-rooms-for-user {:user user-id})]
    (layout/render request "admin-sunblock-shades.html"
                   (merge (select-keys request [:active?])
                          {:user   (db/get-user {:id user-id})
                           :groups groups
                           :shades (mapv (fn [shade]
                                           (assoc shade :group (first (filter (fn [group]
                                                                                (= (:id group)
                                                                                   (:sunblock_group_id shade)))
                                                                              groups))))
                                         shades)
                           :rooms  rooms}))))

(defn set-shade-sunblock-group [{:keys [params]}]
  (let [group (:group params)]
    (db/set-shade-sunblock-group! {:id             (UUID/fromString (:shade params))
                                   :sunblock_group (when-not (str/blank? group) (UUID/fromString group))}))
  (json-response {:action "Sunblock Group set"}))
