(ns adzerk.boot-cljs.middleware
  (:require [adzerk.boot-cljs.util :as util]
            [boot.file :as file]
            [boot.util :as butil]
            [clojure.java.io :as io]
            [clojure.string :as string]))

;; helpers ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- main-ns-forms
  "Given a namespace symbol, ns-sym, a sorted set of namespace symbols this
  namespace will :require, requires, and a vector of namespace-qualified
  symbols, init-fns, constructs the forms for a namespace named ns-sym that
  :requires the namespaces in requires and calls the init-fns with no arguments
  at the top level."
  [ns-sym requires init-fns]
  (apply list
         (list 'ns ns-sym (apply list :require requires))
         (map list init-fns)))

(defn- format-ns-forms
  "Given a sequence of forms, formats them nicely as a string."
  [forms]
  (->> forms (map pr-str) (string/join "\n\n") (format "%s\n")))

(defn- cljs-edn-path->output-dir-path
  [cljs-edn-path]
  (str (.replaceAll cljs-edn-path "\\.cljs\\.edn$" "") ".out"))

(defn- cljs-edn-path->js-path
  [cljs-edn-path]
  (str (.replaceAll cljs-edn-path "\\.cljs\\.edn$" "") ".js"))

(defn- cljs-edn-path->module-path
  [cljs-edn-path module-k]
  (.getPath (io/file (str (.replaceAll cljs-edn-path "\\.cljs\\.edn$" "")) (str (name module-k) ".js"))))

;; middleware ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn compiler-options
  [{:keys [opts main] :as ctx}
   {:keys [compiler-options] :as task-options}]
  (assoc ctx :opts (merge (:compiler-options main)
                          compiler-options
                          (select-keys task-options [:optimizations :source-map]))))

(defn set-option [ctx k value]
  (when-let [current-value (get-in ctx [:opts k])]
    (butil/warn "WARNING: Replacing ClojureScript compiler option %s with automatically set value%s.\n"
                k (case k
                    :main ": :main option is not compatibly with :require or :init-fns."
                    nil)))
  (assoc-in ctx [:opts k] value))

(defn main
  "Middleware to create the CLJS namespace for the build's .cljs.edn file and
  set the compiler :output-to option accordingly. The :output-to will be derived
  from the path of the .cljs.edn file (e.g. foo/bar.cljs.edn will produce the
  foo.bar CLJS namespace with output to foo/bar.js)."
  [{:keys [tmp-src tmp-out main] :as ctx} write-main?]
  (let [out-rel-path (cljs-edn-path->output-dir-path (:rel-path main))
        asset-path   (util/get-name out-rel-path)
        out-file     (io/file tmp-out out-rel-path)
        out-path     (.getPath out-file)
        js-path      (util/path tmp-out (cljs-edn-path->js-path (:rel-path main)))
        cljs-path    (util/path "boot" "cljs" (str (:ns-name main) ".cljs"))
        cljs-file    (io/file tmp-src cljs-path)
        cljs-ns      (symbol (util/path->ns cljs-path))

        use-main-compiler-option? (and (get-in ctx [:opts :main]) (empty? (:init-fns main)) (empty? (:require main)))]

    (if-not use-main-compiler-option?
      (let [init-fns     (:init-fns main)
            requires     (into (sorted-set) (:require main))
            init-nss     (into requires (->> init-fns (keep namespace) (map symbol)))]
        (.mkdirs out-file)
        (when write-main?
          (doto cljs-file
            (io/make-parents)
            (spit (format-ns-forms (main-ns-forms cljs-ns init-nss init-fns)))))))

    (-> ctx
        ;; Only update asset-path in not set
        (update-in [:opts :asset-path] #(if % % asset-path))
        (set-option :output-dir out-path)
        (set-option :output-to js-path)
        (cond-> (not use-main-compiler-option?) (set-option :main cljs-ns)))))

(defn modules
  "If .cljs.edn file contains modules declaration, use it to create options
  to ClojureScript compiler. Output-to values are generated for modules
  based on relative path of .cljs.edn file and key of the module.

  Cljs-base module is written to the path of .cljs.edn (similar to how
  output-to is set without modules).

  Example, js/main.cljs.edn with modules declaration will setup following options:
  :modules {:cljs-base {:output-to \"<tmp-dir>/js/main.js\"}
            :common    {:output-to \"<tmp-dir>/js/main/common.js\" ...}
            :core      {:output-to \"<tmp-dir>/js/main/core.js\" ...}}"
  [{:keys [tmp-out main] :as ctx}]
  (if-let [modules (:modules main)]
    (set-option ctx :modules (assoc (into {} (map (fn [[k v]]
                                                    (let [js-path (util/path tmp-out (cljs-edn-path->module-path (:rel-path main) k))]
                                                      [k (assoc v :output-to js-path)]))
                                                  modules))
                                    :cljs-base {:output-to (:output-to (:opts ctx))}))
    ctx))

(defn source-map
  "Middleware to configure source map related CLJS compiler options."
  [{:keys [opts] :as ctx}]
  (if-not (:source-map opts)
    ctx
    (let [optimizations (:optimizations opts :none)
          sm  (-> opts :output-to (str ".map"))
          dir (.getName (io/file (:output-dir opts)))]
      ; Under :none optimizations only true and false are valid values:
      ; https://github.com/clojure/clojurescript/wiki/Compiler-Options#source-map
      ; If modules are used, should be true.
      (update-in ctx [:opts] assoc
                 :optimizations optimizations
                 :source-map (if (or (= optimizations :none) (:modules opts)) true sm)
                 :source-map-path dir))))
