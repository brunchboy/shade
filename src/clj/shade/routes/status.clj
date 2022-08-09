(ns shade.routes.status
  "Supports the status page."
  (:require
   [java-time :as jt]
   [shade.config :refer [env]]
   [shade.db.core :as db]
   [shade.layout :as layout]
   [shade.routes.websocket :as ws]
   [shade.sun :as sun]
   [shade.weather :as weather]))


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
