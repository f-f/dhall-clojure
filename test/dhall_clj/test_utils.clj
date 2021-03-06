(ns dhall-clj.test-utils
  (:require [medley.core :refer [map-vals]]
            [dhall-clj.binary :refer [slurp-bytes]]
            [clojure.string :as string]
            [clojure.java.io :as io]))


;; Credit: https://clojuredocs.org/clojure.core/tree-seq#example-54d33991e4b0e2ac61831d15
(defn list-files [basepath]
  (let [directory (clojure.java.io/file basepath)
        dir? #(.isDirectory %)]
    ;; we want only files, therefore filter items that are not directories.
    (filter (comp not dir?)
            (tree-seq dir? #(.listFiles %) directory))))

(defn failure-case?
  "Given a `File`, will return true if it's a failure test case.
  Note: we try to match both Windows and *nix paths."
  [file]
  (or (string/includes? (str file) "/failure/")
      (string/includes? (str file) "\\failure\\")))

(defn success-testcases
  "Returns a record of records {'testcase name' {:actual Text, :expected Text}}
  for the 'successful' test cases."
  [test-folder]
  (let [files (->> (list-files test-folder)
                 (remove failure-case?)
                 (remove #(string/includes? (str %) ".md")))
        map-of-testcases (group-by #(-> % str
                                       (string/replace #"A.dhall" "")
                                       (string/replace #"B.dhall" ""))
                                   files)]
    (map-vals
      (fn [a-and-b]
        ;; We sort so we get the A.dhall file first
        (let [[actual expected] (sort a-and-b)]
          {:actual   (slurp actual)
           :expected (slurp expected)}))
      map-of-testcases)))

(defn failure-testcases
  "Returns a record of all testcases that should fail.
  The keys are the path to the file, and the values are the
  associated Dhall expression."
  [test-folder]
  (let [files (->> (list-files test-folder)
                 (filter failure-case?)
                 (remove #(string/includes? (str %) ".md")))]
    (into {} (mapv #(vector (str %) (slurp %)) files))))


(defn success-binary-testcases
  "Returns a record of records {'testcase name' {:actual Text, :expected ByteArray}}
  for the 'successful' test cases."
  [test-folder]
  (let [files (->> (list-files test-folder)
                 (remove failure-case?))
        map-of-testcases (group-by #(-> % str
                                       (string/replace #"A.dhall" "")
                                       (string/replace #"B.dhallb" ""))
                                   files)]
    (map-vals
     (fn [a-and-b]
       ;; We sort so we get the A.dhall file first
       (let [[actual expected] (sort a-and-b)]
         {:actual   (slurp actual)
          :expected (slurp-bytes expected)}))
     map-of-testcases)))
