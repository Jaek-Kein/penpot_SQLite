;; SPDX-License-Identifier: MPL-2.0
(ns local.store
  (:require [app.common.features :as features]
            [app.common.files.changes :as changes]
            [app.common.files.defaults :as defaults]
            [app.common.transit :as transit]
            [app.common.types.file :as file]
            [app.common.uuid :as uuid]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import java.time.Instant))

(def options {:builder-fn rs/as-unqualified-lower-maps})
(def permissions {:type :membership :is-owner true :is-admin true
                  :can-edit true :can-read true :is-logged true})

(defn fail [status code message]
  (throw (ex-info message {:status status :type :validation :code code :hint message})))

(defn one [conn sql] (jdbc/execute-one! conn sql options))
(defn rows [conn sql] (jdbc/execute! conn sql options))

(defn get-setting [conn k]
  (some-> (one conn ["SELECT value FROM settings WHERE name=?" (name k)]) :value transit/decode-str))

(defn put-setting [conn k v]
  (one conn ["INSERT INTO settings(name,value) VALUES(?,?)
              ON CONFLICT(name) DO UPDATE SET value=excluded.value" (name k) (transit/encode-str v)])
  v)

(defn create-document [conn project-id title]
  (when (or (not (string? title)) (str/blank? title) (> (count title) 250))
    (fail 400 :invalid-name "Enter a file name of 1 to 250 characters."))
  (let [f (file/make-file {:id (uuid/next) :project-id project-id :name title
                           :features features/default-features})
        ms (System/currentTimeMillis)]
    (one conn ["INSERT INTO documents(id,name,revn,created,modified,body)
                VALUES(?,?,0,?,?,?)" (str (:id f)) title ms ms (transit/encode-str (:data f))])
    (:id f)))

(defn open-store [path]
  (let [db (jdbc/get-datasource {:jdbcUrl (str "jdbc:sqlite:" path)})]
    (one db ["PRAGMA journal_mode=WAL"])
    (jdbc/with-transaction [tx db]
      (case (:user_version (one tx ["PRAGMA user_version"]))
        0 (do
            (one tx ["CREATE TABLE settings(name TEXT PRIMARY KEY, value TEXT NOT NULL)"])
            (one tx ["CREATE TABLE documents(id TEXT PRIMARY KEY, name TEXT NOT NULL,
                      revn INTEGER NOT NULL, created INTEGER NOT NULL, modified INTEGER NOT NULL,
                      deleted INTEGER, body TEXT NOT NULL)"])
            (one tx ["CREATE TABLE saves(file_id TEXT NOT NULL, request TEXT NOT NULL,
                      revision INTEGER NOT NULL, PRIMARY KEY(file_id,request))"])
            (one tx ["CREATE TABLE media(id TEXT PRIMARY KEY, file_id TEXT NOT NULL,
                      mime TEXT NOT NULL, bytes BLOB NOT NULL)"])
            (one tx ["CREATE TABLE previews(file_id TEXT NOT NULL, object_id TEXT NOT NULL,
                      media_id TEXT NOT NULL, PRIMARY KEY(file_id,object_id))"])
            (let [ids {:profile (uuid/next) :team (uuid/next) :project (uuid/next)
                       :created-at (Instant/now)}]
              (put-setting tx :ids ids)
              (create-document tx (:project ids) "Untitled"))
            (one tx ["PRAGMA user_version=1"]))
        1 nil
        (fail 409 :unsupported-schema "This workspace requires a newer application.")))
    db))

(defn get-identity [db]
  (let [ids (get-setting db :ids)]
    (if (:created-at ids)
      ids
      (let [first-file (:ts (one db ["SELECT min(created) AS ts FROM documents"]))]
        (assoc ids :created-at (Instant/ofEpochMilli (or first-file (System/currentTimeMillis))))))))

(defn- summary [project r]
  {:id (parse-uuid (:id r)) :project-id project :name (:name r) :revn (:revn r) :vern 0
   :version defaults/version :features features/default-features :is-shared false
   :permissions permissions :created-at (Instant/ofEpochMilli (:created r))
   :modified-at (Instant/ofEpochMilli (:modified r))})

(defn list-files [db project]
  (mapv (partial summary project)
        (rows db ["SELECT id,name,revn,created,modified FROM documents
                   WHERE deleted IS NULL ORDER BY modified DESC, id"])))

(defn get-document [conn project id]
  (if-let [r (one conn ["SELECT * FROM documents WHERE id=? AND deleted IS NULL" (str id)])]
    (assoc (summary project r) :data (transit/decode-str (:body r)))
    (fail 404 :object-not-found "The requested file does not exist.")))

(defn save-changes [db project {:keys [id revn vern session-id commit-id changes-with-metadata]
                                :as params}]
  ;; Old editors omit the commit UUID. A replay then carries the same session,
  ;; base revision and operations. Hash the payload instead of storing it twice.
  (let [items (if (some? changes-with-metadata)
                (into [] (mapcat :changes) changes-with-metadata)
                (:changes params))
        request (str (or commit-id
                         (java.util.UUID/nameUUIDFromBytes
                          (transit/encode [session-id revn vern items]))))]
    (locking db
      (jdbc/with-transaction [tx db]
        (if-let [receipt (one tx ["SELECT revision FROM saves WHERE file_id=? AND request=?"
                                  (str id) request])]
          {:revn (:revision receipt) :lagged []}
          (let [f (get-document tx project id)]
            (when-not (and (= revn (:revn f)) (= (or vern 0) (:vern f)))
              (fail 409 :revn-conflict "Another window saved this file. Reopen it before editing."))
            (when-not (vector? items)
              (fail 400 :invalid-changes "The save request must contain a change vector."))
            (let [data (changes/process-changes (:data f) items)
                  next-revn (inc revn)]
              (one tx ["UPDATE documents SET body=?,revn=?,modified=? WHERE id=?"
                       (transit/encode-str data) next-revn (System/currentTimeMillis) (str id)])
              (one tx ["INSERT INTO saves(file_id,request,revision) VALUES(?,?,?)"
                       (str id) request next-revn])
              {:revn next-revn :lagged []})))))))

(defn rename-document [db id title]
  (when (or (not (string? title)) (str/blank? title) (> (count title) 250))
    (fail 400 :invalid-name "Enter a file name of 1 to 250 characters."))
  (when (zero? (:next.jdbc/update-count
                (one db ["UPDATE documents SET name=?,modified=? WHERE id=? AND deleted IS NULL"
                         title (System/currentTimeMillis) (str id)])))
    (fail 404 :object-not-found "The requested file does not exist."))
  nil)
