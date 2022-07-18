(ns shade.sun
  "Astronomical calculations to determine the position of the sun at a
  particular time and geographic location. Calulations ported from Sun
  Position: Astronomical Algorithm in 9 Common Programming Languages,
  by John Clark Craig. Please see that book for much more detailed
  explanations of these calculations, along with diagrams and other
  useful information."
  (:import (java.time.temporal ChronoField JulianFields Temporal)
           (java.util.concurrent TimeUnit)))

(defn decimal-degrees
  "Convert degrees, minutes, and seconds to decimal degrees."
  [degrees minutes seconds]
  (+ degrees (/ minutes 60.0) (/ seconds 3600.0)))

(defn normalize-to-range
  "Given a cyclical value, returns where it falls in the specified range."
  [x minimum maximum]
  (let [shifted-x (- x minimum)
        delta (- maximum minimum)]
    (+ minimum (mod (+ (mod shifted-x delta) delta) delta))))

(defn decimal-hour-of-day-utc
  "Given a Temporal object, determines the decimal hour of the day it
  represents in Coordinated Universal Time."
  [^Temporal t]
  (let [offset (/ (.get t ChronoField/OFFSET_SECONDS)
                  (double (.toSeconds TimeUnit/HOURS 1)))]
    (+ (- (.get t ChronoField/HOUR_OF_DAY) offset)  ; The current hour of the day at Greenwich.
       (/ (.get t ChronoField/MINUTE_OF_HOUR) (double (.toMinutes TimeUnit/HOURS 1)))  ; Decimal minute.
       (/ (.get t ChronoField/SECOND_OF_MINUTE) (double (.toSeconds TimeUnit/HOURS 1)))))) ; Decimal second.


(def epoch-2000
  "The start of January 1, 2000 in UTC; the epoch for Julian date
  computations used by the rest of the code."
  (java.time.ZonedDateTime/of 2000 1 1 0 0 0 0 (java.time.ZoneId/of "UTC")))

(defn decimal-days-since-2000
  "Given a Temporal object, calculate the distance in decimal days it
  represents from the start of January 1, 2000 in UTC. This
  implementation is valid for all dates supported by `java.time`, and
  returns Julian time values in the form expected by the remaining
  calculations."
  [^Temporal t]
  (+ (- (.getLong t JulianFields/JULIAN_DAY) (.getLong epoch-2000 JulianFields/JULIAN_DAY))
     (/ (decimal-hour-of-day-utc t) 24)
     -0.5))

(def tau
  "A convenience constant holding Pi times two."
  (* 2.0 Math/PI))

(def radians-per-degree
  "The conversion factor between degrees and radians."
  (/ Math/PI 180.0))

(defn degrees-to-radians
  "Converts a degrees value to radians."
  [degrees]
  (* degrees radians-per-degree))

(defn radians-to-degrees
  "Converts a radians value to degrees, normalized to the positive range
  0 through 360, or to a different range if `lower` and `upper` bounds
  are supplied."
  ([radians]
   (radians-to-degrees radians 0.0, 360.0))
  ([radians lower upper]
   (normalize-to-range (/ radians radians-per-degree) lower upper)))

(defn mean-longitude
  "Calculates the longitude, in radians, the sun would have if the
  earth's orbit were a perfect circle. Later calculations correct this
  for the actual elliptical orbit."
  [decimal-date]
  (normalize-to-range (+ (* decimal-date 0.01720279239) 4.894967873) 0 tau))

(defn mean-anomaly
  "The first-order adjustment for the sun's longitude."
  [decimal-date]
  (normalize-to-range (+ (* decimal-date 0.01720197034) 6.240040768) 0 tau))

(defn ecliptic-longitude
  "Adjusts the mean anomaly for the plane of the earth's orbit around
  the sun."
  [mean-longitude mean-anomaly]
  (+ mean-longitude (* 0.03342305518 (Math/sin mean-anomaly)) (* 0.0003490658504 (Math/sin (* 2 mean-anomaly)))))

(defn obliquity-of-ecliptic
  "The tilt of the earth's axis of spin as measured from the plane of
  the ecliptic, in radians."
  [decimal-date]
  (- 0.4090877234 (* 0.000000006981317008 decimal-date)))

(defn right-ascension
  "The right ascension of the sun, in radians, calculated from the
  ecliptic longitude, and adjusted for the obliquity of the ecliptic.
  One of two values which defines the position of the sun within the
  celestial sphere (in which stars have fixed positions, but the sun
  and planets move)."
  [ecliptic-longitude obliquity]
  (Math/atan2 (* (Math/cos obliquity) (Math/sin ecliptic-longitude))
              (Math/cos ecliptic-longitude)))

(defn declination
  "The other value which determines the sun's position within the
  celestial sphere, in radians. (Declination is relative to the north
  and south celestial poles, with +90° declination at the north pole,
  and -90° declination at the south pole.)"
  [ecliptic-longitude obliquity]
  (Math/asin (* (Math/sin obliquity) (Math/sin ecliptic-longitude))))

(defn sidereal-time
  "The local sidereal time, a clock time based on the spin of the Earth
  as measured by the stars, not the sun."
  [decimal-date longitude-radians]
  (normalize-to-range (+ 4.894961213 (* 6.300388099 decimal-date) longitude-radians) 0 tau))

(defn hour-angle
  "The hour angle of the sun in radians. This is based on the local
  sidereal time and the current right ascension of the sun. This is
  getting close to knowing where the sun is in the sky."
  [local-siderial-time sun-right-ascension]
  (normalize-to-range (- local-siderial-time sun-right-ascension) 0 tau))

(defn elevation
  "The local elevation of the sun in radians. See
  https://en.wikipedia.org/wiki/Horizontal_coordinate_system for
  details of how this plus `azimuth` tells us the location of the
  sun."
  [sun-declination latitude-radians sun-hour-angle]
  (Math/asin (+ (* (Math/sin sun-declination) (Math/sin latitude-radians))
                (* (Math/cos sun-declination) (Math/cos latitude-radians) (Math/cos sun-hour-angle)))))

(defn azimuth
  "The local azimuth of the sun in radians. See
  https://en.wikipedia.org/wiki/Horizontal_coordinate_system for
  details of how this plus `elevation` tells us the location of the
  sun."
  [sun-declination latitude-radians sun-hour-angle sun-elevation]
  (Math/atan2 (- (* (Math/cos sun-declination) (Math/cos latitude-radians) (Math/sin sun-hour-angle)))
              (- (Math/sin sun-declination) (* (Math/sin latitude-radians) (Math/sin sun-elevation)))))

(defn refraction-correction
  "Calculates the correction that needs to be applied to the sun's
  elevation due to refraction by the atmosphere, which makes it appear
  higher as it approaches the horizon. Unlike in other functions, this
  calculation is performed in degrees above the horizon rather than
  radians."
  [sun-elevation-degrees]
  (/ (/ 1.02
        (Math/tan (* (+ sun-elevation-degrees
                        (/ 10.3 (+ sun-elevation-degrees 5.11)))
                     radians-per-degree)))
     60.0))

(defn position
  "Calculate the position of the sun in the sky at the time specified by
  `t` as seen from the geographic location specified by `latitude` and
  `longitude`.

  Returns a map with keys `:azimuth`, `:elevation`, and `:refraction`.
  This identifies the apparent position of the sun using the usual
  horizontal coordinate system, described at
  https://en.wikipedia.org/wiki/Horizontal_coordinate_system with a
  basic adjustment for refraction by the atmosphere. The actual
  position of the sun can be calculated by subtracting the returned
  refraction correction from the reported elevation."
  [^Temporal t latitude longitude]
  (let [latitude-radians       (degrees-to-radians latitude)
        longitude-radians      (degrees-to-radians longitude)
        decimal-date           (decimal-days-since-2000 t)
        sun-mean-longitude     (mean-longitude decimal-date)
        sun-mean-anomaly       (mean-anomaly decimal-date)
        sun-ecliptic-longitude (ecliptic-longitude sun-mean-longitude sun-mean-anomaly)
        sun-obliquity          (obliquity-of-ecliptic decimal-date)
        sun-right-ascension    (right-ascension sun-ecliptic-longitude sun-obliquity)
        sun-declination        (declination sun-ecliptic-longitude sun-obliquity)
        local-sidereal-time    (sidereal-time decimal-date longitude-radians)
        sun-hour-angle         (hour-angle local-sidereal-time sun-right-ascension)
        sun-elevation          (elevation sun-declination latitude-radians sun-hour-angle)
        sun-azimuth            (azimuth sun-declination latitude-radians sun-hour-angle sun-elevation)
        elevation-degrees      (radians-to-degrees sun-elevation -180 180)
        refraction             (refraction-correction elevation-degrees)]
    {:elevation             (+ elevation-degrees refraction)
     :azimuth               (radians-to-degrees sun-azimuth)
     :refraction-correction refraction}))

(defn entering-windows?
  "Checks whether a given sun `position` means that windows facing the
  specified `:azimuth` are receiving sunlight. To simplify
  calculations, the sun's azimuth is normalized based on the window
  azimuth, so that if the sun is shining directly from where the
  windows are facing, the normalized azimuth is 90°. Returns truthy
  when the sun elevation is between `:horizon` (which defaults to 5.0,
  to catch sunset views when the sun is not throwing much heat) and
  `:ceiling` (which defaults to 85.0), and its normalized azimuth is
  between `:left` (which defaults to 5.0) and `:right` (which defaults
  to 175.0).

  Any of the boundaries can be adjusted if (as in our case) there are
  buildings or architectural features blocking the sun in some
  direction."
  [position {:keys [azimuth horizon ceiling left right] :or {horizon 5.0
                                                             ceiling 85.0
                                                             left    5.0
                                                             right   175.0}}]
  ;; First, normalize azimuths as if the windows are facing 90°, to
  ;; make later math easier.
  (let [offset           (- azimuth 90.0)
        relative-azimuth (normalize-to-range (- (:azimuth position) offset) 0.0 360.0)]
    (and (<= left relative-azimuth right)
         (<= horizon (:elevation position) ceiling))))


;; This last function is not actually used, but it is included for
;; completenes, for those who want to see how the decimal date could
;; be calculated before the `java.time` classes introduced in Java 8
;; made it much easier.

(defn book-decimal-days-since-2000
  "Given a Temporal object, calculate the distance in decimal days it
  represents from the start of January 1, 2000 in UTC. This
  calculation is accurate only from 1901 to 2099, and is the version
  present in the book. We can use the Julian date features in
  `java.time` to calculate it more easily and with a wider range of
  validity. (See `decimal-days-since-2000` above for our
  implementation.)"
  [^Temporal t]
  (let [day-1          (Math/floor (/ (+ (.get t ChronoField/MONTH_OF_YEAR) 9) 12))
        day-2          (Math/floor (/ (* 7 (+ (.get t ChronoField/YEAR) day-1)) 4))
        day-3          (Math/floor (/ (* 275 (.get t ChronoField/MONTH_OF_YEAR)) 9))
        day-4          (+ (- (* 367 (.get t ChronoField/YEAR)) day-2) day-3)]
    (- (+ day-4 (.get t ChronoField/DAY_OF_MONTH) (/ (decimal-hour-of-day-utc t) 24)) 730531.5)))
