;; SPDX-License-Identifier: MPL-2.0
(ns local.store-test
  (:require [app.common.uuid :as uuid]
            [clojure.test :refer [deftest is run-tests]]
            [local.media :as media]
            [local.server :as server]
            [local.store :as store])
  (:import java.nio.file.Files
           java.util.Base64))

(defn with-workspace [f]
  (let [path (.toFile (Files/createTempFile "penpot-local-" ".sqlite"
                                            (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (f (store/open-store (.getPath path)) (.getPath path))
      (finally (Files/deleteIfExists (.toPath path))))))

(deftest document-survives-reopen-and-retry
  (with-workspace
    (fn [db path]
      (let [{:keys [project]} (store/get-setting db :ids)
            id (store/create-document db project "Saved locally")
            page (uuid/next)
            params {:id id :vern 0 :revn 0 :session-id (uuid/next) :commit-id (uuid/next)
                    :changes [{:type :add-page :id page :name "Second page"}]}
            result (store/save-changes db project params)
            reopened (store/open-store path)]
        (is (= {:revn 1 :lagged []} result))
        (is (= result (store/save-changes reopened project params)))
        (is (= "Second page" (get-in (store/get-document reopened project id)
                                     [:data :pages-index page :name])))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Another window"
                              (store/save-changes reopened project (assoc params :commit-id (uuid/next)))))
        (is (= 1 (:revn (store/get-document reopened project id))))
        (is (= 2 (count (store/list-files reopened project))))
        (let [retry (assoc params :revn 1 :commit-id (uuid/next))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"change vector"
                                (store/save-changes reopened project (dissoc retry :changes))))
          (is (= 1 (:revn (store/get-document reopened project id))))
          (is (= 2 (:revn (store/save-changes reopened project (assoc retry :changes []))))))
        (store/rename-document reopened id "Renamed")
        (is (= "Renamed" (:name (store/get-document reopened project id))))))))

(deftest image-survives-reopen-and-preview-replacement
  (with-workspace
    (fn [db path]
      (let [project (:project (store/get-identity db))
            file-id (:id (first (store/list-files db project)))
            png (.decode (Base64/getDecoder)
                         "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/lWQAAAAASUVORK5CYII=")
            form {:file-id file-id :object-id "board/frame"
                  :content {:bytes png :filename "sample.png"}}
            image (media/save-image db form false)
            old (media/save-image db form true)
            current (media/save-image db form true)
            reopened (store/open-store path)]
        (is (= ["image/png" 1 1 "sample.png"] ((juxt :mtype :width :height :name) image)))
        (is (= {"board/frame" current} (media/get-preview-ids reopened file-id "frame")))
        (is (nil? (store/one reopened ["SELECT id FROM media WHERE id=?" (str old)])))
        (is (= (seq png) (seq (:bytes (store/one reopened ["SELECT bytes FROM media WHERE id=?"
                                                           (str (:id image))])))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"PNG, JPEG or GIF"
                              (media/save-image db (assoc-in form [:content :bytes] (byte-array [1 2 3])) false)))
        (is (= 2 (:n (store/one reopened ["SELECT count(*) AS n FROM media"]))))))))

(deftest renderer-preference-survives-reopening
  (with-workspace
    (fn [db path]
      (let [ids (store/get-identity db)]
        (server/rpc db ids "update-profile-props" {:props {:renderer :wasm :other true}})
        (let [reopened (store/open-store path)]
          (is (= :wasm (get-in (server/rpc reopened ids "get-profile" {}) [:props :renderer])))
          (server/rpc reopened ids "update-profile-props" {:props {:renderer nil}})
          (let [props (:props (server/rpc reopened ids "get-profile" {}))]
            (is (not (contains? props :renderer)))
            (is (true? (:other props)))))))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'local.store-test)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
