;; SPDX-License-Identifier: MPL-2.0
(ns local.media
  (:require [app.common.uuid :as uuid]
            [clojure.string :as str]
            [local.store :as store]
            [next.jdbc :as jdbc])
  (:import io.undertow.server.handlers.form.FormParserFactory
           java.io.ByteArrayInputStream
           javax.imageio.ImageIO))

(def parser-factory (.build (FormParserFactory/builder)))
(def max-image-bytes (* 32 1024 1024))

(defn read-form [request]
  (with-open [parser (.createParser parser-factory (:exchange request))]
    (let [form (.parseBlocking parser)]
      (into {}
            (for [field form
                  :let [value (.getFirst form field)]]
              [(keyword field)
               (if (.isFileItem value)
                 (with-open [in (.getInputStream (.getFileItem value))]
                   (let [bytes (.readNBytes in (inc max-image-bytes))]
                     (when (> (alength bytes) max-image-bytes)
                       (store/fail 413 :file-too-large "Maximum image size is 32 MiB."))
                     {:bytes bytes :filename (.getFileName value)}))
                 (.getValue value))])))))

(defn- image-info [bytes]
  (with-open [input (ImageIO/createImageInputStream (ByteArrayInputStream. bytes))]
    (let [readers (ImageIO/getImageReaders input)]
      (when-not (.hasNext readers)
        (store/fail 415 :media-type-not-allowed "Use a PNG, JPEG or GIF image."))
      (let [reader (.next readers)]
        (try
          (.setInput reader input)
          (let [kind (str/lower-case (.getFormatName reader))]
            (when-not (#{"png" "jpeg" "gif"} kind)
              (store/fail 415 :media-type-not-allowed "Use a PNG, JPEG or GIF image."))
            {:mtype (str "image/" kind) :width (.getWidth reader 0) :height (.getHeight reader 0)})
          (finally (.dispose reader)))))))

(defn save-image [db {:keys [file-id content media name object-id]} preview?]
  (let [{:keys [bytes filename]} (or content media)
        _ (when-not bytes (store/fail 400 :missing-image "Image content is required."))
        info (image-info bytes)
        id (uuid/next)]
    (locking db
      (jdbc/with-transaction [tx db]
        (when-not (store/one tx ["SELECT id FROM documents WHERE id=? AND deleted IS NULL" (str file-id)])
          (store/fail 404 :object-not-found "The requested file does not exist."))
        (store/one tx ["INSERT INTO media(id,file_id,mime,bytes) VALUES(?,?,?,?)"
                       (str id) (str file-id) (:mtype info) bytes])
        (when preview?
          (let [key (or object-id "file")
                old (:media_id (store/one tx ["SELECT media_id FROM previews WHERE file_id=? AND object_id=?"
                                              (str file-id) key]))]
            (store/one tx ["INSERT INTO previews VALUES(?,?,?) ON CONFLICT(file_id,object_id)
                            DO UPDATE SET media_id=excluded.media_id" (str file-id) key (str id)])
            (when old (store/one tx ["DELETE FROM media WHERE id=?" old]))))))
    (if preview?
      id
      (assoc info :id id :media-id id :file-id (parse-uuid (str file-id))
             :name (or name filename "Image") :is-local true))))

(defn get-preview-ids [db id tag]
  (into {} (comp (remove #(= "file" (:object_id %)))
                 (filter #(or (nil? tag) (str/ends-with? (:object_id %) (str tag))))
                 (map (fn [r] [(:object_id r) (parse-uuid (:media_id r))])))
        (store/rows db ["SELECT object_id,media_id FROM previews WHERE file_id=?" (str id)])))
