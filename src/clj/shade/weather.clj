(ns shade.weather
  "Handles obtaining weather information from the US National Weather
  Service to determine whether sun blocking is needed during the day."
  (:require [shade.config :refer [env]]
            [shade.sun :as sun]
            [clj-http.client :as client]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [java-time :as jt]
            [clojure.tools.logging :as log])
  (:import [java.time ZonedDateTime]))

(def state
  "Keeps track of weather information we have obtained."
  (atom {}))

(defn current-weather
  "Gets current weather information for the location of the blinds."
  []
  (let [url (str "https://api.openweathermap.org/data/2.5/weather?lat=" (get-in env [:location :latitude])
                 "&lon=" (get-in env [:location :longitude]) "&appid=" (:openweather-api-key env)
                 "&units=imperial")]
    (-> (client/get url)
        :body
        json/read-str)))

(defn parse-openweather-timestamp
  "Converts an OpenWeather API dt timestamp to a `ZonedDateTime`
  object."
  [dt]
  (java-time/zoned-date-time (java-time/instant (* dt 1000)) "UTC"))

(defn format-current-weather
  "Extracts current weather information details used to determine
  whether sun block action is appropriate."
  []
  (let [weather (current-weather)]
    {:time             (parse-openweather-timestamp (get weather "dt"))
     :temperature      (get-in weather ["main" "temp"])
     :relatve-humidity (get-in weather ["main" "humidity"])
     :cloud-percentage (get-in weather ["clouds" "all"])}))

(defn- same-day?
  "Checks whether the specified temporal object represents the same day as today."
  [t local-timezone today]
  (= (jt/local-date (jt/with-zone-same-instant t local-timezone)) today))

(defn current-forecast
  "Gets latest forecast information for the location of the blinds."
  []
  (let [url (str "https://api.openweathermap.org/data/2.5/forecast?lat=" (get-in env [:location :latitude])
                 "&lon=" (get-in env [:location :longitude]) "&appid=" (:openweather-api-key env)
                 "&units=imperial&cnt=16")]
    (-> (client/get url)
        :body
        json/read-str)))

(defn format-current-forecast
  "Extracts daily high and low temperatures for the next two days from
  the latest forecast information."
  [forecast old-forecast]
  (let [local-timezone (jt/zone-id (get-in env [:location :timezone]))
        today          (jt/local-date (jt/instant) local-timezone)
        updated        (reduce (fn [acc entry]
                                 (let [timestamp (parse-openweather-timestamp (get entry "dt"))
                                       date-key  (jt/local-date (jt/with-zone-same-instant timestamp local-timezone))]
                                   (update acc date-key
                                           (fn [existing]
                                             (if-let [temperature (get-in entry ["main" "temp"])]
                                               {:low  (min (:low existing 2000.0) temperature)
                                                :high (max (:high existing -2000.0) temperature)}
                                               existing)))))
                               (or old-forecast {})
                               (get forecast "list"))]
    ;; Get rid of any forecasts for days that are now in the past.
    (select-keys updated (remove (partial jt/after? today) (keys updated)))))

(defn- time-to-update?
  "Checks whether it is time to update weather information, keeping
  track of when we do. We update every thirty seconds."
  []
  (let [now     (java-time/instant)
        updated (swap! state update :update-attempted
                       (fn [last-attempt]
                         (if (or (not last-attempt)
                                 (> (jt/as (jt/duration last-attempt now) :seconds) 29))
                           now
                           last-attempt)))]
    (= now (:update-attempted updated))))

(defn update-when-due
  "Tries to update weather information"
  []
  (when (time-to-update?)
    (future
      (try
        (let [weather (format-current-weather)
              forecast (current-forecast)]
          (swap! state (fn [old-state]
                         (-> old-state
                             (assoc :weather weather)
                             (update :forecast (partial format-current-forecast forecast))))))
        (catch Throwable t
          (log/error t "Problem getting updated weather information."))))))

(defn overcast?
  "Checks whether there are enough clouds to ignore the temperature.
  Let's see how it works to say the coverage has to be more than 97%."
  []
  (when-let [cloud-percentage (get-in @state [:weather :cloud-percentage])]
    (> cloud-percentage 97)))

(defn forecast-for-today
  "Look up today's forecast."
  []
  (let [local-timezone (jt/zone-id (get-in env [:location :timezone]))
        today          (jt/local-date (jt/instant) local-timezone)]
    (get-in @state [:forecast today])))
