(ns swank.commands.basic
  (:use (swank util commands core)
        (swank.util.concurrent thread))
  (:require (swank.util [sys :as sys]))
  (:import (java.io StringReader File)
           (java.util.zip ZipFile)
           (clojure.lang LineNumberingPushbackReader)))

;;;; Connection

(defslimefn connection-info []
  `(:pid ~(sys/get-pid)
    :style :spawn
    :lisp-implementation (:type "clojure" :name "clojure")
    :package (:name ~(name (ns-name *ns*))
              :prompt ~(name (ns-name *ns*)))
    :version ~(deref *protocol-version*)))

(defslimefn quit-lisp []
  (.exit System 0))

;;;; Evaluation

(defn- eval-region
  "Evaluate string, return the results of the last form as a list and
   a secondary value the last form."
  ([string]
     (with-open rdr (new LineNumberingPushbackReader (new StringReader string))
       (loop [form (read rdr false rdr), value nil, last-form nil]
         (if (= form rdr)
           [value last-form]
           (recur (read rdr false rdr)
                  (eval form)
                  form))))))

(defslimefn interactive-eval-region [string]
  (with-emacs-package
   (pr-str (first (eval-region string)))))

(defslimefn interactive-eval [string]
  (with-emacs-package
   (let [result (eval (read-from-string string))]
     (pr-str (first (eval-region string))))))

(defslimefn listener-eval [form]
  (with-emacs-package
   (with-package-tracking
    (let [[value last-form] (eval-region form)]
      (send-repl-results-to-emacs value)))))

;;;; Macro expansion

(defn- apply-macro-expander [expander string]
  (pr-str (expander (read-from-string string))))

(defslimefn swank-macroexpand-1 [string]
  (apply-macro-expander macroexpand-1 string))

(defslimefn swank-macroexpand [string]
  (apply-macro-expander macroexpand string))

;; not implemented yet, needs walker
(defslimefn swank-macroexpand-all [string]
  (apply-macro-expander macroexpand string))

;;;; Compiler / Execution

(def *compiler-exception-location-re* #"^clojure\\.lang\\.Compiler\\$CompilerException: ([^:]+):([^:]+):")
(defn- guess-compiler-exception-location [#^clojure.lang.Compiler$CompilerException t]
  (let [[match file line] (re-find *compiler-exception-location-re* (.toString t))]
    (when (and file line)
      `(:location (:file ~file) (:line ~(Integer/parseInt line)) nil))))

;; TODO: Make more and better guesses
(defn- exception-location [#^Throwable t]
  (or (guess-compiler-exception-location t)
      '(:error "No error location available")))

;; plist of message, severity, location, references, short-message
(defn- exception-to-message [#^Throwable t]
  `(:message ~(.toString t)
             :severity :error
             :location ~(exception-location t) 
             :references nil
             :short-message ~(.toString t)))

(defn- exception-causes [#^Throwable t]
  (lazy-cons t (when-let cause (.getCause t)
                 (exception-causes cause))))

(defn- compile-file-for-emacs*
  "Compiles a file for emacs. Because clojure doesn't compile, this is
   simple an alias for load file w/ timing and messages. This function
   is to reply with the following:
     (:swank-compilation-unit notes results durations)"
  ([file-name]
     (let [start (System/nanoTime)]
       (try
        (let [ret (load-file file-name)
              delta (- (System/nanoTime) start)]
          `(:swank-compilation-unit nil (~ret) (~(/ delta 1000000000.0))))
        (catch Throwable t
          (let [delta (- (System/nanoTime) start)
                causes (exception-causes t)
                num (count causes)]
            `(:swank-compilation-unit
              ~(map exception-to-message causes) ;; notes
              ~(take num (repeat nil)) ;; results
              ~(take num (repeat (/ delta 1000000000.0))) ;; durations
              )))))))

(defslimefn compile-file-for-emacs
  ([file-name load?]
     (when load?
       (compile-file-for-emacs* file-name))))

(defslimefn load-file [file-name]
  (pr-str (clojure/load-file file-name)))

;;;; Describe

(defn- describe-to-string [var]
  (with-out-str
   (print-doc var)))

(defn- describe-symbol* [symbol-name]
  (with-emacs-package
   (if-let v (ns-resolve (maybe-ns *current-package*) (symbol symbol-name))
     (describe-to-string v)
     (str "Unknown symbol " symbol-name))))

(defslimefn describe-symbol [symbol-name]
  (describe-symbol* symbol-name))

(defslimefn describe-function [symbol-name]
  (describe-symbol* symbol-name))

;; Only one namespace... so no kinds
(defslimefn describe-definition-for-emacs [name kind]
  (describe-symbol* name))

;; Only one namespace... so only describe symbol
(defslimefn documentation-symbol
  ([symbol-name default] (documentation-symbol symbol-name))
  ([symbol-name] (describe-symbol* symbol-name)))


;;;; Operator messages
(defslimefn operator-arglist [name package]
  (try
   (let [f (read-from-string name)]
     (cond
      (keyword? f) "[map]"
      (symbol? f) (let [var (ns-resolve (maybe-ns package) f)]
                    (if-let args (and var (:arglists (meta var)))
                      (pr-str args)
                      nil))
      :else nil))
   (catch Throwable t nil)))



;;;; Completions


(defn- vars-with-prefix
  "Filters a coll of vars and returns only those that have a given
   prefix."
  ([#^String prefix vars]
     (let [matches-prefix?
           (fn matches-prefix? [#^String s]
             (and s (not= 0 (.length s)) (.startsWith s prefix)))]
       (filter matches-prefix? (map (comp str :name meta) vars)))))

(defn- largest-common-prefix
  "Returns the largest common prefix of two strings."
  ([#^String a #^String b]
     (let [limit (min (.length a) (.length b))]
       (loop [i 0]
         (if (or (= i limit)
                 (not= (.charAt a i) (.charAt b i)))
           (.substring a 0 i)
           (recur (inc i))))))
  {:tag String})

(defn- symbol-name-parts
  "Parses a symbol name into a namespace and a name. If name doesn't
   contain a namespace, the default-ns is used (nil if none provided)."
  ([symbol]
     (symbol-name-parts symbol nil))
  ([#^String symbol default-ns]
     (let [ns-pos (.indexOf symbol (int \/))]
       (if (= ns-pos -1) ;; namespace found? 
         [default-ns symbol] 
         [(.substring symbol 0 ns-pos) (.substring symbol (inc ns-pos))]))))

(defn- maybe-alias [sym pkg]
  (or (find-ns sym)
      (get (ns-aliases (maybe-ns pkg)) sym)))

(defslimefn simple-completions [symbol-string package]
  (try
   (let [[sym-ns sym-name] (symbol-name-parts symbol-string)
         ns (if sym-ns (maybe-alias (symbol sym-ns) package) (maybe-ns package))
         vars (vals (if sym-ns (ns-publics ns) (ns-map ns)))
         matches (sort (vars-with-prefix sym-name vars))]
     (if sym-ns
       (list (map (partial str sym-ns "/") matches)
             (if matches
               (str sym-ns "/" (reduce largest-common-prefix matches))
               symbol-string))
       (list matches
             (if matches
               (reduce largest-common-prefix matches)
               symbol-string))))
   (catch java.lang.Throwable t
     (list nil symbol-string))))


(defslimefn list-all-package-names
  ([] (map (comp str ns-name) (all-ns)))
  ([nicknames?] (list-all-package-names)))

(defslimefn set-package [name]
  (let [ns (maybe-ns name)]
    (in-ns (ns-name ns))
    (list (str (ns-name ns))
          (str (ns-name ns)))))

;;;; Source Locations
(comment
  "Sets the default directory (java's user.dir). Note, however, that
   this will not change the search path of load-file. ")
(defslimefn set-default-directory
  ([directory & ignore]
     (System/setProperty "user.dir" directory)
     directory))


;;;; meta dot find

(defn- slime-find-file-in-dir [#^File file #^String dir]
  (let [file-name (. file (getPath))
        child (File. (File. dir) file-name)]
    (or (when (.exists child)
          `(:file ~(.getPath child)))
        (try
         (let [zipfile (ZipFile. dir)]
           (when (.getEntry zipfile file-name)
             `(:zip ~dir ~file-name)))
         (catch Throwable e false)))))

(defn- slime-find-file-in-paths [#^String file paths]
  (let [f (File. file)]
    (if (.isAbsolute f)
      `(:file ~file)
      (first (filter identity (map (partial slime-find-file-in-dir f) paths))))))

(defn- get-path-prop
  "Returns a coll of paths within a property"
  ([prop]
     (seq (.. System
              (getProperty prop)
              (split File/pathSeparator)))))

(defn- slime-search-paths []
  (concat (get-path-prop "user.dir")
          (get-path-prop "java.class.path")
          (get-path-prop "sun.boot.class.path")
          (map #(.getPath %) (seq (.getURLs (.ROOT_CLASSLOADER clojure.lang.RT))))))

(defn- namespace-to-path [ns]
  (.. (ns-name ns)
      toString
      (replace \- \_)
      (replace \. \/)))

(defslimefn find-definitions-for-emacs [name]
  (let [sym-name (read-from-string name)
        sym-var (ns-resolve (maybe-ns *current-package*) sym-name)]
    (when-let meta (and sym-var (meta sym-var))
      (list (if-let path (or (slime-find-file-in-paths (str (namespace-to-path (:ns meta))
                                                            (.separator File)
                                                            (:file meta)) (slime-search-paths))
                             (slime-find-file-in-paths (:file meta) (slime-search-paths)))
              `(~(str "(defn " (:name meta) ")")
                (:location
                 ~path
                 (:line ~(:line meta))
                 nil))
              `(~(str (:name meta))
                (:error "Source definition not found.")))))))


(defslimefn throw-to-toplevel []
  (throw (swank.core.DebugQuitException. "Return debug")))

(defslimefn invoke-nth-restart-for-emacs [level n]
  (if (= n 1)
    (throw (.getCause *current-exception*))
    (throw (swank.core.DebugQuitException. "Nth restart"))))

(defslimefn buffer-first-change [file-name] nil)

(defslimefn backtrace [start end] nil)
(defslimefn frame-catch-tags-for-emacs [n] nil)
(defslimefn frame-locals-for-emacs [n] nil)
