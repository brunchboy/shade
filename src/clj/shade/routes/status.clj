(ns shade.routes.status
  "Supports the status page."
  (:require
   [java-time :as jt]
   [shade.config :refer [env]]
   [shade.db.core :as db]
   [shade.layout :as layout]
   [shade.routes.websocket :as ws]
   [shade.sun :as sun]
   [shade.util :as util]
   [shade.weather :as weather]))


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
                  (update :happened util/format-timestamp-relative))))
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
                   (merge (select-keys request [:active? :admin?])
                          {:user              (db/get-user {:id user-id})
                           :rooms             rooms
                           :events            (format-events)
                           :now               (util/localize-timestamp (jt/instant)
                                               )
                           :sun               (sun/position (jt/zoned-date-time) latitude longitude)
                           :astronomical-dawn (sun/find-sunrise sun/astronomical-dawn-elevation)
                           :sunrise           (sun/find-sunrise)
                           :sunset            (sun/find-sunset)
                           :connected?        (some? @ws/channel-open)
                           :blinds-update     (util/format-timestamp-relative (:last-update @ws/shade-state))
                           :battery-update    (util/format-timestamp-relative (:last-battery-update @ws/shade-state))
                           :weather-update    (util/localize-timestamp (:time weather))
                           :weather           weather
                           :high              high
                           :overcast?         (not (ws/not-overcast-enough?))}))))
