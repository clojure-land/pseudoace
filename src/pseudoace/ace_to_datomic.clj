(ns pseudoace.ace-to-datomic
  (:require
   [acetyl.parser :as ace]
   [clojure.data :refer (diff)]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.pprint :as pp]
   [clojure.pprint :refer (pprint)]
   [clojure.repl :refer (doc)]
   [clojure.set :refer (difference)]
   [clojure.string :as str]
   [clojure.test :as t]
   [clojure.tools.cli :refer (parse-opts)]
   [datomic.api :as datomic]
   [pseudoace.import :as old-import]
   [pseudoace.locatable-import :as loc-import]
   [pseudoace.locatable-schema :refer (locatable-schema locatable-extras)]
   [pseudoace.metadata-schema :refer (basetypes metaschema)]
   [pseudoace.model :as model]  
   [pseudoace.model2schema :as model2schema]
   [pseudoace.schema-datomic :as schema-datomic]
   [pseudoace.ts-import :as ts-import]
   [pseudoace.utils :as utils]
   [pseudoace.wormbase-schema-fixups :refer (schema-fixups)])
  (:import
   (java.lang.Runtime)
   (java.lang.Runtimea)
   (java.net InetAddress)
   (java.io.FileInputStream)
   (java.io.File)
   (java.util.zip.GZIPInputStream))
  (:gen-class))

;; First three strings describe a short-option, long-option with optional
;; example argument description, and a description. All three are optional
;; and positional.
(def cli-options
  [[nil
    "--model PATH"
    (str "Specify the model file that you would "
         "like to use that is found in the models folder "
         "e.g. \"models.wrm.WS250.annot\"")]
    [nil
     "--url URL"
     (str "URL of the dataomic transactor; "
          "Example: datomic:free://localhost:4334/WS250")]
   [nil
    "--schema-filename PATH"
    (str "Name of the file for the schema view "
         "to be written; "
         "Example: \"schema250.edn\"")]
   [nil
    "--log-dir PATH"
    (str "Path to an empty directory to store the Datomic logs in; "
         "Example: /datastore/datomic/tmp/datomic/import-logs-WS250/")]
   [nil
    "--acedump-dir PATH"
    (str "Path to the directory of the desired acedump; "
         "Example: /datastore/datomic/tmp/acedata/WS250/")]
   [nil
    "--backup-file PATH"
    "Path to store the database dump."]
   [nil
    "--report-filename PATH"
    (str "Path to the file that you "
         "would like the report to be written to")]
   ["-v" "--verbose"]
   ["-f" "--force"]
   ["-h" "--help"]])

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (str/join \newline errors)))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn- run-delete-database
  ([url]
   (run-delete-database url false))
  ([url verbose]
   (if verbose
     (println "Deleting database: " url))
   (datomic/delete-database url)))

(defn- confirm-delete-db! [url]
  (println
   "Are you sure you would like to delete the database: "
   url
   " [y/n]")
  (= (.toLowerCase (read-line)) "y"))

(defn delete-database
  "Delete the database at the given URL."
  [& {:keys [url force verbose]
      :or {force false
           verbose false}}]
  (if (or force (confirm-delete-db! url))
    (run-delete-database url verbose)
    (println "Not deleting database")))

(defn generate-schema-view
  "Export the geneated database schema to a file."
  [& {:keys [url schema-filename verbose]
      :or {verbose false}}]
  (when verbose
    (println "Generating Datomic schema view")
    (println "\tCreating database connection"))
  (let [con (datomic/connect url)]
     (utils/with-outfile schema-filename)
       (doseq [s (schema-datomic/schema-from-db (datomic/db con))]
         (pp/pprint s)
         (println))
       (if verbose
         (println "\tReleasing database connection"))
       (datomic/release con)))

(defn generate-schema
  "Generate the database schema from the annotated ACeDB models."
  ([model-filename]
   (generate-schema model-filename false))
  ([model-filename verbose]
   (when verbose
     (println "\tGenerating Schema")
     (println (str "\\tRead in annotated ACeDB models "
                   "file generated by hand - PAD to create this"))
     (println "\tMaking the datomic schema from the acedb annotated models"))
   (let [file (io/file "models/" model-filename)
         models (model/parse-models (io/reader file))]
     (model2schema/models->schema models))))

(defn timestamp-schema
  [transact schema & {:keys [verbose]
                      :or {verbose false}}]
  (if verbose
    (println "\tAdding extra attribute 'schema' to list of attributes"
             "and add timestamp to preserve ACeDB timeseamps with"
             "auto-generated schema"))
  (transact
   (conj
    schema
    {:db/id        (datomic/tempid :db.part/tx)
     :db/txInstant #inst "1970-01-01T00:00:01"})))

(defn- transact-silenced
  "Tranact the entity `tx` using `con`.
  Suppresses the (potentially-large) report if it succeeds."
  [con tx]
  @(datomic/transact con tx)
  nil)

;; TODO: consolidate schemata (metaschema, basetypes, locatable{,-extras})
;;       consider moving to e.g: pseudoace.schemata/load-schema
;;
(defn load-schema
  "Load the schema for the database."
  ([url model]
   (load-schema url model false))
  ([url model verbose]
   (when verbose
     (println (str/join " " ["Loading Schema into:" url]))
     (println "\tCreating database connection"))
   (let [con (datomic/connect url)
         tx-quiet (partial transact-silenced con)
         main-schema (generate-schema model verbose)]
     ;; Built-in schemas include explicit 1970-01-01 timestamps.
     ;; the 'metaschema' and 'locatable-schema' lines simply execute
     ;; what was read in on the previous two lines for metadata and locatables
     (tx-quiet metaschema) ; pace namespace, used by importer
     (tx-quiet basetypes)
     (tx-quiet locatable-schema)
     ;; Add an extra attribute to the 'main-schema',
     ;; saying this transaction occurs on 1st Jan 1970 to fake a first
     ;; transaction to preserve the acedb timestamps
     (if verbose
       (println "\tAdding extra attribute 'schema' to list of attributes"
                "and add timestamp to preserve ACeDB timeseamps with"
                "auto-generated schema"))
     (tx-quiet (conj
                main-schema
                {:db/id        (datomic/tempid :db.part/tx)
                 :db/txInstant #inst "1970-01-01T00:00:01"}))
     (if verbose
       (println "\tAdding locatables-extras"))
     (tx-quiet locatable-extras)
     ;; needs to be transacted after the main schema.
     (if verbose
       (println "\tAdding wormbase-schema-fixups"))
     ;; TODO: consolidate schemata
     ;; (tx-quiet (conj schemata/fixups (schemata/make-ts-part)))
     (tx-quiet schema-fixups)
     (if verbose
       (println "\tReleasing database connection"))
     (datomic/release con))))

(defn create-database
  "Create a Datomic database from a schema generated
  from an annotated ACeDB models file."
  [& {:keys [url model verbose]
      :or {verbose false}}]
  (if verbose
    (println "Creating Database"))
  (generate-schema model verbose)
  (datomic/create-database url)
  (load-schema url model verbose))

(defn uri-to-helper-uri [uri]
  (str/join "-" [uri "helper"]))

(defn create-helper-database
  "Create a helper Datomic database from a schema."
  [& {:keys [url model verbose]
      :or {verbose false}}]
  (if verbose
    (println "Creating Helper Database"))
  (generate-schema model verbose)
  (let [helper-uri (uri-to-helper-uri url)]
    (datomic/create-database helper-uri)
    (load-schema helper-uri model verbose)))

(defn directory-walk [directory pattern]
  (doall (filter #(re-matches pattern (.getName %))
                 (file-seq (io/file directory)))))

(defn get-ace-files [directory]
  (map #(.getPath %) (directory-walk directory #".*\.ace.gz")))

(defn get-edn-log-files [directory]
  (map #(.getName %) (directory-walk directory #".*\.edn.gz")))

(defn get-sorted-edn-log-files [log-dir]
  (->> (.listFiles (io/file log-dir))
       (filter #(.endsWith (.getName %) ".edn.sort.gz"))
       (sort-by #(.getName %))))

(defn acedump-file-to-datalog
  ([imp file log-dir]
   (acedump-file-to-datalog imp file log-dir false))
  ([imp file log-dir verbose]
   (if (utils/not-nil? verbose)
     (println "\tConverting " file))
   ;; then pull out objects from the pipeline in chunks of 20 objects.
   ;; Larger block size may be faster if you have plenty of memory
   (doseq [blk (->> (java.io.FileInputStream. file)
                    (java.util.zip.GZIPInputStream.)
                    (ace/ace-reader)
                    (ace/ace-seq)
                    (partition-all 20))]
     (ts-import/split-logs-to-dir imp blk log-dir))))

(def helper-filename "helper.edn.gz")
(def helper-folder-name "helper")

(defn helper-dest-file [log-dir]
  (io/file log-dir helper-folder-name helper-filename))

(defn move-helper-log-file
  ([log-dir]
   (move-helper-log-file log-dir false))
  ([log-dir verbose]
   (if verbose
     (println "\tMoving helper log file"))
   (let [dest-file (helper-dest-file log-dir)
         helper-dir (io/file (.getParent dest-file))]
     (if-not (.exists helper-dir)
       (.mkdir helper-dir))
     (let [source (io/file log-dir helper-filename)]
       (when (.exists source)
         (io/copy source dest-file)
         (io/delete-file source))))))

(defn acedump-to-edn-logs
  "Create the EDN log files."
  [& {:keys [url log-dir acedump-dir verbose]
      :or {verbose false}}]
  (when verbose
    (println "Converting ACeDump to Datomic Log")
    (println "\tCreating database connection"))
  (let [con (datomic/connect url)

        ;; Helper object, holds a cache of schema data.
        imp (old-import/importer con)

        ;; Must be an empty directory.
        ;; *Directory path must end in a trailing forward slash, see:
        ;; *
        directory (io/file log-dir)
        files (get-ace-files acedump-dir)]
    (doseq [file files]
      (acedump-file-to-datalog imp file directory verbose))
    (move-helper-log-file log-dir verbose)
    (datomic/release con)))

(defn check-sh-result
  [result & {:keys [verbose]
             :or {verbose false}}]
  (if-not (zero? (:exit result))
    (println
     "ERROR: Sort command had exit value: "
     (:exit result)
     " and err: " (:err result) )
    (if verbose
      (println "Sort completed Successfully"))))

(defn get-current-directory []
  (.getCanonicalPath (java.io.File. ".")))

(defn sort-edn-logs-command [file]
  "Invoke the perl helper script to sort EDN files.

Assumes that the current working directory is the top level project
directory."
  (shell/sh
   "./scripts/sort-edn-log.pl"
   file
   :dir (get-current-directory)))

(defn sort-edn-logs
  "Sort the log files generated from ACeDB dump files."
  [& {:keys [log-dir verbose]
      :or {verbose false}}]
  (if verbose
    (println "Sorting datomic log"))
  (let [files (get-edn-log-files log-dir)]
     (doseq [file files]
       (if verbose
         (println "Sorting file " file))
       (let [filepath (str/join "" [log-dir file])
             result (sort-edn-logs-command filepath)]
         (check-sh-result result :verbose verbose)))))

(defn import-edn-logs
  "Import the sorted EDN log files."
  [& {:keys [url log-dir verbose]
      :or {verbose false}}]
  (if verbose
    (println "Importing logs into datomic" url log-dir verbose))
  (let [con (datomic/connect url)
        log-files (get-sorted-edn-log-files log-dir)]
    (if verbose
      (println "Importing" (count log-files) "log files"))
    (doseq [file log-files]
      (if verbose
        (println "\timporting: " (.getName file)))
      (ts-import/play-logfile
       con
       (java.util.zip.GZIPInputStream. (io/input-stream file))))
    (datomic/release con)))

(defn excise-tmp-data
  "Remove all the temporary data created during processing."
  [& {:keys [url]}]
  (let [con (datomic/connect url)]
    (datomic/transact
     con
     [{:db/id #db/id[:db.part/user] :db/excise :importer/temp}])))

(defn run-test-query
  "Perform tests on the generated database."
  [& {:keys [url verbose]
      :or {verbose false}}]
  (let [con (datomic/connect url)
        n-expected 1
        datalog-query '[:find ?c
                        :in $
                        :where
                        [?c :gene/id "WBGene00018635"]]
        results (datomic/q datalog-query (datomic/db con))
        n-results (count results)]
    (when verbose
      (println "Testing datomic data, expecting exactly" n-expected "result")
      (println "Datalog query:" datalog-query)
      (print "Results: ")
      (pprint results))
    (if-let [success (t/is (= n-results 1))]
      (println "OK")
      (println "Failed to find record matching " datalog-query))
    (datomic/release con)))

(defn import-helper-edn-logs
  "Import the helper log files."
  [& {:keys [url log-dir verbose]
      :or {verbose false}}]
  (if verbose
    (println "Importing helper log into helper database"))
  (let [helper-uri (uri-to-helper-uri url)
        helper-connection (datomic/connect helper-uri)]
    (binding [ts-import/*suppress-timestamps* true]
      (ts-import/play-logfile
       helper-connection
       (java.util.zip.GZIPInputStream.
        (io/input-stream (helper-dest-file log-dir)))))
    (if verbose
      (println "\tReleasing helper database connection"))
    (datomic/release helper-connection)))

(defn helper-file-to-datalog [helper-db file log-dir verbose]
  (if (utils/not-nil? verbose)
    (println "\tAdding extra data from: " file))
  ;; then pull out objects from the pipeline in chunks of 20 objects.
  (doseq [blk (->> (java.io.FileInputStream. file)
                   (java.util.zip.GZIPInputStream.)
                   (ace/ace-reader)
                   (ace/ace-seq)
                   (partition-all 20))] ;; Larger block size may be faster if
    ;; you have plenty of memory.
    (loc-import/split-locatables-to-dir helper-db blk log-dir)))

(defn run-locatables-importer-for-helper
  ([url]
   (run-locatables-importer-for-helper url false))
  ([url log-dir acedump-dir verbose]
   (if verbose
     (println "Importing logs with loactables importer into helper database"))
   (let [helper-uri (uri-to-helper-uri url)
         helper-connection (datomic/connect helper-uri)
         helper-db (datomic/db helper-connection)
         files (get-ace-files acedump-dir)]
     (doseq [file files]
       (helper-file-to-datalog helper-db file log-dir verbose))
     (if verbose
       (println "\tReleasing helper database connection"))
     (datomic/release helper-connection))))

(defn delete-helper-database
  "Delete the \"helper\" database."
  [& {:keys [url verbose]}]
  (if verbose
    (println "Deleting helper database"))
  (let [helper_uri (uri-to-helper-uri url)]
    (datomic/delete-database helper_uri)))

(defn all-import-actions
  "Perform all actions required to import data from ACeDB dump files."
  [& {:keys [url model log-dir acedump-dir schema-filename verbose]
      :or {verbose false}}]
  (create-database :url url :model model :verbose verbose)
  (acedump-to-edn-logs :url url
                       :log-dir log-dir
                       :acedump-dir acedump-dir
                       :verbose verbose)
  ;; DISABLED: (create-helper-database options)
  (generate-schema-view
   :url url
   :schema-filename schema-filename
   :verbose verbose)
  ;; DISABLED: (run-locatables-importer-for-helper url log-dir aceedump-dir verbose)
  ;; DISABLED: (delete-helper-database options)
  (sort-edn-logs :log-dir log-dir :verbose verbose)
  (import-edn-logs :url url :log-dir log-dir :verbose verbose))
  ;; DISABLED: (excise-tmp-data options)
  ;; DISABLED: (run-test-query options))


(defn list-databases
  "List all databases."
  [& {:keys [url]}]
  (doseq [database-name (datomic/get-database-names url)]
    (println database-name)))

(defn write-report [filename db]
  (let [elements-attributes
        (sort (datomic/q
               '[:find [?ident ...] :where [_ :db/ident ?ident]] db))]
    (with-open [wrtr (io/writer filename)]
      (binding [*out* wrtr]
        (print "element" "\t" "attribute" "\t" "count")
        (doseq [element-attribute elements-attributes]
          (let [element (namespace element-attribute)
                attribute (name element-attribute)
                expression [:find '(count ?eid) '.
                            :where ['?eid element-attribute]]
                name-of-entity (datomic/q expression db )
                line (str element "\t" attribute "\t" name-of-entity)]
            (println line)))))))

(defn generate-report
  "Generate a summary report of database content."
  [& {:keys [url report-filename verbose]
      :or {verbose false}}]
  (if verbose
    (println "Generateing Datomic database report"))
  (let [con (datomic/connect url)
        db  (datomic/db con)]
    (if verbose
      (println "\tGenerating:" report-filename))
    (write-report report-filename db)
    (datomic/release con)))

(defn backup-database
  "Backup the database at a given URL to a file."
  [& {:keys [url verbose]
      :or {verbose false}}]
  (throw (UnsupportedOperationException. "Not implemented yet"))
  (when verbose
    (println "Backing up database"))
  (let [con (datomic/connect url)]
    (datomic/release con)))

(def cli-actions [#'create-database
                  #'create-helper-database
                  #'generate-schema-view
                  #'acedump-to-edn-logs
                  #'sort-edn-logs
                  #'import-edn-logs
                  #'import-helper-edn-logs
                  #'excise-tmp-data
                  #'run-test-query
                  #'all-import-actions
                  #'generate-report
                  #'list-databases
                  #'delete-database])

(def cli-action-metas (map meta cli-actions))

(def cli-action-map (zipmap
                     (map (comp str :name) cli-action-metas)
                     cli-actions))

(def ^:private space-join (partial str/join "  "))


(defn- single-space
  "Remove occruances of multiple spaces in `s` with a single space."
  [s]
  (str/replace s #"\s{2,}" " "))

(defn- required-kwds
  "Return the required keyword arguments to `func-ref`.

  `func-ref` should be a reference to function."
  [func-ref]
  (let [func-info (meta func-ref)
        arglist (-> func-info :arglists flatten)
        kwds (last arglist)
        opt (apply sorted-set (-> kwds :or keys))
        req (apply sorted-set (:keys kwds))]
    (map keyword (difference req opt))))

(defn invoke-action
  "Invoke `action-name` with the options supplied."
  [action options]
  (let [supplied-opts (set (keys options))
        required-opts (set (required-kwds action))
        missing (difference required-opts supplied-opts)]
    (if (empty? missing)
      (apply action (flatten (into '() options)))
      (println
       "Missing required options:"
       (str/join "--" (conj (map name missing) nil))))))

(defn usage
  "Display command usage to the user."
  [options-summary]
  (let [action-names (keys cli-action-map)
        action-docs (map :doc cli-action-metas)
        doc-width-left (+ 10 (apply max (map count action-docs)))
        action-width-right (+ 10 (apply max (map count action-names)))
        line-template (str "%-" action-width-right "s%-" doc-width-left "s")]
    (str/join
     \newline
     (concat 
      [(str "Ace to dataomic is tool for importing data from ACeDB "
            "into to Datomic database")
       ""
       "Usage: ace-to-datomic [options] action"
       ""
       "Options:"
       options-summary
       ""
       (str "Actions: (required options for each action "
            "are provided in square brackets)")]
      (for [[name doc-string] (zipmap action-names action-docs)]
        (let [usage-doc (-> doc-string
                            str/split-lines
                            space-join
                            single-space)]
          (format line-template name usage-doc)))))))

(defn -main [& args]
  (let [{:keys [options
                arguments
                errors
                summary]} (parse-opts args cli-options)]
    (if errors
      (exit 1 (error-msg errors)))
    (if (:help options)
      (exit 0 (usage summary)))
    (let [action-name (last arguments)]
      (if-let [action (get cli-action-map action-name)]
        (invoke-action action options)
        (do
          (println "Unknown action" action-name)
          (exit 1 (usage summary)))))))

