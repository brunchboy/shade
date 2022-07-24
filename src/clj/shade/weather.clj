(ns shade.weather
  "Handles obtaining weather information from the US National Weather
  Service to determine whether sun blocking is needed during the day."
  (:require [shade.config :refer [env]]
            [shade.sun :as sun]
            [clj-http.client :as client]
            [clojure.data.json :as json]
            [java-time :as jt]
            [clojure.tools.logging :as log])
  (:import [java.time Instant ZonedDateTime]))

(defn great-circle-distance
  "Calculate the shortast distance over the earth's surface between the
  two specified points using the haversine forumula."
  [lat-1 lon-1 lat-2 lon-2]
  (let [r  6731.0e3 ; Earth's mean radius in meters
        ϕ1 (sun/degrees-to-radians lat-1)
        ϕ2 (sun/degrees-to-radians lat-2)
        Δϕ (- ϕ2 ϕ1)
        Δλ (sun/degrees-to-radians (- lon-2 lon-1))
        a  (+ (* (Math/sin (/ Δϕ 2)) (Math/sin (/ Δϕ 2)))
              (* (Math/cos ϕ1) (Math/cos ϕ2) (Math/sin (/ Δλ 2)) (Math/sin (/ Δλ 2))))
        c  (* 2 (Math/atan2 (Math/sqrt a) (Math/sqrt (- 1 a))))]
    (* r c)))

(defn- find-closest-observation-station
  "Finds the URL of the observation station geographically closest to us."
  [observation-stations]
  (let [latitude  (get-in env [:location :latitude])
        longitude (get-in env [:location :longitude])]
    (->> (map (fn [station]
                (let [[station-longitude station-latitude] (get-in station ["geometry" "coordinates"])]
                  {:url      (get station "id")
                   :distance (great-circle-distance latitude longitude station-latitude station-longitude)}))
              (get observation-stations "features"))
         (sort-by :distance)  ; Likely unnecessary; they seem to arrive this way, but I have no proof.
         first
         :url)))

(def weather-urls
  "Holds the URLs used to obtain forecast and observation data for the
  location of the blinds. A delay so they can be obtained from the NWS
  the first time it is needed. Once found, they are stored under the
  keys `:forecast` and `:observations`."
  (delay
    (let [point-url  (str "https://api.weather.gov/points/" (get-in env [:location :latitude])
                          "," (get-in env [:location :longitude]))
          point-info (-> (client/get point-url)
                         :body
                         json/read-str)
          obs-url    (get-in point-info ["properties" "observationStations"])]
      {:forecast (get-in point-info ["properties" "forecast"])
       :observations (-> (client/get obs-url)
                         :body
                         json/read-str
                         find-closest-observation-station)})))

(defn- same-day?
  "Checks whether the specified temporal object represents the same day as today."
  [t local-timezone today]
  (= (jt/local-date (jt/with-zone-same-instant t local-timezone)) today))

(defn- update-high-for-today
  "Tries to obtain the forecast high for today."
  []
  (let [local-timezone (jt/zone-id (get-in env [:location :timezone]))
        today          (jt/local-date (jt/with-zone-same-instant (.atZone (Instant/now) (jt/zone-id)) local-timezone))
        forecast       (-> (client/get (:forecast @weather-urls))
                           :body
                           json/read-str)
        forecast       (first (filter (fn [period]
                                        (and (get period "isDaytime")
                                             (same-day? (ZonedDateTime/parse (get period "startTime"))
                                                        local-timezone today)))
                                      (get-in forecast ["properties" "periods"])))
        high           (get forecast "temperature")]
    (when high
      {:temperature high
       :today       today
       :unit        (get today "temperatureUnit")
       :generated   (ZonedDateTime/parse (get-in forecast ["properties" "generatedAt"]))})))

(defn get-observation
  "Tries to obtain the current temperature and cloud cover"
  []
  (let [observation (-> (client/get (str (:observations @weather-urls) "/observations/latest"))
                        :body
                        json/read-str
                        (get "properties"))]
    {:time             (ZonedDateTime/parse (get observation "timestamp"))
     :dewpoint         (get observation "dewpoint")
     :relatve-humidity (get observation "relativeHumidity")
     :temperature      (get observation "temperature")
     :cloud-layers     (get observation "cloudLayers")}))

(def state
  "Keeps track of weather information we have obtained."
  (atom {}))

(defn- time-to-update?
  "Checks whether it is time to update weather information, keeping
  track of when we do. We update every thirty minutes."
  []
  (let [now     (java-time/instant)
        updated (swap! state update :update-attempted
                       (fn [last-attempt]
                         (if (or (not last-attempt)
                                 (> (jt/as (jt/duration last-attempt now) :minutes) 29))
                           now
                           last-attempt)))]
    (= now (:update-attempted updated))))

(defn update-when-due
  "Tries to update weather information"
  []
  (let [previous-update (:update-attempted @state)]
    (when (time-to-update?)
      (future
        (try
          (when-let [observation (get-observation)]
            (swap! state assoc :observation observation))
          ;; We update the forecast high less often, hourly but only before six in the evening.
          (when-not (or (> (jt/as (jt/local-time) :hour-of-day) 17) ; Forecasts for todays are no longer available.
                        (and previous-update (zero? (jt/as (jt/duration previous-update (java-time/instant)) :hours))))
            (when-let [high (update-high-for-today)]
              (swap! state assoc :high high)))
          (catch Throwable t
            (log/error t "Problem getting updated weather information.")))))))

(defn celsius-to-fahrenheit
  [x]
  (+ 32 (* x 1.8)))

(defn latest-temperature
  "Returns the latest temperature observation, and the time at which it
  was made. If dewpoint and relative humidity are available returns
  them as well."
  []
  (when-let [observation (:observation @state)]
    (merge (select-keys observation [:time])
           (when-let [temp (get-in observation [:temperature "value"])]
             {:temperature (celsius-to-fahrenheit temp)})
           (when-let [dew (get-in observation [:dewpoint "value"])]
             {:dewpoint (celsius-to-fahrenheit dew)})
           (when-let [rh (get-in observation [:relative-humidity "value"])]
             {:relative-humidity rh}))))

(defn high-for-today
  "Returns the high temperature for today, if known."
  []
  (let [local-timezone (jt/zone-id (get-in env [:location :timezone]))
        today          (jt/local-date (jt/with-zone-same-instant (.atZone (Instant/now) (jt/zone-id)) local-timezone))
        high           (:high @state)]
    (when (= today (:today high))
      high)))
