(ns facepalm.core
  (:gen-class)
  (:use [clojure.java.io :only [copy file reader]]
        [clojure.tools.cli :only [cli]]
        [clojure-commons.file-utils :only [with-temp-dir]]
        [facepalm.error-codes]
        [kameleon.queries :only [current-db-version]]
        [kameleon.sql-reader :only [load-sql-file]]
        [korma.core :exclude [update]]
        [korma.db]
        [slingshot.slingshot :only [throw+ try+]])
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [clj-http.client :as client]
            [clojure-commons.config :as cfg]
            [facepalm.conversions :as cnv]
            [kameleon.pgpass :as pgpass]
            [me.raynes.fs :as fs])
  (:import [java.io IOException]
           [java.sql SQLException]
           [org.apache.log4j BasicConfigurator ConsoleAppender Level
            SimpleLayout]))

(declare initialize-database update-database)

(defn- do-init
  "The handler for the init mode."
  [opts]
  (initialize-database opts))

(defn- do-update
  "The handler for the update mode."
  [opts]
  (update-database opts))

(def ^:private default-cfg-dir
  "The service configuration file directory"
  "/etc/iplant/de")

(def ^:private default-jenkins-base
  "The base URL used to connect to Jenkins."
  "http://watson.iplantcollaborative.org/hudson")

(def ^:private default-jenkins-artifact-path
  "The relative path to use when retrieving build artifacts from Jenkins."
  "/lastSuccessfulBuild/artifact/builds/")

(def ^:private default-qa-drop-base
  "The base URL for the QA drops."
  "http://katic.iplantcollaborative.org/qa-drops")

(def ^:private modes
  "The map of mode names to their helper functions."
  {:init   do-init
   :update do-update})

(def ^:private modes-str
  "The names of the modes, used in the help string for the --mode option."
  (apply str (string/join " | " (map name (keys modes)))))

(def conversions (atom nil))

(def admin-db-spec (atom nil))

(defn set-conversions
  "Reads in the conversions from the unpacked build artifact. Set the conversions atom
   to the conversion map."
  [opts]
  (let [conversion-dir (fs/file "conversions")]
    (when (and (= :update (keyword (:mode opts))) (.exists conversion-dir))
      (println "Loading conversions...")
      (reset! conversions (cnv/conversion-map opts))
      (println "Done loading conversions.")
      (println "Here are the loaded conversions: ")
      (dorun (map (partial println "   ") (sort (keys @conversions)))))))

(defn- to-int
  "Parses a string representation of an integer."
  [i]
  (Integer. i))

(defn- parse-args
  "Parses the command-line arguments."
  [args]
  (cli args
       ["-?" "--help" "Show help." :default false :flag true]
       ["-m" "--mode" (str "The type of database update: [" modes-str "].")
        :default "init"]
       ["-h" "--host" "The database hostname." :default "localhost"]
       ["-p" "--port" "The database port number." :default 5432
        :parse-fn to-int]
       ["-d" "--database" "The database name." :default "de"]
       ["-A" "--admin-user" "The admin database username." :default "postgres"]
       ["-U" "--user" "The database username." :default "de"]
       ["-j" "--job" "The name of DE database job in Jenkins."]
       ["-q" "--qa-drop" "The QA drop date to use when retrieving"]
       ["-f" "--filename" "An explicit path to the database tarball."
        :default "database.tar.gz"]
       ["-v" "--version" "The destination database version"]
       ["--proxy-host" "Specifies an HTTP proxy host to use when resolving dependencies."]
       ["--proxy-port" "Specifies an HTTP proxy port to use when resolving dependencies."
        :default 80 :parse-fn to-int]
       ["--jenkins-base" "The base URL used to connect to Jenkins."
        :default default-jenkins-base]
       ["--jenkins-artifact-path" "The relative path to build artifacts in jenkins."
        :default default-jenkins-artifact-path]
       ["--qa-drop-base" "The base URL to use for the QA drops."
        :default default-qa-drop-base]
       ["--cfg-dir" "The location of the service configuration files."
        :default default-cfg-dir]
       ["--debug" "Enable debugging." :default false :flag true]))

(defn- pump
  "Pumps data obtained from a reader to an output stream.  Copied shamelessly
   from leiningen.core.eval/pump."
  [reader out]
  (let [buffer (char-array 1024)]
    (loop [len (.read reader buffer)]
      (when-not (neg? len)
        (.write out buffer 0 len)
        (.flush out)
        (Thread/sleep 100)
        (recur (.read reader buffer))))))

(defn- sh
  "A version of clojure.java.shell/sh that streams out/err.  Copied shamelessly
   from leiningen.core.eval/sh.  This version of (sh) is being used because
   clojure.java.shell/sh wasn't calling .destroy on the process, which was
   preventing this program from exiting in a timely manner.  It's also
   convenient to be able to stream standard output and standard error output to
   the user's terminal session."
  [& cmd]
  (log/debug "Executing command:" (string/join " " cmd))
  (try+
   (let [proc (.exec (Runtime/getRuntime) (into-array cmd))]
     (.addShutdownHook (Runtime/getRuntime)
                       (Thread. (fn [] (.destroy proc))))
     (with-open [out (reader (.getInputStream proc))
                 err (reader (.getErrorStream proc))]
       (let [pump-out (doto (Thread. #(pump out *out*)) .start)
             pump-err (doto (Thread. #(pump err *err*)) .start)]
         (.join pump-out)
         (.join pump-err))
       (.waitFor proc)))
   (catch Exception e
     (command-execution-failed cmd (.getMessage e)))))

(defn- create-layout
  "Creates the layout that will be used for log messages."
  []
  (doto (SimpleLayout.)
    (.activateOptions)))

(defn- configure-logging
  "Configures logging for this tool.  All logging is printed on the console,
   but the logging level may be changed."
  [opts]
  (BasicConfigurator/configure
   (doto (ConsoleAppender. (create-layout))
     (.setLayout (SimpleLayout.))
     (.setName "Console")
     (.setThreshold (if (:debug opts) Level/DEBUG Level/ERROR)))))

(defn- prompt-for-password
  "Prompts the user for a password."
  [user]
  (print user "password: ")
  (flush)
  (.. System console readPassword))

(defn- get-password
  "Attempts to obtain the database password from the user's .pgpass file.  If
   the password can't be obtained from .pgpass, prompts the user for the
   password"
  [host port database user]
  (let [password (pgpass/get-password host port database user)]
    (if (nil? password)
      (if (nil? (System/console))
        (no-password-supplied host port database user)
        (prompt-for-password user))
      password)))

(defn- define-db
  "Defines the database connection settings."
  [{:keys [host port database user admin-user]}]
  (let [db-spec        {:classname   "org.postgresql.Driver"
                        :subprotocol "postgresql"
                        :subname     (str "//" host ":" port "/" database)}
        password       (get-password host port database user)
        admin-password (get-password host port database admin-user)]
    (when admin-password
      (reset! admin-db-spec
              (postgres (assoc db-spec
                          :schema "public"
                          :user admin-user
                          :password (apply str admin-password)))))
    (defdb de (assoc db-spec
                :user user
                :password (apply str password)))))

(defn- test-db
  "Test the database connection settings to ensure that the connection settings
   are correct."
  [{:keys [mode host port database user admin-user]}]
  (when (= :init (keyword mode))
    (try+
     (with-db @admin-db-spec (transaction))
     (catch Exception e
       (database-connection-failure host port database admin-user))))
  (try+
   (transaction)
   (catch Exception e
     (database-connection-failure host port database user))))

(defn- jenkins-build-artifact-url
  "Returns the URL used to obtain the build artifact from Jenkins."
  [{:keys [jenkins-base jenkins-artifact-path job filename]}]
  (str jenkins-base "/job/" job jenkins-artifact-path filename))

(defn- qa-drop-build-artifact-url
  "Returns the URL used to obtain the build artifact from a QA drop."
  [{:keys [qa-drop-base qa-drop filename]}]
  (str qa-drop-base "/" qa-drop "/" filename))

(defn- build-artifact-url
  "Builds and returns the URL used to obtain the build artifact."
  [{:keys [qa-drop job] :as opts}]
  (cond (not (string/blank? qa-drop)) (qa-drop-build-artifact-url opts)
        (not (string/blank? job))     (jenkins-build-artifact-url opts)
        :else                         (options-missing :job :qa-drop)))

(defn- add-proxy
  "Adds proxy configuration settings to a request if a proxy was specified on the command line."
  [cli-opts request-opts]
  (when (:proxy-host cli-opts)
    (assoc request-opts
      :proxy-host (:proxy-host cli-opts)
      :proxy-port (:proxy-port cli-opts))))

(defn- get-remote-resource
  "Gets a remote resource using a URL."
  [resource-url opts]
  (client/get resource-url
              (add-proxy opts {:as               :stream
                               :throw-exceptions false})))

(defn- get-build-artifact-from-url
  "Gets the build artifact from a URL."
  [opts]
  (let [artifact-url          (build-artifact-url opts)
        {:keys [status body]} (get-remote-resource artifact-url opts)]
    (if-not (< 199 status 300)
      (build-artifact-retrieval-failed status artifact-url))
    (with-open [in body]
      (copy in (fs/file (:filename opts))))))

(defn get-build-artifact-from-file
  "Gets the build artifact from a local file."
  [filename]
  (let [src (file filename)
        dst (fs/file (.getName src))]
    (try+
     (copy src dst)
     (catch IOException e
       (database-tarball-copy-failed src dst (.getMessage e))))))


(defn- get-build-artifact
  "Obtains the database build artifact."
  [{:keys [filename job qa-drop] :as opts}]
  (println "Retrieving the build artifact...")
  (if (every? string/blank? [qa-drop job])
    (get-build-artifact-from-file filename)
    (get-build-artifact-from-url opts)))

(defn- unpack-build-artifact
  "Unpacks the database build artifact after it has been obtained."
  [dir filename]
  (println "Unpacking the build artifact...")
  (let [file-path   (.getPath (fs/file (.getName (file filename))))
        exit-status (sh "tar" "xvf" file-path "-C" (.getPath dir))]
    (when-not (zero? exit-status)
      (build-artifact-expansion-failed))))

(defn- load-sql-files
  "Loads SQL files from a subdirectory of the artifact directory."
  [subdir-name]
  (let [subdir (fs/file subdir-name)]
    (dorun (map load-sql-file
                (sort-by #(.getName %) (.listFiles subdir))))))

(defn- refresh-public-schema
  "Refreshes the public shema associated with the database."
  [user]
  (println "Refreshing the public schema...")
  (dorun (map exec-raw
              ["DROP SCHEMA public CASCADE"
               "CREATE SCHEMA public"
               (str "ALTER SCHEMA public OWNER TO " user)])))

(defn- log-next-exception
  "Calls getNextException and logs the resulting exception for any SQL
  exception in the cause stack."
  [e]
  (when-not (nil? e)
    (if (instance? SQLException e)
      (log/error (.getNextException e) "next exception"))
    (recur (.getCause e))))

(defn- init-database
  "Refreshes the public schema and initializes extension scripts with the admin user."
  [opts]
  (try+
   (with-db @admin-db-spec
     (transaction (refresh-public-schema (:user opts)))
     (transaction (load-sql-files "extensions")))
   (catch Exception e
     (log-next-exception e)
     (throw+))))

(defn- apply-database-init-scripts
  "Applies the database initialization scripts to the database."
  []
  (try+
   (dorun (map #(load-sql-files %) ["tables" "constraints" "views" "data" "functions"]))
   (catch Exception e
     (log-next-exception e)
     (throw+))))

(defn- initialize-database
  "Initializes the database using a database archive obtained from a well-known
  location."
  [opts]
  (with-temp-dir dir "-fp-" temp-directory-creation-failure
    (get-build-artifact opts)
    (unpack-build-artifact dir (:filename opts))
    (set-conversions opts)
    (init-database opts)
    (transaction (apply-database-init-scripts))))

(defn- get-current-db-version
  "Gets the current database version, defaulting to 1.2.0:20120101.01 if the
   current version is nil or in a format that we don't recognize."
  []
  (let [ver (str (current-db-version))]
    (if (or (nil? ver) (not (re-find #":" ver)))
      "1.2.0:20120101.01"
      ver)))

(defn- get-update-versions
  "Gets the list of versions to run database conversions for."
  [current-version dest-version]
  (let [sorted-versions  (sort (keys @conversions))
        dest-version     (or dest-version (last sorted-versions))
        existing-version #(<= (compare % current-version) 0)
        wanted-version   #(<= (compare % dest-version) 0)]
    (->> sorted-versions
         (drop-while existing-version)
         (take-while wanted-version))))

(defn- load-cfg
  [opts cfg-name]
  (let [props (ref nil)]
    (cfg/load-config-from-file (:cfg-dir opts) cfg-name props)
    @props))

(defn- do-conversion
  "Performs a database conversion and updates the database version."
  [opts new-version]
  (let [convert   (@conversions new-version)
        extra-cfg (:cfg-file (meta convert))]
    (transaction
     (if extra-cfg
       (convert @admin-db-spec (load-cfg opts extra-cfg))
       (convert))
     (insert :version
             (values {:version new-version})))))

(defn- update-database
  "Converts the database schema from one DE version to another."
  [opts]
  (with-temp-dir dir "-fp-" temp-directory-creation-failure
    (get-build-artifact opts)
    (unpack-build-artifact dir (:filename opts))
    (set-conversions opts)
    (let [versions (get-update-versions (get-current-db-version) (:version opts))]
      (try+
       (dorun (map (partial do-conversion opts) versions))
       (catch Exception e
         (log-next-exception e)
         (throw+))))))

(defn- exec-mode-fn
  "Executes the function associated with the selected mode of operation."
  [opts]
  (let [mode-fn (modes (keyword (:mode opts)))]
    (if-not (nil? mode-fn)
      (mode-fn opts)
      (unknown-mode (:mode opts)))))

(defn -main
  "Parses the command-line options and performs the database updates."
  [& args-vec]
  (let [[opts args banner] (parse-args args-vec)]
    (when (:help opts)
      (println banner)
      (System/exit 0))
    (configure-logging opts)
    (log/debug opts)
    (trap banner
     (define-db opts)
     (test-db opts)
     (exec-mode-fn opts))))
