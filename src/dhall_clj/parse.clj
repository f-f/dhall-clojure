(ns dhall-clj.parse
  (:require [instaparse.core :as insta]
            [clojure.java.io :as io]
            [lambdaisland.uri :refer [uri join]]
            [dhall-clj.ast :refer :all]
            [dhall-clj.fail :as fail]
            [clojure.string :as str]))

(def grammar
  "The Dhall grammar specified in ABNF"
  (slurp (io/resource "dhall.abnf")))

(def keywords
  ["Bool"
   "Optional"
   "None"
   "Natural"
   "Integer"
   "Double"
   "Text"
   "List"
   "True"
   "False"
   "NaN"
   "Infinity"
   "Type"
   "Kind"
   "Sort"
   "Natural/fold"
   "Natural/build"
   "Natural/isZero"
   "Natural/even"
   "Natural/odd"
   "Natural/toInteger"
   "Natural/show"
   "Integer/toDouble"
   "Integer/show"
   "Double/show"
   "List/build"
   "List/fold"
   "List/length"
   "List/head"
   "List/last"
   "List/indexed"
   "List/reverse"
   "Optional/fold"
   "Optional/build"
   "Text/show"
   "if"
   "then"
   "else"
   "let"
   "in"
   "as"
   "using"
   "merge"
   "constructors"
   "Some"])

(defn patch-grammar [grammar]
  (let [keywords-rule (str "keyword = \"" (str/join " \"\n        / \"" keywords) " \"")]
    (str (-> grammar
           ;; We add negative lookahead to avoid matching keywords in simple labels
           (str/replace #"simple-label = (.*)" "simple-label = !keyword ($1)")
           ;; Grammar apparently doesn't allow lowercase hashes
           (str/replace #"HEXDIG = " "HEXDIG = \"a\" /  \"b\" / \"c\" / \"d\" / \"e\" / \"f\" / "))
         "\n"
         keywords-rule)))

(def dhall-parser
  "Dhall parser generated by Instaparse from the ABNF grammar"
  (insta/parser (patch-grammar grammar)
                :input-format :abnf
                :start :complete-expression
                :output-format :enlive
                :string-ci false))

(defn clean
  "Cut the names of the attrs of the tree
  TODO: save the meta!"
  [tree]
  (if (map? tree)
    {:c (map clean (:content tree))
     ;;:a (:attrs tree)
     :t (:tag tree)}
    tree))

(defn parse
  "Parses the Dhall input, on success returns an Enlive-style tree.
  Throws on a failed parse."
  [dhall-string]
  (let [parsed (dhall-parser dhall-string)]
    (if (insta/failure? parsed)
      (fail/parsing! parsed)
      (clean parsed))))

;;
;; Utils
;;
(declare expr)

(defn first-child-expr
  "Folds the current expression into its first child"
  [e]
  (expr (-> e :c first)))

(defn children?
  "True if there is more than one child"
  [e]
  (> (count (:c e)) 1))

(defn compact
  "Given a parse tree, it will compact all the text in it,
  and return a single string"
  [tree]
  (cond
    (map? tree) (apply str (mapv compact (:c tree)))
    (string? tree) tree
    (seqable? tree) (apply str (mapv compact tree))
    :else tree))

;;
;; Parse Tree -> Expression Tree
;;

(defmulti expr
  "Takes an enlive parse tree, and constructs a tree of
  objects implementing IExpr"
  :t)


;;
;; Rules that we eliminate as not needed
;;

(defmethod expr :complete-expression [e]
  (expr (-> e :c second)))

(defmethod expr :operator-expression [e]
  (first-child-expr e))

(defmethod expr :import-expression [e]
  (first-child-expr e))

;;
;; Import rules
;;

(defmethod expr :import [{:keys [c]}]
  (let [import (expr (first c))
        mode (if (> (count c) 1) ;; If yes, we have a "as Text"
               ;; FIXME: other imports modes go here, maybe make it more robust
               :text
               :code)]
    (assoc import :mode mode)))

(defmethod expr :import-hashed [{:keys [c]}]
  (let [import (expr (first c))
        hash?  (when (> (count c) 1) ;; If yes, we have a hash
                 (expr (second c)))]
    (assoc import :hash? hash?)))

(defmethod expr :hash [{:keys [c]}]
  (-> c
     (compact)
     (subs 7) ;; Cut the "sha256:" prefix here
     (str/trim)))

(defmethod expr :import-type [{:keys [c]}]
  (-> c first expr))

(defmethod expr :missing [_]
  (map->Import {:data (->Missing)}))

(defmethod expr :env [{:keys [c]}]
  (let [envname (compact (nth c (if (string? (second c)) 2 1)))
        env (->Env envname)]
    (map->Import {:data env})))

(defmethod expr :local [{:keys [c]}]
  (let [raw-c (-> c first :c)
        relative? (string? (first raw-c))
        prefix? (when relative?
                  (first raw-c))
        compact-path-component #(-> % :c rest compact)
        directory (->> (nth raw-c (if relative? 1 0))
                     :c
                     (map compact-path-component)
                     reverse) ;; Yes, we store the path components reversed
        file (-> raw-c
                (nth (if relative? 2 1))
                :c first
                compact-path-component)
        local (->Local prefix? directory file)]
    (map->Import {:data local})))

(defmethod expr :http [{:keys [c]}]
  (let [headers? (case (count c)
                   4   (-> c (nth 3) expr)
                   6   (-> c (nth 4) expr)
                   nil)
        headers? (when headers?
                   (assoc headers? :mode :code)) ;; FIXME: we should have a smart constructor
        remote (map->Remote
                 {:url      (-> c first expr)
                  :headers? headers?})]
    (map->Import {:data remote})))

(defmethod expr :http-raw [{:keys [c]}]
  (let [compact-next (fn [char coll]
                       (some->>
                         coll
                         (partition 2 1)
                         (filter (fn [[a b]] (= char a)))
                         (first)
                         (second)
                         (compact)))
        base     (uri "")
        scheme   (-> c first compact)
        query    (compact-next "?" c)
        fragment (compact-next "#" c)

        host      (-> c (nth 2) :c)
        port      (compact-next ":" host)
        userinfo? (= "@" (second host))
        authority (-> host
                     (nth (if userinfo? 2 0))
                     compact)
        [user password] (when userinfo?
                          ((juxt (partial take-while (partial not= ":"))
                                 (partial drop-while (partial not= ":")))
                           (-> host first :c)))
        user     (when (seq user)     (compact user))
        password (when (seq password) (compact (rest password)))
        path (str (-> c (nth 3) compact)
                  (-> c (nth 4) compact))]
    (assoc base
           :scheme scheme
           :host authority
           :user user
           :password password
           :path path
           :port port
           :query query
           :fragment fragment)))


;;
;; Useful rules that parse the pure subset of the language
;;

(defmethod expr :expression [{:keys [c t]}]
  (let [first-tag (-> c first :t)]
    (case first-tag
      :lambda (->Lam (expr (nth c 2))
                     (expr (nth c 4))
                     (expr (nth c 7)))
      :if     (->BoolIf (expr (nth c 1))
                        (expr (nth c 3))
                        (expr (nth c 5)))
      :let    (loop [left c
                     bindings []]
                (if (= (count left) 2)
                  (->Let bindings (expr (second left)))
                  (if-let [type? (when (= :colon (:t (nth left 2)))
                                   (expr (nth left 3)))]
                    (recur
                      (nthrest left 6)
                      (conj bindings (->Binding
                                       (expr (nth left 1))
                                       type?
                                       (expr (nth left 5)))))
                    (recur
                      (nthrest left 4)
                      (conj bindings (->Binding
                                       (expr (nth left 1))
                                       nil
                                       (expr (nth left 3))))))))
      :forall (->Pi (expr (nth c 2))
                    (expr (nth c 4))
                    (expr (nth c 7)))
      :operator-expression  (->Pi "_"
                                  (expr (nth c 0))
                                  (expr (nth c 2)))
      :annotated-expression (-> c first expr))))

(defmethod expr :annotated-expression [{:keys [c t]}]
  (let [first-tag (-> c first :t)]
    (case first-tag
      :merge               (->Merge
                             (-> c (nth 1)     expr)
                             (-> c (nth 2)     expr)
                             (if (>= (count c) 5)
                               (-> c (nth 4) expr)
                               nil))
      :open-bracket        (-> c second expr)
      :operator-expression (if (> (count c) 1)
                             (->Annot
                               (expr (nth c 0))
                               (expr (nth c 2)))
                             (expr (first c))))))

(defmethod expr :empty-collection [{:keys [c t]}]
  (let [typ       (-> c last expr)]
    (if (= :List (-> c (nth 2) :t))
      (->ListLit     typ [])
      (->OptionalLit typ nil))))

(defmethod expr :non-empty-optional [{:keys [c t]}]
  (let [typ   (-> c last  expr)
        value (-> c first expr)]
    (->OptionalLit typ value)))

(defmethod expr :reserved-raw [e]
  (let [first-tag (-> e :c first :t)]
    (case first-tag
      :Bool-raw     (->BoolT)
      :Optional-raw (->OptionalT)
      :None-raw     (->None)
      :Natural-raw  (->NaturalT)
      :Integer-raw  (->IntegerT)
      :Double-raw   (->DoubleT)
      :Text-raw     (->TextT)
      :List-raw     (->ListT)
      :True-raw     (->BoolLit true)
      :False-raw    (->BoolLit false)
      :NaN-raw      (->DoubleLit Double/NaN)
      :Infinity-raw (->DoubleLit Double/POSITIVE_INFINITY)
      :Type-raw     (->Const :type)
      :Kind-raw     (->Const :kind)
      :Sort-raw     (->Const :sort))))

(defmethod expr :reserved-namespaced-raw [e]
  (let [first-tag (-> e :c first :t)]
    (case first-tag
      :Natural-fold-raw      (->NaturalFold)
      :Natural-build-raw     (->NaturalBuild)
      :Natural-isZero-raw    (->NaturalIsZero)
      :Natural-even-raw      (->NaturalEven)
      :Natural-odd-raw       (->NaturalOdd)
      :Natural-toInteger-raw (->NaturalToInteger)
      :Natural-show-raw      (->NaturalShow)
      :Integer-toDouble-raw  (->IntegerToDouble)
      :Integer-show-raw      (->IntegerShow)
      :Double-show-raw       (->DoubleShow)
      :List-build-raw        (->ListBuild)
      :List-fold-raw         (->ListFold)
      :List-length-raw       (->ListLength)
      :List-head-raw         (->ListHead)
      :List-last-raw         (->ListLast)
      :List-indexed-raw      (->ListIndexed)
      :List-reverse-raw      (->ListReverse)
      :Optional-fold-raw     (->OptionalFold)
      :Optional-build-raw    (->OptionalBuild)
      :Text-show-raw         (->TextShow))))

(defn identifier [e]
  (let [children (:c e)
        ;; if we have  a simple identifier, the "prefix" is just the label
        ;; if instead it's a prefixed identifier, the prefix is the reserved word
        prefix (if (= :identifier (:t e))
                 (->> children first expr)
                 (->> children first :c first :c (apply str)))
        ;; at the end of `children` there might be a DeBrujin index
        maybe-index (-> children butlast last)
        index? (= :natural-literal-raw (:t maybe-index))
        index (if index?
                (-> maybe-index :c first :c first read-string)
                0)
        ;; the label is the rest of the chars
        ;; if it's an identifier without prefix this is going to
        ;; be an empty string, so all good
        label (->> children
                 rest
                 (drop-last (if index? 3 1))
                 compact)]
    (->Var (str prefix label) index)))

(defmethod expr :identifier [e]
  (identifier e))

(defmethod expr :identifier-reserved-namespaced-prefix [e]
  (identifier e))

(defmethod expr :identifier-reserved-prefix [e]
  (identifier e))

(defmacro defexpr*
  "Generalize `defmethod` for the cases in which we need to do
  something like:
  - if there's one remove this tag
  - if there's multiple create an `Expr a b` and recur with left-precedence"
  [parser-tag record-class separator-tag]
  (let [expr-constructor (symbol (str "->" record-class))]
    `(defmethod expr ~parser-tag [e#]
       (if (> (count (:c e#)) 1)
         (let [exprs# (remove #(or (= ~separator-tag (:t %))
                                   (= :whitespace-chunk (:t %)))
                              (:c e#))]
           (loop [more# (nnext exprs#)
                  start# (~expr-constructor
                           (expr (first exprs#))
                           (expr (second exprs#)))]
             (if (empty? more#)
               start#
               (recur (rest more#)
                      (~expr-constructor start# (expr (first more#)))))))
         (expr (-> e# :c first))))))


(defexpr* :import-alt-expression    ImportAlt    :import-alt)
(defexpr* :or-expression            BoolOr       :or)
(defexpr* :plus-expression          NaturalPlus  :plus)
(defexpr* :text-append-expression   TextAppend   :text-append)
(defexpr* :list-append-expression   ListAppend   :list-append)
(defexpr* :and-expression           BoolAnd      :and)
(defexpr* :combine-expression       Combine      :combine)
(defexpr* :prefer-expression        Prefer       :prefer)
(defexpr* :combine-types-expression CombineTypes :combine-types)
(defexpr* :times-expression         NaturalTimes :times)
(defexpr* :equal-expression         BoolEQ       :double-equal)
(defexpr* :not-equal-expression     BoolNE       :not-equal)

(defmethod expr :application-expression [{:keys [c t]}]
  (if (> (count c) 1)
    (let [exprs (remove #(= :whitespace-chunk (:t %)) c)
          constructors? (= :constructors (-> c first :t))
          some? (= :Some (-> c first :t))]
      (loop [more (nnext exprs)
             app (cond
                   constructors?
                   (->Constructors (expr (second exprs)))

                   some?
                   (->Some (expr (second exprs)))

                   :else
                   (->App
                     (expr (first exprs))
                     (expr (second exprs))))]
        (if (empty? more)
          app
          (recur (rest more)
                 (->App app (expr (first more)))))))
    (expr (-> c first))))

(defmethod expr :selector-expression [e]
  (if (children? e)
    (let [exprs (remove #(= :dot (:t %)) (:c e))
          base  (expr (first exprs))
          labels? (fn [l] (= :labels (:t l)))]
      (loop [more (nnext exprs)
             sel ((if (labels? (second exprs))
                    ->Project
                    ->Field)
                  base
                  (expr (second exprs)))]
        (if (empty? more)
          sel
          (recur (rest more)
                 ((if (labels? (first more))
                    ->Project
                    ->Field)
                  sel
                  (expr (first more)))))))
    (-> e :c first expr))) ;; Otherwise we go to the primitive expression

(defmethod expr :labels [{:keys [c t]}]
  (->> c
     rest
     (take-nth 2)
     (mapv expr)))

(defmethod expr :primitive-expression [e]
  (let [first-tag (-> e :c first :t)
        children (:c e)]
    (case first-tag
      :double-literal  (let [d (-> children first compact read-string)]
                         (if (or (= d Double/NEGATIVE_INFINITY)
                                 (= d Double/POSITIVE_INFINITY))
                           (fail/double-out-of-bounds! (-> children first compact) e)
                           (->DoubleLit d)))
      :natural-literal (-> children first compact read-string ->NaturalLit)
      :integer-literal (-> children first compact read-string ->IntegerLit)
      nil              (->DoubleLit Double/NEGATIVE_INFINITY) ;; TODO: better way to do this?
      :text-literal                          (-> children first expr)
      :open-brace                            (-> children second expr)
      :open-angle                            (-> children second expr)
      :non-empty-list-literal                (-> children first expr)
      :identifier-reserved-namespaced-prefix (-> children first expr)
      :reserved-namespaced                   (-> children first :c first expr)
      :identifier-reserved-prefix            (-> children first expr)
      :reserved                              (-> children first :c first expr)
      :identifier                            (-> children first expr)
      :open-parens                           (-> children second expr))))

(defmethod expr :union-type-or-literal [e]
  (let [first-tag (-> e :c first :t)]
    (case first-tag
      :non-empty-union-type-or-literal
      (let [[k v kvs] (->> e :c first expr)]
        (if (and k v) ;; if we actually have a value, we have a Union Literal
          (->UnionLit k v kvs)
          (->UnionT kvs)))
      (->UnionT {})))) ;; Empty union type

;; This should return a vector `[k v kvs]`,
;; where `k` is the value key, `v` is its value,
;; and `kvs` are the remaining types.
;; `k` and `v` can be `nil`, and that means it's a Union type
(defmethod expr :non-empty-union-type-or-literal [e]
  (let [children (:c e)
        label    (-> children first expr)
        literal? (= :equal (-> children second :t))
        value    (-> children (nth 2) expr)
        more?    (> (count children) 3)]
    (if literal?
      ;; If we got our key:value here, we just
      ;; merge into the kvs the remaining type
      [label value (if more?
                     (->> (-> e :c (nthrest 3))
                        (partition 4)
                        (mapv (fn [[bar label colon expr']]
                                {(expr label)
                                 (expr expr')}))
                        (apply merge))
                     {})]
      ;; Otherwise our label:value is another type declaration,
      ;; and we recur into the next types (if any), merging the
      ;; current label:value into the next kvs
      (if more?
        (let [[k v kvs] (-> children (nth 4) expr)]
          [k v (merge kvs {label value})])
        [nil nil {label value}]))))

(defmethod expr :record-type-or-literal [e]
  (let [first-tag (-> e :c first :t)]
    (case first-tag
      :equal                            (->RecordLit {}) ;; Empty record literal
      :non-empty-record-type-or-literal (-> e :c first expr)
      (->RecordT {}))))                                  ;; Empty record type

(defmethod expr :non-empty-record-type-or-literal [e]
  (let [first-label (-> e :c first expr)
        other-vals (-> e :c second)
        record-literal? (= (:t other-vals) :non-empty-record-literal)
        [first-val other-kvs] [(-> other-vals :c second expr)
                               (->> (-> other-vals :c (nthrest 2))
                                  (partition 4)
                                  (mapv (fn [[comma label sep expr']]
                                          {(expr label)
                                           (expr expr')}))
                                  (apply merge))]]
    ((if record-literal?
       ->RecordLit
       ->RecordT)
     (merge {first-label first-val} other-kvs))))

(defmethod expr :label [e]
  (let [quoted? (-> e :c first string?) ;; a quoted label is preceded by `
        actual-label ((if quoted? second first) (:c e))
        str-label (->> actual-label :c
                      (mapv (fn [ch]
                              (if (string? ch)
                                ch
                                (-> ch :c first))))
                      (apply str))]
     str-label))

(defmethod expr :non-empty-list-literal [e]
  ;; Here we always pass the type as nil, because it's a non empty list,
  ;; and the eventual type annotation has this wrapped in an Annot
  (let [vals (->> e :c rest (take-nth 2) (mapv expr))]
    (->ListLit nil vals)))


(defn multiline->text-lit [lines]
  (let [min-indent (apply
                     min
                     (mapv
                       (fn [[first-el & others]]
                         (if (string? first-el)
                           (count (take-while #(= % \space) first-el))
                           0))
                       lines))
        strip-indent (fn [line]
                       (if (and (> min-indent 0) (string? (first line)))
                         (update line 0 #(subs % min-indent))
                         line))]
    (->> lines
       (mapcat strip-indent)
       (compact-chunks)
       (into []))))


;; From https://groups.google.com/forum/#!topic/clojure/JrnYQp84Dig
(defn hex->num [#^String s]
  (Integer/parseInt (.substring s 2) 16))


(defmethod expr :text-literal [e]
  (let [first-tag (-> e :c first :t)
        children (:c e)]
    (->TextLit
      (if (= first-tag :double-quote-literal)
        ;; If it's a double quoted string, we fold on the children,
        ;; so that we collapse the contiguous strings in a single chunk,
        ;; while skipping the interpolation expressions
        (loop [children (-> children first :c rest butlast) ;; Skip the quotes
               acc ""
               chunks []]
          (if (seq children)
            (let [chunk (first children)
                  content (:c chunk)]
              (condp = (first content)
                "${" ;; If we match the interpolation, empty the accumulator and concat it
                (recur (rest children)
                       ""
                       (conj chunks acc (expr (nth content 1))))

                "\\" ;; Or we might match the escape slash, so we emit some special chars
                (let [new-char (condp = (second content)
                                 "\"" "\""
                                 "$"  "$"
                                 "\\" "\\"
                                 "/"  "/"
                                 "b"  "\b"
                                 "f"  "\f"
                                 "n"  "\n"
                                 "r"  "\r"
                                 "t"  "\t"
                                 ;; Otherwise we're reading in a \uXXXX char
                                 (->> (nthrest content 2)
                                    compact
                                    (str "0x")
                                    hex->num
                                    char))]
                  (recur (rest children)
                         (str acc new-char)
                         chunks))

                ;; Otherwise we just attach to the accumulator, since it's a normal char
                (recur (rest children)
                       (str acc (first content))
                       chunks)))
            ;; If we have no children left to process,
            ;; we return the chunks we have, plus the accomulator
            (conj chunks acc)))
        ;; Otherwise it's a single quote literal,
        ;; so we recur over the children until we find an ending literal.
        ;; As above, we make expressions out of interpolation syntax
        (loop [children (-> children first :c (nth 2) :c)
               acc ""
               line-chunks []
               lines []]
          (if (= children ["''"])
            (multiline->text-lit (conj lines (conj line-chunks acc)))
            (condp = (first children)
              "${" ;; Interpolation check - reset accumulator, concat to chunks
              (recur (-> children (nth 3) :c)
                     ""
                     (conj line-chunks acc (expr (second children)))
                     lines)

              "''${" ;; Escaping the interpolation symbols
              (recur (-> children second :c)
                     (str acc "${")
                     line-chunks
                     lines)

              "'''" ;; Escaping the single quotes
              (recur (-> children second :c)
                     (str acc "''")
                     line-chunks
                     lines)

              {:c '("\n") :t :end-of-line} ;; newline, reset acc and line-chunks
              (recur (-> children second :c)
                     ""
                     []
                     (conj lines (conj line-chunks (str acc "\n"))))

              {:c '("\t") :t :tab} ;; tab char, we leave it alone
              (recur (-> children second :c)
                     (str acc "\t")
                     line-chunks
                     lines)

              ;; Otherwise we just add the string to the accumulator and recur
              (recur (-> children second :c)
                     (str acc (first children))
                     line-chunks
                     lines))))))))

;; Default case, we end up here when there is no matches
(defmethod expr :default [e]
  (fail/ast-building! e))
