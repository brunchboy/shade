(ns shade.sun-test
  (:require [shade.sun :as sun]
            [clojure.test :as t])
  (:import (java.time ZonedDateTime ZoneId)
           (java.time.temporal ChronoField)
           (java.util.concurrent TimeUnit)))

(t/deftest test-decimal-degrees
  (t/testing "decimal-degrees"
    (t/is (= 43.07555555555556 (sun/decimal-degrees 43 4 32)))))

(def sample-time
  "Specifies the exact point in time used by the examples from the book,
  so we can test our calculations against the sample results."
  (ZonedDateTime/of 2019 12 11 10 9 8 0 (ZoneId/of "America/Denver")))

(def sample-latitude
  "The latitude used by the examples in the book."
  40.6028)

(def sample-longitude
  "The latitude used by the examples in the book."
  -104.7417)

(t/deftest test-inputs
  (t/testing "sample input values in sample-calendar"
    (t/is (= 2019 (.get sample-time ChronoField/YEAR)))
    (t/is (= 2019 (.getYear sample-time)))
    (t/is (= 12 (.get sample-time ChronoField/MONTH_OF_YEAR)))
    (t/is (= 12 (.getValue (.getMonth sample-time))))
    (t/is (= 11 (.get sample-time ChronoField/DAY_OF_MONTH)))
    (t/is (= 11 (.getDayOfMonth sample-time)))
    (t/is (= 10 (.get sample-time ChronoField/HOUR_OF_DAY)))
    (t/is (= 10 (.getHour sample-time)))
    (t/is (= 9 (.get sample-time ChronoField/MINUTE_OF_HOUR)))
    (t/is (= 9 (.getMinute sample-time)))
    (t/is (= 8 (.get sample-time ChronoField/SECOND_OF_MINUTE)))
    (t/is (= 8 (.getSecond sample-time)))
    (t/is (= (.toSeconds (TimeUnit/HOURS) -7) (.getTotalSeconds (.getOffset sample-time))))))

(t/deftest test-day-and-year
  (t/testing "decimal-hour-of-day-utc"
    (t/is (= 17.15222222222222 (sun/decimal-hour-of-day-utc sample-time))))
  (t/testing "decimal-days-since-j2000"
    (t/is (= 7284.214675925926 (sun/decimal-days-since-2000 sample-time)))
    (t/is (= 7284.214675925905 (sun/book-decimal-days-since-2000 sample-time)))))

(t/deftest test-sun-partials
  (let [decimal-date          (sun/decimal-days-since-2000 sample-time)
        mean-longitude        (sun/mean-longitude decimal-date)
        mean-anomaly          (sun/mean-anomaly decimal-date)
        ecliptic-longitude    (sun/ecliptic-longitude mean-longitude mean-anomaly)
        obliquity             (sun/obliquity-of-ecliptic decimal-date)
        right-ascension       (sun/right-ascension ecliptic-longitude obliquity)
        declination           (sun/declination ecliptic-longitude obliquity)
        sidereal-time         (sun/sidereal-time decimal-date (sun/degrees-to-radians sample-longitude))
        hour-angle            (sun/hour-angle sidereal-time right-ascension)
        elevation             (sun/elevation declination (sun/degrees-to-radians sample-latitude) hour-angle)
        azimuth               (sun/azimuth declination (sun/degrees-to-radians sample-latitude) hour-angle elevation)
        refraction-correction (sun/refraction-correction (sun/radians-to-degrees elevation -180 180))]
    (t/testing "mean-longitude"
      (t/is (= 4.540094523553108 mean-longitude)))
    (t/testing "mean-anomaly"
      (t/is (= 5.879179429878775 mean-anomaly)))
    (t/testing "ecliptic-longitude"
      (t/is (= 4.5267034130623935 ecliptic-longitude)))
    (t/testing "obliquity-of-ecliptic"
      (t/is (= 0.40903686998819305 obliquity)))
    (t/testing "right-ascension"
      (t/is (= -1.772745058241749 right-ascension)))
    (t/testing "declination"
      (t/is (= -0.40159712492436245 declination)))
    (t/testing "local sidereal time"
      (t/is (= 4.060844809006085 sidereal-time)))
    (t/testing "hour angle"
      (t/is (= 5.833589867247834 hour-angle)))
    (t/testing "elevation"
      (t/is (= 0.3843859505645618 elevation))
      (t/is (= 22.02369267147367 (sun/radians-to-degrees elevation -180 180))))
    (t/testing "azimmuth"
      (t/is (= 2.6954252683481297 azimuth))
      (t/is (= 154.43649186926518 (sun/radians-to-degrees azimuth))))
    (t/testing "refraction-correction"
      (t/is (= 0.04123836107091465 refraction-correction)))))

(t/deftest test-position
  (t/is (= {:elevation 22.06493103254458
            :azimuth   154.43649186926518
            :refraction-correction 0.04123836107091465}
           (sun/position sample-time sample-latitude sample-longitude))))

(def latitude
  "The latitude of the Deep Symmetry headquarters."
  (sun/decimal-degrees 43 4 32))

(def longitude
  "The longitude of the Deep Symmetry headquarters."
  (- (sun/decimal-degrees 89 23 10)))

(def dayton-azimuth
  "The azimuth of our windows facing Dayton Street"
  326)

(def carroll-azimuth
  "The azimuth of our windows facing Carroll Street"
  236)

(defn- enter-test
  "Run entering-windows? function for windows at a particular azimuth,
  tweaking desired time values"
  [azimuth {:keys [year month day hour minute horizon] :or {year    2022
                                                            month   7
                                                            day     17
                                                            hour    15
                                                            minute  49
                                                            horizon 5}}]
  (sun/entering-windows? (sun/position (ZonedDateTime/of year month day hour minute 0 0 (ZoneId/of "America/Chicago"))
                                       latitude longitude)
                         {:azimuth azimuth
                          :horizon horizon}))

(t/deftest test-entering-windows
  (t/testing "entering-windows?"
    (t/is (enter-test dayton-azimuth {}))
    (t/is (enter-test carroll-azimuth {}))
    (t/is (not (enter-test 13 {})))
    (t/is (not (enter-test dayton-azimuth {:hour 12})))
    (t/is (not (enter-test dayton-azimuth {:hour 14})))
    (t/is (not (enter-test carroll-azimuth {:hour 11})))
    (t/is (not (enter-test carroll-azimuth {:hour 11
                                            :minute 30})))
    (t/is (enter-test carroll-azimuth {:hour 14}))
    (t/is (enter-test carroll-azimuth {:hour 19}))
    (t/is (enter-test dayton-azimuth {:hour 19}))
    (t/is (not (enter-test dayton-azimuth {:hour 19 :horizon 19})))))


;; Temporary stuff to be removed once the database holds shade bank definitions for sun control.

(defn day
  "Prints positions throughout the day for sanity checking."
  []
  (let [utc   (ZonedDateTime/now (ZoneId/of "UTC"))
        local (ZonedDateTime/now)]
    (println utc)
    (doseq [hour (range 24)]
      (let [local-then (.plusHours local hour)]
        (println local-then)
        (println (sun/position local-then latitude longitude))
        (println)))))
