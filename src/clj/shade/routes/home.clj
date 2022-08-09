(ns shade.routes.home
  (:require
   [shade.config :refer [env]]
   [shade.layout :as layout]
   [shade.db.core :as db]
   [shade.sun :as sun]
   [shade.weather :as weather]
   [shade.routes.macro :as macro]
   [shade.routes.websocket :as ws]
   [clojure.set :as set]
   [clojure.string :as str]
   [shade.middleware :as middleware]
   [shade.auth :as shade-auth]
   [buddy.hashers :as hashers]
   [java-time :as jt]
   [ring.util.response :refer [redirect content-type]]
   [ring.util.http-response :as response]
   [ring.util.json-response :refer [json-response]])
  (:import
   (java.util UUID)
   (java.awt Polygon)))

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
                                              {:macros      (map (partial merge-macro-buttons macro-rooms) in-effect)
                                               :rooms       rooms
                                               :macro-rooms macro-rooms}))))

(defn about-page [request]
  (let [user-id (get-in request [:session :identity :id])
        rooms   (db/list-rooms-for-user {:user user-id})]
    (layout/render request "about.html" (merge (select-keys request [:active?])
                                               {:rooms rooms}))))

(defn login-page [request]
  (layout/render request "login.html" (select-keys request [:active?])))

(defn profile-page [request]
  (let [user-id (get-in request [:session :identity :id])
        rooms   (db/list-rooms-for-user {:user user-id})
        macros  (db/list-macros-for-user {:user user-id})]
    (layout/render request "profile.html"
                   (merge (select-keys request [:active?])
                          {:user   (db/get-user {:id user-id})
                           :rooms  rooms
                           :macros macros}))))

(defn- promote-room-state
  "Modifies a macro entry from the in-effect list so the specified
  room's in-effect state is reflected at the top level, since the
  macro buttons on the room page affect only that room."
  [room-id macro]
  (let [rooms (:rooms macro)]
    (assoc macro :in-effect (get rooms room-id))))

(defn room-page [{:keys [path-params session] :as request}]
  (let [user-id   (get-in session [:identity :id])
        rooms     (db/list-rooms-for-user {:user user-id})
        room-id   (UUID/fromString (:id path-params))
        room      (db/get-room {:id room-id})
        macros    (db/list-macros-enabled-for-user-in-room {:user user-id
                                                            :room room-id})
        in-effect (ws/macros-in-effect macros user-id)]
    (if (and room (some #(= (:id %) room-id) rooms))
      (layout/render request "room.html"
                     (merge (select-keys request [:active?])
                            {:onload "draw();"
                             :user   (db/get-user {:id user-id})
                             :rooms  rooms
                             :room   room
                             :macros (map (partial promote-room-state room-id) in-effect)}))
      (layout/error-page {:status 404 :title "404 - Page not found"}))))

(defn localize-timestamp
  "Converts a timestamp to a local date and time (if an un-zoned
  Instant, considers it to be in UTC). Accepts either an Instant
  object or a number; if `nil` returns `nil`."
  [timestamp]
  (cond
    (jt/zoned-date-time? timestamp)
    (let [local-timezone (jt/zone-id (get-in env [:location :timezone]))]
      (jt/local-date-time (jt/with-zone-same-instant timestamp local-timezone)))

    (jt/instant? timestamp)
    (let [local-timezone (jt/zone-id (get-in env [:location :timezone]))]
      (jt/local-date-time (jt/with-zone-same-instant (.atZone timestamp (jt/zone-id "UTC")) local-timezone)))

    (number? timestamp)
    (localize-timestamp (jt/instant timestamp))))

(defn format-timestamp-relative
  "Formats a timestamp as a string, describing it relative to today if
  it falls within a week."
  [timestamp]
  (when-let [localized (localize-timestamp timestamp)]
    (let [local-timezone (jt/zone-id (get-in env [:location :timezone]))
          date           (jt/local-date localized)
          today          (jt/local-date (jt/instant) local-timezone)
          days           (jt/as (jt/period date today) :days)]
      (str (case days
             0           "Today"
             1           "Yesterday"
             (2 3 4 5 6) (jt/format "EEEE" date)
             (jt/format "YYYY-MM-dd" date))
           (jt/format " HH:mm:ss" localized)))))

(defn- format-events
  "Gather information about events which have been recorded for display
  on the status page."
  []
  (->> (db/list-events)
       (map (fn [event]
              (-> event
                  (assoc :related-name (case (:name event)
                                         ("sunblock-group-entered" "sunblock-group-exited")
                                         (:name (db/get-sunblock-group {:id (:related_id event)}))

                                         nil))
                  (update :name {"sunblock-group-entered" "Sun Block started"
                                 "sunblock-group-exited"  "Sun Block ended"
                                 "sunrise-protect"        "Sunrise Protection"})
                  (update :happened format-timestamp-relative))))
       (filter :name)))  ; Remove the ones we have no name for.

(defn status-page [request]
  (let [weather   (:weather @weather/state)
        forecast  (weather/forecast-for-today)
        high      (when forecast (:high forecast))
        latitude  (get-in env [:location :latitude])
        longitude (get-in env [:location :longitude])
        user-id   (get-in request [:session :identity :id])
        rooms     (db/list-rooms-for-user {:user user-id})]
    (layout/render request "status.html"
                   (merge (select-keys request [:active?])
                          {:rooms             rooms
                           :events            (format-events)
                           :now               (localize-timestamp (jt/instant))
                           :sun               (sun/position (jt/zoned-date-time) latitude longitude)
                           :astronomical-dawn (sun/find-sunrise sun/astronomical-dawn-elevation)
                           :sunrise           (sun/find-sunrise)
                           :sunset            (sun/find-sunset)
                           :connected?        (some? @ws/channel-open)
                           :blinds-update     (format-timestamp-relative (:last-update @ws/shade-state))
                           :battery-update    (format-timestamp-relative (:last-battery-update @ws/shade-state))
                           :weather-update    (localize-timestamp (:time weather))
                           :weather           weather
                           :high              high
                           :overcast?         (not (ws/not-overcast-enough?))}))))

(defn shades-visible [{:keys [path-params session]}]
  (let [user-id (get-in session [:identity :id])
        room-id (UUID/fromString (:room path-params))]
    (json-response (ws/shades-visible room-id user-id))))

(defn point-on-line?
  "Calculates a cross product to determine if a point falls on, below,
  or above a line. Returns zero if the point is on the line, positive
  if it is above it, and zero if below it."
  [line-left-x line-left-y line-right-x line-right-y x y]
  (let [dx (- line-right-x line-left-x)
        dy (- line-right-y line-left-y)
        mx (- x line-left-x)
        my (- y line-left-y)]
    (- (* dx my) (* dy mx))))

(defn find-level
  [x y shade]
  (loop [max-level 100
         min-level 0]
    (let [level (+ min-level (quot (- max-level min-level) 2))]
      (if (or (= level max-level) (= level min-level))
        level  ; We've reached the limit of our resolution.
        (let [line-left-x  (ws/interpolate (:top_left_x shade) (:bottom_left_x shade) level)
              line-left-y  (ws/interpolate (:top_left_y shade) (:bottom_left_y shade) level)
              line-right-x (ws/interpolate (:top_right_x shade) (:bottom_right_x shade) level)
              line-right-y (ws/interpolate (:top_right_y shade) (:bottom_right_y shade) level)
              direction    (point-on-line? line-left-x line-left-y line-right-x line-right-y x y)]
          (if (zero? direction)
            level  ; We hit the point exactly.
            (if (pos? direction)
              (recur level min-level)  ; The current level has the blind too low.
              (recur max-level level))))))))  ; The current level has the blind too high.

(defn level-from-point
  "Given the coordinates of a point within a room image, and a shade
  boundary record, returns the record of the shade which was tapped,
  if any, augmented with the level at which it was tapped."
  [x y shade]
  (let [poly (Polygon.)]
    (.addPoint poly (:top_left_x shade) (:top_left_y shade))
    (.addPoint poly (:top_right_x shade) (:top_right_y shade))
    (.addPoint poly (:bottom_right_x shade) (:bottom_right_y shade))
    (.addPoint poly (:bottom_left_x shade) (:bottom_left_y shade))
    (cond (.contains poly x y)
          (assoc shade :level (find-level x y shade))

          (and (>= x (:top_left_x shade))
               (<= x (:top_right_x shade))
               (<= y (max (:top_left_y shade) (:top_right_y shade))))
          (assoc shade :level 100)  ; Click above shade means open all the way.

          (and (>= x (:bottom_left_x shade))
               (<= x (:bottom_right_x shade))
               (>= y (min (:bottom_left_y shade) (:bottom_right_y shade))))
          (assoc shade :level 0))))  ; Click below shade means close all the way.

(defn shade-tapped [{:keys [path-params params session]}]
  (let [user-id     (get-in session [:identity :id])
        room-id     (UUID/fromString (:room path-params))
        x           (Long/valueOf (:x params))
        y           (Long/valueOf (:y params))
        kind        (:kind params "blackout")
        valid-rooms (->> (db/list-rooms-for-user {:user user-id}))
        room        (first (filter #(= (:id %) room-id) valid-rooms))]
    (when room
      (let [shades (db/get-room-photo-boundaries {:room room-id})
            hit    (->> shades
                        (filter #(= (:kind %) kind))
                        (map (partial level-from-point x y))
                        (filter identity)
                        first)]
        (when hit
          (ws/move-shades {(keyword (str (:shade_id hit))) (:level hit)}))
        (json-response hit)))))


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
                            {:user         {:name  name
                                            :email email}
                             :new-password new-pw
                             :error        (str/join " " errors)}))
      (do
        (db/update-user! (merge user
                                {:name  name
                                 :email email}
                                (when-not (str/blank? new-pw)
                                  {:pass (hashers/derive new-pw)})))
        (when-not (str/blank? new-pw) (shade-auth/clear-user-sessions user))
        (let [updated-session (update session :identity merge {:name  name
                                                               :email email})]
          (-> (redirect "/")
              (assoc :session updated-session)))))))

(defn logout [_request]
  (-> (redirect "/login")
      (assoc :session {})))

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
   ["/delete-macro/:id" {:get  macro/delete-macro-page
                         :post macro/macro-delete}]
   ["/about" {:get about-page}]
   ["/login" {:get  login-page
              :post login-authenticate}]
   ["/logout" {:get logout}]
   ["/macro/" {:get  macro/macro-page
               :post macro/macro-save}]
   ["/macro/:id" {:get  macro/macro-page
                  :post macro/macro-save}]
   ["/macro-states" {:get macro/macro-states}]
   ["/profile" {:get  profile-page
                :post profile-update}]
   ["/room/:id" {:get room-page}]
   ["/run/:id" {:post macro/run-macro}]
   ["/set-macro-visibility/:macro-id/:user-id/:visible" {:post macro/set-macro-visibility}]
   ["/set-shade-levels" {:post set-shade-levels}]
   ["/shade-tapped/:room" {:post shade-tapped}]
   ["/shades-visible/:room" {:get shades-visible}]
   ["/status" {:get status-page}]])
