;; SPDX-License-Identifier: MPL-2.0
(ns local.server
  (:require [app.common.features :as features]
            [app.common.transit :as transit]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [local.media :as media]
            [local.store :as store]
            [yetti.adapter :as http]
            [yetti.request :as request]
            [yetti.websocket :as ws])
  (:import java.net.URLDecoder
           java.nio.charset.StandardCharsets))

(defn response [status mime body]
  {:status status :headers {"content-type" mime "x-content-type-options" "nosniff"} :body body})

(defn transit-response [status value]
  (assoc-in (response status "application/transit+json" (transit/encode-str value))
            [:headers "cache-control"] "no-store"))

(defn get-profile [db {:keys [profile team project created-at]}]
  {:id profile :default-team-id team :default-project-id project :fullname "Local Designer"
   :email "designer@localhost.invalid" :is-active true :is-blocked false
   :auth-backend "local" :created-at created-at :modified-at created-at
   :props (merge {:onboarding-viewed true :onboarding-questions-answered true
                  :viewed-tutorial? true :viewed-walkthrough? true :v2-info-shown true}
                 (store/get-setting db :profile-props))})

(defn get-team [{:keys [team project created-at]}]
  {:id team :name "Local workspace" :default-project-id project :is-default true
   :features features/default-features :permissions store/permissions
   :created-at created-at :modified-at created-at})

(defn get-project [{:keys [team project created-at]}]
  {:id project :team-id team :name "Drafts" :is-default true :is-pinned true
   :permissions store/permissions :created-at created-at :modified-at created-at})

(defn get-files [db project]
  (let [previews (into {} (map (juxt :file_id :media_id))
                       (store/rows db ["SELECT file_id,media_id FROM previews WHERE object_id='file'"]))]
    (mapv (fn [f]
            (if-let [id (get previews (str (:id f)))]
              (assoc f :thumbnail-id (parse-uuid id))
              f))
          (store/list-files db project))))

(defn update-profile-props [db props]
  (when-not (map? props) (store/fail 400 :invalid-props "Profile preferences must be a map."))
  (locking db
    (let [previous (or (store/get-setting db :profile-props) {})
          updated (reduce-kv (fn [result k v]
                               (cond
                                 (not (simple-keyword? k)) result
                                 (nil? v) (dissoc result k)
                                 :else (assoc result k v)))
                             previous props)]
      (store/put-setting db :profile-props updated))))

(defn rpc [db ids cmd {:keys [id file-id name tag] :as params}]
  (let [project (:project ids)]
    (case cmd
      "get-environment-data" {:deployment "selfhost" :flags #{}}
      "get-profile" (get-profile db ids)
      "get-teams" [(get-team ids)]
      "get-team" (get-team ids)
      "get-projects" [(assoc (get-project ids) :count
                             (:n (store/one db ["SELECT count(*) AS n FROM documents WHERE deleted IS NULL"])))]
      "get-project" (get-project ids)
      ("get-team-members" "get-team-users" "get-profiles-for-file-comments")
      [(assoc (get-profile db ids) :is-owner true :is-admin true :can-edit true :role :owner)]
      ("get-project-files" "get-team-recent-files") (get-files db project)
      "search-files" (filterv #(str/includes? (str/lower-case (:name %))
                                              (str/lower-case (or (:search-term params) "")))
                              (get-files db project))
      "get-file" (store/get-document db project id)
      "get-file-summary" (dissoc (store/get-document db project id) :data)
      "create-file" (let [new-id (store/create-document db project name)]
                      (dissoc (store/get-document db project new-id) :data))
      "rename-file" (store/rename-document db id name)
      "update-file" (store/save-changes db project params)
      "update-profile-props" (update-profile-props db (:props params))
      "upload-file-media-object" (media/save-image db params false)
      ("create-file-object-thumbnail" "create-file-thumbnail") (media/save-image db params true)
      "get-file-object-thumbnails" (media/get-preview-ids db file-id tag)
      "get-file-data-for-thumbnail"
      (let [f (store/get-document db project file-id)
            p (get-in f [:data :pages-index (first (get-in f [:data :pages]))])]
        {:file-id (:id f) :revn (:revn f) :page p})
      ("get-team-font-variants" "get-font-variants" "get-file-libraries" "get-team-shared-files"
                                "get-comment-threads" "get-unread-comment-threads" "get-team-invitations" "get-webhooks"
                                "get-builtin-templates" "get-team-deleted-files" "get-file-snapshots") []
      ("get-profile-notifications" "get-access-tokens") []
      (store/fail 501 :unsupported-local-operation (str "Unavailable in the local prototype: " cmd)))))

(defn query-params [query]
  (into {}
        (for [pair (remove str/blank? (str/split (or query "") #"&"))
              :let [[k v] (str/split pair #"=" 2)]]
          [(keyword (URLDecoder/decode k StandardCharsets/UTF_8))
           (URLDecoder/decode (or v "") StandardCharsets/UTF_8)])))

(defn- request-params [req]
  (let [query (query-params (request/query req))
        mime  (or (request/get-header req "content-type") "")]
    (merge query
           (cond
             (str/starts-with? mime "application/transit+json")
             (transit/decode-str (slurp (request/body-stream req) :encoding "UTF-8"))
             (str/starts-with? mime "multipart/form-data") (media/read-form req)
             :else nil))))

(def media-types
  {"html" "text/html; charset=utf-8" "js" "text/javascript" "mjs" "text/javascript"
   "css" "text/css" "json" "application/json" "wasm" "application/wasm"
   "svg" "image/svg+xml" "png" "image/png" "jpg" "image/jpeg" "webp" "image/webp"
   "woff2" "font/woff2" "woff" "font/woff" "ttf" "font/ttf"})

(defn static-response [root path]
  (let [name (if (= path "/") "index.html" (subs path 1))
        target (.normalize (.resolve root name))]
    (if (and (.startsWith target root) (.isFile (.toFile target)))
      (response 200 (get media-types (last (str/split name #"\.")) "application/octet-stream")
                (.toFile target))
      (response 404 "text/plain" "Not found"))))

(defn handler [db ids root port]
  (let [host (str "127.0.0.1:" port)
        origin (str "http://" host)]
    (fn [req]
      (let [path (request/path req)
            method (request/method req)
            caller (request/get-header req "origin")]
        (try
          (cond
            (or (not= host (request/get-header req "host"))
                (and caller (not= origin caller))
                (= "cross-site" (request/get-header req "sec-fetch-site")))
            (response 403 "text/plain" "Local requests only")

            (= path "/local/health") (response 200 "text/plain" "penpot-sqlite/1")
            (= path "/local/open")
            (let [f (first (store/list-files db (:project ids)))
                  url (if f (str "/?screen=workspace&team-id=" (:team ids) "&file-id=" (:id f))
                          (str "/?screen=dashboard-projects&team-id=" (:team ids)))]
              {:status 302 :headers {"location" url "cache-control" "no-store"}})

            (= path "/js/config.js")
            (response 200 "text/javascript"
                      (str "var penpotFlags='disable-registration disable-telemetry disable-onboarding "
                           "disable-mcp disable-admin-console enable-air-gapped-conf disable-google-fonts-provider';"))

            (str/starts-with? path "/api/main/methods/")
            (let [cmd (subs path (count "/api/main/methods/"))]
              (when (and (not= :post method)
                         (not (and (= :get method) (or (str/starts-with? cmd "get-") (= cmd "search-files")))))
                (store/fail 405 :method-not-allowed "Use POST for changes."))
              (transit-response 200 (rpc db ids cmd (request-params req))))

            (= path "/ws/notifications")
            (if (and (= caller origin) (ws/upgrade-request? req))
              {::ws/listener {}}
              (response 400 "text/plain" "Expected a local WebSocket"))

            (re-matches #"/assets/(?:by-id|by-file-media-id)/[a-fA-F0-9-]+(?:/thumbnail)?" path)
            (if-let [item (store/one db ["SELECT mime,bytes FROM media WHERE id=?"
                                         (nth (str/split path #"/") 3)])]
              (response 200 (:mime item) (:bytes item))
              (response 404 "text/plain" "Image not found"))

            (#{:get :head} method) (static-response root path)
            :else (response 405 "text/plain" "Method not allowed"))
          (catch Exception e
            (let [details (ex-data e)
                  status (or (:status details) 500)]
              (binding [*out* *err*] (println method path status (ex-message e))
                       (when (>= status 500) (when-not (= status 501) (.printStackTrace e))))
              (transit-response status (if (:code details) details
                                           {:type :internal :code :local-request-failed})))))))))

(defn -main [db-path web-root port-text & [owner-mode]]
  (let [port (parse-long port-text)
        db (store/open-store db-path)
        ids (store/get-identity db)
        root (.toRealPath (.toPath (io/file web-root)) (make-array java.nio.file.LinkOption 0))
        server (http/server (handler db ids root port)
                            {:http/host "127.0.0.1" :http/port port
                             :http/max-body-size (* 40 1024 1024)
                             :http/max-multipart-body-size (* 40 1024 1024)
                             :xnio/io-threads 2})]
    (http/start! server)
    (println (str "Local Penpot is ready at http://127.0.0.1:" port "/local/open"))
    (if (= "--owner-stdin" owner-mode)
      (try (.read System/in) (finally (http/stop! server)))
      (do (.addShutdownHook (Runtime/getRuntime) (Thread. #(http/stop! server)))
          @(promise)))))
