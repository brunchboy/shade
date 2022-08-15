(ns shade.middleware
  (:require
    [shade.env :refer [defaults]]
    [clojure.tools.logging :as log]
    [shade.layout :refer [error-page]]
    [ring.middleware.anti-forgery :refer [wrap-anti-forgery]]
    [shade.middleware.formats :as formats]
    [muuntaja.middleware :refer [wrap-format wrap-params]]
    [shade.config :refer [env]]
    [shade.db.core :as db]
    [ring.middleware.flash :refer [wrap-flash]]
    [ring.middleware.defaults :refer [site-defaults wrap-defaults]]
    [ring.util.response :refer [redirect]]
    [jdbc-ring-session.core :refer [jdbc-store]]
    [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
    [buddy.auth.accessrules :refer [wrap-access-rules]]
    [buddy.auth :refer [authenticated?]]
    [buddy.auth.backends.session :refer [session-backend]]))

(defn wrap-internal-error [handler]
  (fn [req]
    (try
      (handler req)
      (catch Throwable t
        (log/error t (.getMessage t))
        (error-page {:status  500
                     :title   "Something very bad has happened!"
                     :message "We've dispatched a team of highly trained gnomes to take care of the problem."})))))

(defn wrap-csrf [handler]
  (wrap-anti-forgery
    handler
    {:error-response
     (error-page
      {:status 403
       :title  "Invalid anti-forgery token"})}))


(defn wrap-formats [handler]
  (let [wrapped (-> handler wrap-params (wrap-format formats/instance))]
    (fn [request]
      ;; disable wrap-formats for websockets
      ;; since they're not compatible with this middleware
      ((if (:websocket? request) handler wrapped) request))))

(defn on-error
  "Authorization failure handler for protected pages."
  [request _response]
  (if (authenticated? request)
    ;; If the request was authenticated, raise a 403, because the user is logged in but they do not
    ;; have permission to access this specific route.
    (error-page
     {:status 403
      :title  (str "Access to " (:uri request) " is not authorized")})
    ;; Otherwise, they are not logged in, so simply redirect them to the login page.
    (let [current-url (:uri request)]
      (redirect (format "/login?next=%s" current-url)))))

(defn active?
  "Checks that the user making the request is both authenticated, and
  marked as active."
  [request]
  (when (authenticated? request)
    (boolean (:is_active (db/get-user (:identity request))))))

(defn admin?
  "Checks that the user is authenticated, active, and an administrator."
  [request]
  (and (active? request)
       (get-in request [:identity :admin])))

(def rules
  "The access rules which control user access to routes."
  [{:uri     "/assets/*"  ; Public assets can always be accessed.
    :handler (constantly true)}
   {:uri     "/css/*"  ; Public CSS can always be accessed.
    :handler (constantly true)}
   {:uri     "/img/*"  ; Public images can always be accessed.
    :handler (constantly true)}
   {:uri     "/login" ; Login page can always be accessed.
    :handler (constantly true)}
   {:uri     "/logout" ; Logout page can always be accessed.
    :handler (constantly true)}
   {:uri     "/about" ; About page can always be accessed.
    :handler (constantly true)}
   {:uri     "/" ; The home page only requires a valid login.
    :handler authenticated?}
   {:uri     "/profile" ; The profile page also only requries a valid login.
    :handler authenticated?}
   {:uri     "/admin/*" ; Administrative pages require administrator privileges.
    :handler admin?}
   {:uri     "/set-shade-levels" ; Setting arbitrary shade levels also requires administrator privileges.
    :handler admin?}
   {:uri     "/ws" ; Need special header to open the web socket.
    :handler (fn [request] (= (get-in request [:headers "x-shade-token"]) (env :websocket-token)))}
   {:uri     "*" ; Everything else needs an active and valid login to access.
    :handler active?}])

(defn wrap-auth [handler]
  (let [backend (session-backend)]
    (-> handler
        (wrap-access-rules {:rules rules :on-error on-error})
        (wrap-authentication backend)
        (wrap-authorization backend))))

(defn wrap-base [handler]
  (-> ((:middleware defaults) handler)
      wrap-auth
      wrap-flash
      (wrap-defaults (-> site-defaults
                         ;; Anti-forgery is already active from somewhere, turning it on again breaks everything.
                         (assoc-in [:security :anti-forgery] false)
                         (assoc-in [:session :store] (jdbc-store db/*db*))
                         (assoc-in [:session :timeout] Integer/MAX_VALUE)
                         (assoc-in [:session :cookie-attrs] {:max-age   Integer/MAX_VALUE
                                                             :http-only false
                                                             :same-site :strict})))
      wrap-internal-error))
