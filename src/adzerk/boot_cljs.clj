(ns adzerk.boot-cljs
  {:boot/export-tasks true}
  (:refer-clojure :exclude [compile])
  (:require [adzerk.boot-cljs.js-deps :as deps]
            [adzerk.boot-cljs.middleware :as wrap]
            [adzerk.boot-cljs.util :as util]
            [boot.core :as core]
            [boot.pod :as pod]
            [boot.file :as file]
            [boot.util :refer [dbug info warn]]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as string]))

(def ^:private QUALIFIERS
  "Order map for well-known Clojure version qualifiers."
  { "alpha" 0 "beta" 1 "rc" 2 "" 3})

(defn- assert-clojure-version!
  "Warn user if Clojure 1.7 or greater is not found"
  [pod]
  (let [{:keys [major minor incremental qualifier]} (pod/with-eval-in pod *clojure-version*)
        [qualifier-part1 qualifier-part2] (if-let [[_ w d] (re-find #"(\w+)(\d+)" (or qualifier ""))]
                                            [(get QUALIFIERS w) (Integer/parseInt d)]
                                            [3 0])]
    (when-not (>= (compare [major minor incremental qualifier-part1 qualifier-part2] [1 7 0 3 0]) 0)
      (warn "ClojureScript requires Clojure 1.7 or greater.\nSee https://github.com/boot-clj/boot/wiki/Setting-Clojure-version.\n"))))

(defn- assert-cljs! []
  (let [deps  (map :dep (pod/resolve-dependencies (core/get-env)))
        cljs? #{'org.clojure/clojurescript}]
    (if (empty? (filter (comp cljs? first) deps))
      (warn "ERROR: No ClojureScript dependency.\n"))))

(defn- read-cljs-edn
  [tmp-file]
  (let [file (core/tmp-file tmp-file)
        path (core/tmp-path tmp-file)]
    (assoc (read-string (slurp file))
           :path     (.getPath file)
           :rel-path path
           :id       (string/replace (.getName file) #"\.cljs\.edn$" ""))))

(defn- compile
  "Given a compiler context and a pod, compiles CLJS accordingly. Returns a
  seq of all compiled JS files known to the CLJS compiler in dependency order,
  as paths relative to the :output-to compiled JS file."
  [{:keys [tmp-src tmp-out main opts] :as ctx} pod]
  (let [{:keys [warnings dep-order]}
        (pod/with-call-in pod
          (adzerk.boot-cljs.impl/compile-cljs ~(.getPath tmp-src) ~opts))]
    (swap! core/*warnings* + (or warnings 0))
    (conj dep-order (-> opts :output-to util/get-name))))

(defn cljs-files
  [fileset]
  (->> fileset core/input-files (core/by-ext [".cljs" ".cljc"]) (sort-by :path)))

(defn main-files 
  ([fileset]
   (main-files fileset nil))
  ([fileset id]
   (let [select (if (seq id)
                  #(core/by-name [(str id ".cljs.edn")] %)
                  #(core/by-ext [".cljs.edn"] %))]
     (->> fileset
          core/input-files
          select
          (sort-by :path)))))

(defn tmp-file->docroot [tmp-file]
  (or (.getParent (io/file (core/tmp-path tmp-file))) ""))

(defn new-pod! []
  (future (doto (pod/make-pod (core/get-env)) assert-clojure-version!)))

(defn make-compiler [cljs-edn tmp-result]
  {:pod         (new-pod!)
   :initial-ctx {:tmp-src (core/tmp-dir!)
                 :tmp-out tmp-result
                 :docroot (tmp-file->docroot cljs-edn)
                 :main    (-> (read-cljs-edn cljs-edn)
                              (assoc :ns-name (name (gensym "main"))))}})

(defn compile-1 [compilers task-opts tmp-result {:keys [path] :as cljs-edn}]
  (swap! compilers #(util/assoc-or % path (make-compiler cljs-edn tmp-result)))
  (let [{:keys [pod initial-ctx]} (get @compilers path)
        ctx (-> initial-ctx
                (wrap/compiler-options task-opts)
                wrap/main
                wrap/source-map)
        out (.getPath (file/relative-to tmp-result (-> ctx :opts :output-to)))]
    (info "• %s\n" out)
    (dbug "CLJS options:\n%s\n" (with-out-str (pp/pprint (:opts ctx))))
    (future (compile ctx @pod))))

(defn compile-all [compilers task-opts tmp-result cljs-edns]
  (info "Compiling ClojureScript...\n")
  (let [compile #(compile-1 compilers task-opts tmp-result %)]
    (mapv deref (mapv compile cljs-edns))))

(core/deftask ^:private default-main
  "Private task.

  If no .cljs.edn exists with given id, creates one. This default .cljs.edn file
  will :require all CLJS namespaces found in the fileset."
  [i id ID str ""]
  (let [tmp-main (core/tmp-dir!)]
    (core/with-pre-wrap fileset
      (core/empty-dir! tmp-main)
      (if (seq (main-files fileset id))
        fileset
        (let [cljs     (cljs-files fileset)
              out-main (str (or id "main") ".cljs.edn")
              out-file (io/file tmp-main out-main)]
          (info "Writing %s...\n" (.getName out-file))
          (doto out-file
            (io/make-parents)
            (spit {:require (mapv (comp symbol util/path->ns core/tmp-path) cljs)}))
          (-> fileset (core/add-source tmp-main) core/commit!))))))

(core/deftask cljs
  "Compile ClojureScript applications.

   Available --optimization levels (default 'none'):

   * none         No optimizations. Bypass the Closure compiler completely.
   * whitespace   Remove comments, unnecessary whitespace, and punctuation.
   * simple       Whitespace + local variable and function parameter renaming.
   * advanced     Simple + aggressive renaming, inlining, dead code elimination.

   Source maps can be enabled via the --source-map flag. This provides what the
   browser needs to map locations in the compiled JavaScript to the corresponding
   locations in the original ClojureScript source files.

   The --compiler-options option can be used to set any other options that should
   be passed to the Clojurescript compiler. A full list of options can be found
   here: https://github.com/clojure/clojurescript/wiki/Compiler-Options."

  [i id ID                 str  ""
   O optimizations LEVEL   kw   "The optimization level."
   s source-map            bool "Create source maps for compiled JS."
   c compiler-options OPTS edn  "Options to pass to the Clojurescript compiler."]

  (let [tmp-result (core/tmp-dir!)
        compilers  (atom {})]
    (assert-cljs!)
    (comp
      (default-main)
      (core/with-pre-wrap fileset
        (compile-all compilers *opts* tmp-result (main-files fileset))
        (-> fileset
            (core/add-resource tmp-result)
            ;(core/add-meta (-> fileset (deps/compiled dep-order)))
            core/commit!)))))
