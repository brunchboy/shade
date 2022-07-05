(ns shade.routes.websocket
  "Handles communication with the web socket that relayes queries and
  commands to the blind controller running on our home network."
  (:require [ring.adapter.undertow.websocket :as ws]))

(def channels-open
  "Keeps track of the channels associated with the open web sockets."
  (atom #{}))

(defn on-open
  "Called when a connection to the web socket is opened."
  [{:keys [channel]}]
  (println "Web socket opened!")
  (swap! channels-open conj channel))

(defn on-message
  "Called when a message is received from the web socket."
  [{:keys [channel data]}]
  (println "Received message, data:" data)
  (ws/send "message received" channel))

(defn on-close
  "Called when the web socket is closed."
  [{:keys [channel ws-channel]}]
  (println "Web socket closed!")
  (swap! channels-open disj ws-channel))

(defn on-error
  "Called when there is an error."
  [{:keys [channel error]}]
  (println "Web socket error:" error)
  (swap! channels-open disj channel))

(defn handler
  "The web socket handler."
  [_request]
  {:undertow/websocket
   {:on-open    on-open
    :on-message on-message
    :on-close   on-close
    :on-error   on-error}})

(defn websocket-routes []
  [["/ws" handler]])
