(ns dash-clojuredocs.core
  (:gen-class)
  (:require [clojure.string :as string]
            [pegasus.core :refer [crawl]]
            [pegasus.process :refer [PipelineComponentProtocol]]
            [pegasus.dsl :refer :all]
            [clojure.core.async :refer [chan alts! <! >! <!! go-loop go timeout pipeline]]
            [hickory.core :refer [parse as-hiccup]]
            [clojure.java.jdbc :as jdbc]
            [clojure.java.io :as io]
            [clj-http.client :as client]
            [clj-time.core :as t]
            [clj-time.coerce :as c]
            [selmer.parser :as parser]
            [me.raynes.fs :as fs]
            [clojure.core.match :refer [match]])
  (:import [java.util.zip GZIPInputStream]))

(def docset-dir "clojuredocs.docset")
(def content-dir (str docset-dir "/Contents/"))
(def document-dir (str content-dir "Resources/Documents/"))
(def tmp-dir "/tmp/clojuredocs/")

(io/make-parents (str document-dir "/styles/tomorrow.css"))
(io/copy (io/file "resources/highlight.pack.js")
         (io/file (str document-dir "/highlight.pack.js")))

(io/copy (io/file "resources/styles/tomorrow.css")
         (io/file (str document-dir "/styles/tomorrow.css")))

(io/copy (io/file "resources/Info.plist")
         (io/file (str content-dir "/Info.plist")))

(def db {:classname   "org.sqlite.JDBC"
         :subprotocol "sqlite"
         :subname (str docset-dir "/Contents/Resources/docSet.dsidx")})

(jdbc/execute! db ["drop table if exists searchIndex"])

(let [cs (jdbc/create-table-ddl :searchIndex
                               [[:id :integer
                                 :primary :key
                                 :autoincrement]
                                [:name :text]
                                [:type :text]
                                [:path :text]])]
  (jdbc/execute! db [cs]))

(defn surround-par [content]
  (str "(" content ")"))

(defn example-template [example-data]
  (parser/render-file "template.html" example-data))

(defn handle-string [origin-string]
  (-> origin-string
      str
      string/trim
      (string/replace #"\\n" "\r\n")))

(defn gen-example-markup [example]
  (update example :body handle-string))

(defn handle-var [item-var]
  (-> item-var
      (update :arglists (fn [value]
                          (->> value
                               (map #(surround-par
                                       (str (:name item-var)
                                            (when (not= % "")
                                              (str " " %)))))
                               (string/join " "))))
      (update :doc #(-> %
                        str
                        (string/replace #"\\n[ ]*" "\r\n")))))

(defn handle-content [item-map]
  (example-template (merge {:examples (map gen-example-markup
                                           (:examples item-map))}
                           (handle-var (:var item-map)))))

(defn map-source [item]
  (when-let [ret (get (re-find #"PAGE_DATA=\"(.*)\"" (:body item)) 1)]
    (let [item-map (-> ret
                       (string/replace #"(?<!\\\\)\\\"" "\"")
                       (string/replace #"\\\\\\\"" "\\\\\"")
                       read-string)
          item-ns (get-in item-map [:var :ns])
          item-name (get-in item-map [:var :name])
          item-type (get-in item-map [:var :type])
          item-name-for-file (string/replace item-name #"\?" "_qm")
          file-name (str item-ns "/" item-name-for-file ".html")
          file-content (handle-content item-map)
          full-file-path (str document-dir file-name)]
      (io/make-parents full-file-path)
      (spit full-file-path file-content)
      (jdbc/insert!
        db
        :searchIndex
        {:name (str item-ns "/" item-name)
         :type (match item-type
                      "var" "Variable"
                      "function" "Function"
                      "macro" "Macro"
                      :else "Section")
         :path file-name}))))

(defn handle-source
  []
  (with-open [in (-> (str tmp-dir "corpus/corpus.clj.gz")
                     io/input-stream
                     java.util.zip.GZIPInputStream.)]
    (let [items (-> in
                    slurp
                    surround-par
                    read-string
                    rest)]
      (doseq [item items]
        (map-source item)))))

(defn get-request
  ([url user-agent]
   (get-request url
                user-agent
                1000
                1000))

  ([url user-agent socket-timeout conn-timeout]
   (client/get url {:socket-timeout socket-timeout
                    :conn-timeout conn-timeout
                    :headers {"User-Agent" user-agent}
                    :insecure? true})))

(defn request-with-retry [f retry-count & args]
  (loop [retry-count retry-count
         ret ""]
    (cond
      (or (not= "" ret)
          (zero? retry-count))
      ret

      :else
      (recur (dec retry-count)
             (try
               (:body (apply get-request args))
               (catch Exception e
                 (do
                   (println "request exception"
                            (first args) (.getMessage e))
                   "")))))))

(deftype CustomFrontierPipelineComponent []
  PipelineComponentProtocol

  (initialize
    [this config]
    config)

  (run
    [this url config]
    {:url  url
     :body (request-with-retry get-request
                               5
                               url
                               (:user-agent config)
                               (:socket-timeout config)
                               (:conn-timeout config))
     :time (-> (t/now)
               c/to-long)})

  (clean
    [this config]
    nil))

(defn crawl-clojuredocs
  []
  (fs/delete-dir tmp-dir)
  (let [config (crawl {:seeds ["http://clojuredocs.org/core-library/vars"]
                       :user-agent "dash crawler"
                       :extractor (defextractors
                                    (extract
                                      :at-selector [:ul.var-list :a]
                                      :follow :href))
                       :frontier (->CustomFrontierPipelineComponent)
                       :corpus-size 1395
                       :min-delay-ms 100
                       :job-dir tmp-dir})]
    (while (not (:stop? @(:state config)))
      (Thread/sleep 1000))
    (handle-source)))

(defn -main
  []
  (crawl-clojuredocs))
