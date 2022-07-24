(ns shade.routes.home
  (:require
   [shade.config :refer [env]]
   [shade.layout :as layout]
   [shade.db.core :as db]
   [shade.sun :as sun]
   [shade.weather :as weather]
   [shade.routes.websocket :as ws]
   [clojure.string :as str]
   [shade.middleware :as middleware]
   [buddy.auth :as auth]
   [shade.auth :as shade-auth]
   [buddy.hashers :as hashers]
   [java-time :as jt]
   [ring.util.response :refer [redirect content-type]]
   [ring.util.http-response :as response]
   [ring.util.json-response :refer [json-response]])
  (:import [java.time Instant ZonedDateTime ZoneId]))

(defn home-page [request]
  (let [user-id (get-in request [:session :identity :id])
        macros  (db/list-macros-for-user {:user user-id})
        active  (:is_active (db/get-user {:id user-id}))]
    (layout/render request "home.html" {:macros (ws/macros-in-effect macros user-id)
                                        :active active})))

(defn macro-states [request]
  (let [user-id   (get-in request [:session :identity :id])
        macros    (db/list-macros-for-user {:user user-id})]
    (response/ok (map #(select-keys % [:id :in-effect]) (ws/macros-in-effect macros user-id)))))

(defn about-page [request]
  (layout/render request "about.html"))

(defn login-page [request]
  (layout/render request "login.html"))

(defn profile-page [request]
  (layout/render request "profile.html"
                 {:user (db/get-user {:id (get-in request [:session :identity :id])})}))

(defn localize-timestamp
  "Converts a timestamp from UTC to a local date and time. Accepts
  either an Instant object or a number; if `nil` returns `nil`."
  [timestamp]
  (cond
    (instance? ZonedDateTime timestamp)
    (let [local-timezone (ZoneId/of (get-in env [:location :timezone]))]
      (.toLocalDateTime (.withZoneSameInstant timestamp local-timezone)))

    (instance? Instant timestamp)
    (let [local-timezone (ZoneId/of (get-in env [:location :timezone]))]
      (.toLocalDateTime (.withZoneSameInstant (.atZone timestamp (ZoneId/of "UTC")) local-timezone)))

    (number? timestamp)
    (localize-timestamp (.toInstant (java.util.Date. timestamp)))))

(defn format-timestamp-relative
  "Formats a timestamp as a string, describing it relative to today if
  it falls within a week."
  [timestamp]
  (when-let [localized (localize-timestamp timestamp)]
    (let [local-timezone (ZoneId/of (get-in env [:location :timezone]))
          date           (jt/local-date localized)
          today          (.toLocalDate (.withZoneSameInstant (ZonedDateTime/now) local-timezone))
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
  (let [temp      (weather/latest-temperature)
        high      (weather/high-for-today)
        latitude  (get-in env [:location :latitude])
        longitude (get-in env [:location :longitude])]
    (layout/render request "status.html"
                   {:events            (format-events)
                    :now               (localize-timestamp (Instant/now))
                    :sun               (sun/position (ZonedDateTime/now) latitude longitude)
                    :astronomical-dawn (sun/find-sunrise sun/astronomical-dawn-elevation)
                    :sunrise           (sun/find-sunrise)
                    :sunset            (sun/find-sunset)
                    :connected         (some? @ws/channel-open)
                    :blinds-update     (format-timestamp-relative (:last-update @ws/shade-state))
                    :battery-update    (format-timestamp-relative (:last-battery-update @ws/shade-state))
                    :weather-update    (localize-timestamp (:time temp))
                    :temperature       temp
                    :high              high
                    :high-update       (localize-timestamp (:generated high))})))

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
        (when-not (str/blank? new-pw) (shade-auth/clear-user-sessions user))
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
   ["/status" {:get status-page}]
   ["/run/:id" {:get run-macro}]
   ["/macro-states" {:get macro-states}]])
