(ns shade.routes.home
  "Supports the home page, a few tiny pages and actions, and defines the
  main routing for the application."
  (:require
   [shade.layout :as layout]
   [shade.db.core :as db]
   [shade.routes.admin :as admin]
   [shade.routes.login :as login]
   [shade.routes.macro :as macro]
   [shade.routes.profile :as profile]
   [shade.routes.room :as room]
   [shade.routes.status :as status]
   [shade.routes.user :as user]
   [shade.routes.websocket :as ws]
   [clojure.set :as set]
   [clojure.string :as str]
   [shade.middleware :as middleware]
   [ring.util.json-response :refer [json-response]]))

(defn build-macro-rooms
  "Creates a list describing the rooms which are affected by macros
  available on a page. Will be `nil` if there are no macros affecting
  more than one room. Otherwise, contains a list of maps holding the
  room names (for explanation in a key at the bottom of the page), and
  abbreviations for use on small buttons to run the macro just for
  that room."
  [rooms in-effect]
  (when (some #(> % 1) (map #(count (:rooms %)) in-effect))
    (let [affected (apply set/union (map #(set (keys (:rooms %))) in-effect))]
      (map (fn [room]
             (let [room-name (:name room)]
               {:name   room-name
                :button (->> (str/split room-name #"\s+")
                             (map first)
                             (apply str)
                             clojure.string/upper-case)
                :id     (:id room)}))
           (filter #(affected (:id %)) rooms)))))

(defn- merge-macro-buttons
  "Adds information to a macro entry from the in-effect list making it
  easy for the room template to iterate over and create buttons for
  sending macros to single rooms. Returns the entry unchanged if there
  are no macros which affect multiple rooms."
  [macro-rooms macro]
  (if (empty? macro-rooms)
    macro
    (let [rooms (:rooms macro)]
      (assoc macro :room-buttons
             (for [room macro-rooms]
               (when (contains? rooms (:id room))
                 (assoc room :in-effect (get rooms (:id room)))))))))

(defn home-page [request]
  (let [user-id     (get-in request [:session :identity :id])
        macros      (db/list-macros-enabled-for-user {:user user-id})
        rooms       (db/list-rooms-for-user {:user user-id})
        in-effect   (ws/macros-in-effect macros user-id)
        macro-rooms (build-macro-rooms rooms in-effect)]
    (layout/render request "home.html" (merge (select-keys request [:active?])
                                              {:user        (db/get-user {:id user-id})
                                               :macros      (map (partial merge-macro-buttons macro-rooms) in-effect)
                                               :rooms       rooms
                                               :macro-rooms macro-rooms}))))

(defn about-page [request]
  (let [user-id (get-in request [:session :identity :id])
        rooms   (db/list-rooms-for-user {:user user-id})]
    (layout/render request "about.html" (merge (select-keys request [:active?])
                                               {:rooms rooms}))))


(defn set-shade-levels
  [{:keys [params]}]
  (ws/move-shades params)
  (json-response {:action "Shade levels set."}))

(defn wrap-active [handler]
  (fn [request]
    (if-let [id (get-in request [:identity :id])]
      (let [user (db/get-user {:id id})]
        (handler (assoc request :active? (:is_active user))))
      (handler request))))

(defn home-routes []
  [""
   {:middleware [middleware/wrap-csrf
                 middleware/wrap-formats
                 wrap-active]}
   ["/" {:get home-page}]
   ["/about" {:get about-page}]
   ["/admin/delete-macro/:id" {:get  macro/delete-macro-page
                               :post macro/macro-delete}]
   ["/admin/delete-user/:id" {:get  user/delete-user-page
                               :post user/user-delete}]
   ["/admin/macros" {:get macro/list-macros-page}]
   ["/admin/user/" {:get  user/user-page
                     :post user/user-save}]
   ["/admin/user/:id" {:get  user/user-page
                        :post user/user-save}]
   ["/admin/users" {:get user/list-users-page}]
   ["/admin" {:get admin/admin-page}]
   ["/login" {:get  login/login-page
              :post login/login-authenticate}]
   ["/logout" {:get login/logout}]
   ["/macro/" {:get  macro/macro-page
                     :post macro/macro-save}]
   ["/macro/:id" {:get  macro/macro-page
                        :post macro/macro-save}]
   ["/macro-states" {:get macro/macro-states}]
   ["/profile" {:get  profile/profile-page
                :post profile/profile-update}]
   ["/room/:id" {:get room/room-page}]
   ["/run/:id" {:post macro/run-macro}]
   ["/set-macro-visibility/:macro-id/:user-id/:visible" {:post macro/set-macro-visibility}]
   ["/set-shade-levels" {:post set-shade-levels}]
   ["/shade-tapped/:room" {:post room/shade-tapped}]
   ["/shades-visible/:room" {:get room/shades-visible}]
   ["/status" {:get status/status-page}]])
