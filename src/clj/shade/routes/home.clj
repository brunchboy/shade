(ns shade.routes.home
  (:require
   [shade.config :refer [env]]
   [shade.layout :as layout]
   [shade.db.core :as db]
   [shade.sun :as sun]
   [shade.weather :as weather]
   [shade.routes.websocket :as ws]
   [clojure.set :as set]
   [clojure.string :as str]
   [shade.middleware :as middleware]
   [shade.auth :as shade-auth]
   [buddy.hashers :as hashers]
   [java-time :as jt]
   [conman.core :as conman]
   [ring.util.response :refer [redirect content-type]]
   [ring.util.http-response :as response]
   [ring.util.json-response :refer [json-response]])
  (:import
   (java.util UUID)))

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

(defn macro-states [request]
  (let [user-id   (get-in request [:session :identity :id])
        macros    (db/list-macros-enabled-for-user {:user user-id})]
    (response/ok (map #(select-keys % [:id :in-effect :rooms]) (ws/macros-in-effect macros user-id)))))

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

(defn shades-visible [{:keys [path-params session]}]
  (let [user-id (get-in session [:identity :id])
        room-id (UUID/fromString (:room path-params))]
    (json-response (ws/shades-visible room-id user-id))))

(defn macro-page [{:keys [path-params session] :as request}]
  (let [user-id  (get-in session [ :identity :id])
        macro-id (when-let [id (:id path-params)] (UUID/fromString id))
        rooms    (db/list-rooms-for-user {:user user-id})
        macro    (when macro-id (db/get-macro {:id macro-id}))
        entries  (when macro (db/get-all-macro-entries {:macro macro-id}))
        shades   (ws/shades-for-macro-editor entries)]
    (if (and macro-id (not macro))
      (layout/error-page {:status 404 :title "404 - Macro not found"})
      (layout/render request "macro.html"
                     (merge (select-keys request [:active?])
                            {:user   (db/get-user {:id user-id})
                             :rooms  rooms
                             :macro  macro
                             :shades shades})))))

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
      (layout/render request "macro.html"
                     (merge (select-keys request [:active])
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
        (redirect "/profile")))))

(defn delete-macro-page [{:keys [path-params session] :as request}]
  (let [user-id  (get-in session [ :identity :id])
        rooms    (db/list-rooms-for-user {:user user-id})
        macro-id (when-let [id (:id path-params)] (UUID/fromString id))
        macro    (when macro-id (db/get-macro {:id macro-id}))]
    (if-not macro
      (layout/error-page {:status 404 :title "404 - Macro not found"})
      (layout/render request "delete-macro.html"
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
        (redirect "/profile")))))


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
   ["/delete-macro/:id" {:get  delete-macro-page
                         :post macro-delete}]
   ["/about" {:get about-page}]
   ["/login" {:get  login-page
              :post login-authenticate}]
   ["/logout" {:get logout}]
   ["/macro/" {:get  macro-page
               :post macro-save}]
   ["/macro/:id" {:get  macro-page
                  :post macro-save}]
   ["/macro-states" {:get macro-states}]
   ["/profile" {:get  profile-page
                :post profile-update}]
   ["/room/:id" {:get room-page}]
   ["/run/:id" {:get run-macro}]
   ["/set-macro-visibility/:macro-id/:user-id/:visible" {:get set-macro-visibility}]
   ["/shades-visible/:room" {:get shades-visible}]
   ["/status" {:get status-page}]])
