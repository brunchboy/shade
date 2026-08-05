(ns shade.home-assistant
  "Communicates with the Home Assistant REST API to learn about and
  manipulate shade states."
  (:require [shade.config :refer [env]]
            [clj-http.client :as client]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]))

(defn fetch-shade-cover-state
  "Requests current information about a particular shade from Home Assistant."
  [shade-id]
  (let [url (str (:home-assistant-url env) "states/cover." shade-id "_cover")]
    (-> (client/get url {:oauth-token (:home-assistant-auth env)})
        :body
        (json/read-str))))

(defn format-shade-cover-attributes
  "Converts the shade cover state returned by Home Assistant to the
  format we use."
  [shade]
  {:moving? (boolean (#{"opening" "closing"} (get shade "state")))
   :level   (get-in shade ["attributes" "current_position"])})

(defn fetch-shade-battery-state
  "Requests current information about a particular shade's battery from
  Home Assistant."
  [shade-id]
  (let [url (str (:home-assistant-url env) "states/sensor." shade-id "_battery")]
    (-> (client/get url {:oauth-token (:home-assistant-auth env)})
        :body
        (json/read-str))))

(defn format-shade-battery-attributes
  "Converts the shade battery state returned by Home Assistant to the
  format we use."
  [shade]
  {:battery-level (double (get shade "state"))})
