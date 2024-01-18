(ns xtdb.api
  "This namespace is the main public Clojure API to XTDB.

  It lives in the `com.xtdb/xtdb-api` artifact - include this in your dependency manager of choice.

  To start a node, you will additionally need:

  * `xtdb.node`, for an in-process node.
  * `xtdb.client`, for a remote client."

  (:require [clojure.spec.alpha :as s]
            [xtdb.backtick :as backtick]
            [xtdb.error :as err]
            [xtdb.protocols :as xtp]
            [xtdb.serde :as serde]
            [xtdb.time :as time]
            [xtdb.xtql.edn :as xtql.edn])
  (:import (java.io Writer)
           java.util.concurrent.ExecutionException
           java.util.function.Function
           java.util.List
           java.util.Map
           [java.util.stream Stream]
           (xtdb.api IXtdb IXtdbSubmitClient TransactionKey)
           (xtdb.api.query Basis XtqlQuery QueryOptions)
           (xtdb.api.tx TxOp TxOp$HasArgs TxOp$HasValidTimeBounds TxOptions)
           xtdb.types.ClojureForm))

(defmacro ^:private rethrowing-cause [form]
  `(try
     ~form
     (catch ExecutionException e#
       (throw (.getCause e#)))))

(defn- expect-instant [instant]
  (when-not (s/valid? ::time/datetime-value instant)
    (throw (err/illegal-arg :xtdb/invalid-date-time
                            {::err/message "expected date-time"
                             :timestamp instant})))

  (time/->instant instant))

(defn ->ClojureForm [form]
  (ClojureForm. form))

(defmethod print-dup ClojureForm [^ClojureForm clj-form ^Writer w]
  (.write w "#xt/clj-form ")
  (print-method (.form clj-form) w))

(defmethod print-method ClojureForm [clj-form w]
  (print-dup clj-form w))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn q&
  "asynchronously query an XTDB node.

  - query: either an XTQL or SQL query.
  - opts:
    - `:basis`: see 'Transaction Basis'
    - `:args`: arguments to pass to the query.

  For example:

  (q& node '(from ...))

  (q& node '(from :foo [{:a $a, :b $b}])
      {:a a-value, :b b-value})

  (q& node \"SELECT foo.id, foo.v FROM foo WHERE foo.id = 'my-foo'\")
  (q& node \"SELECT foo.id, foo.v FROM foo WHERE foo.id = ?\" {:args [foo-id]})

  Please see XTQL/SQL query language docs for more details.

  This function returns a CompletableFuture containing the results of its query as a vector of maps

  Transaction Basis:

  In XTDB there are a number of ways to control at what point in time a query is run -
  this is done via a basis map optionally supplied as part of the query map.

  In the case a basis is not provided the query is guaranteed to run sometime after
  the latest transaction submitted by this connection/node.

  Alternatively a basis map containing reference to a specific transaction can be supplied,
  in this case the query will be run exactly at that transaction, ensuring the repeatability of queries.

  This tx key is the same map returned by `submit-tx`.

  (q& node '(from ...)
      {:basis {:at-tx tx}})

  Additionally a tx timeout can be supplied to the query map, which if after the specified duration
  the query's requested basis is not complete the query will be cancelled.

  (q& node '(from ...)
      {:basis-timeout (Duration/ofSeconds 1)})"

  (^java.util.concurrent.CompletableFuture [node q+args] (q& node q+args {}))

  (^java.util.concurrent.CompletableFuture
   [node query opts]
   (let [opts (-> (into {:default-all-valid-time? false} opts)
                  (time/after-latest-submitted-tx node))]

     (-> (xtp/open-query& node query opts)
         (.thenApply
          (reify Function
            (apply [_ res]
              (with-open [^Stream res res]
                (vec (.toList res))))))))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn q
  "query an XTDB node.

  - query: either an XTQL or SQL query.
  - opts:
    - `:basis`: see 'Transaction Basis'
    - `:args`: arguments to pass to the query.

  For example:

  (q node '(from ...))

  (q node '(from :foo [{:a $a, :b $b}])
      {:a a-value, :b b-value})

  (q node \"SELECT foo.id, foo.v FROM foo WHERE foo.id = 'my-foo'\")
  (q node \"SELECT foo.id, foo.v FROM foo WHERE foo.id = ?\" {:args [foo-id]})

  Please see XTQL/SQL query language docs for more details.

  This function returns the results of its query as a vector of maps

  Transaction Basis:

  In XTDB there are a number of ways to control at what point in time a query is run -
  this is done via a basis map optionally supplied as part of the query map.

  In the case a basis is not provided the query is guaranteed to run sometime after
  the latest transaction submitted by this connection/node.

  Alternatively a basis map containing reference to a specific transaction can be supplied,
  in this case the query will be run exactly at that transaction, ensuring the repeatability of queries.

  This tx reference (known as a TransactionKey) is the same map returned by submit-tx

  (q node '(from ...)
     {:basis {:at-tx tx}})

  Additionally a Basis Timeout can be supplied to the query map, which if after the specified duration
  the query's requested basis is not complete the query will be cancelled.

  (q node '(from ...)
     {:tx-timeout (Duration/ofSeconds 1)})"
  ([node q+args]
   (-> @(q& node q+args)
       (rethrowing-cause)))

  ([node q+args opts]
   (-> @(q& node q+args opts)
       (rethrowing-cause))))

(extend-protocol xtp/PSubmitNode
  IXtdbSubmitClient
  (submit-tx& [this tx-ops]
    (xtp/submit-tx& this tx-ops nil))

  (submit-tx& [this tx-ops opts]
    (.submitTxAsync this
                    (cond
                      (instance? TxOptions opts) opts
                      (nil? opts) (TxOptions.)
                      (map? opts) (let [{:keys [system-time default-tz default-all-valid-time?]} opts]
                                    (TxOptions. (some-> system-time expect-instant)
                                                default-tz
                                                (boolean default-all-valid-time?))))
                    (into-array TxOp tx-ops))))

(extend-protocol xtp/PNode
  IXtdb
  (open-query& [this query {:keys [args after-tx basis tx-timeout default-tz default-all-valid-time? explain? key-fn], :or {key-fn :kebab-case-keyword}}]
    (let [query-opts (-> (QueryOptions/queryOpts)
                         (cond-> (map? args) (.args ^Map args)
                                 (vector? args) (.args ^List args)
                                 after-tx (.afterTx after-tx)
                                 basis (.basis (Basis. (:at-tx basis) (:current-time basis)))
                                 default-tz (.defaultTz default-tz)
                                 tx-timeout (.txTimeout tx-timeout)
                                 (some? default-all-valid-time?) (.defaultAllValidTime default-all-valid-time?)
                                 (some? explain?) (.explain explain?))

                         (.keyFn (serde/read-key-fn key-fn))

                         (.build))]
      (if (string? query)
        (.openQueryAsync this ^String query query-opts)

        (let [^XtqlQuery query (cond
                                 (instance? XtqlQuery query) query
                                 (seq? query) (xtql.edn/parse-query query)
                                 :else (throw (err/illegal-arg :unknown-query-type {:query query, :type (type query)})))]
          (.openQueryAsync this query query-opts))))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn submit-tx&
  "Writes transactions to the log for processing. Non-blocking.

  tx-ops: XTQL/SQL transaction operations.
    [(xt/put :table {:xt/id \"my-id\", ...})
     (xt/delete :table \"my-id\")]

    [(-> (xt/sql-op \"INSERT INTO foo (xt$id, a, b) VALUES ('foo', ?, ?)\")
         (xt/with-op-args [0 1]))

     (-> (xt/sql-op \"INSERT INTO foo (xt$id, a, b) VALUES ('foo', ?, ?)\")
         (xt/with-op-args [2 3] [4 5] [6 7]))

     (xt/sql-op \"UPDATE foo SET b = 1\")]

  Returns a CompletableFuture containing a map with details about
  the submitted transaction, including system-time and tx-id.

  opts (map):
   - :system-time
     overrides system-time for the transaction,
     mustn't be earlier than any previous system-time

   - :default-tz
     overrides the default time zone for the transaction,
     should be an instance of java.time.ZoneId"
  (^java.util.concurrent.CompletableFuture [node tx-ops] (submit-tx& node tx-ops {}))
  (^java.util.concurrent.CompletableFuture [node tx-ops tx-opts]
   (xtp/submit-tx& node tx-ops tx-opts)))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn submit-tx
  "Writes transactions to the log for processing

  tx-ops: XTQL/SQL style transactions.
    [(xt/put :table {:xt/id \"my-id\", ...})
     (xt/delete :table \"my-id\")]

    [(-> (xt/sql-op \"INSERT INTO foo (xt$id, a, b) VALUES ('foo', ?, ?)\")
         (xt/with-op-args [0 1]))

     (-> (xt/sql-op \"INSERT INTO foo (xt$id, a, b) VALUES ('foo', ?, ?)\")
         (xt/with-op-args [2 3] [4 5] [6 7]))

     (xt/sql-op \"UPDATE foo SET b = 1\")]

  Returns a map with details about the submitted transaction, including system-time and tx-id.

  opts (map):
   - :system-time
     overrides system-time for the transaction,
     mustn't be earlier than any previous system-time

   - :default-tz
     overrides the default time zone for the transaction,
     should be an instance of java.time.ZoneId"

  (^TransactionKey [node tx-ops] (submit-tx node tx-ops {}))
  (^TransactionKey [node tx-ops tx-opts]
   (-> @(submit-tx& node tx-ops tx-opts)
       (rethrowing-cause))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn status
  "Returns the status of this node as a map,
  including details of both the latest submitted and completed tx"
  [node]
  (xtp/status node))

(def ^:private eid? (some-fn uuid? integer? string? keyword?))

(def ^:private table? keyword?)

(defn- expect-table-name ^String [table-name]
  (when-not (table? table-name)
    (throw (err/illegal-arg :xtdb.tx/invalid-table
                            {::err/message "expected table name" :table table-name})))

  (str (symbol table-name)))

(defn- expect-eid [eid]
  (if-not (eid? eid)
    (throw (err/illegal-arg :xtdb.tx/invalid-eid
                            {::err/message "expected xt/id", :xt/id eid}))
    eid))

(defn- expect-doc [doc]
  (when-not (map? doc)
    (throw (err/illegal-arg :xtdb.tx/expected-doc
                            {::err/message "expected doc map", :doc doc})))
  (expect-eid (or (:xt/id doc) (get doc "xt/id")))

  (-> doc
      (update-keys (fn [k]
                     (cond-> k
                       (keyword? k) (-> symbol str))))))

(defn put
  "Returns a put operation for passing to `submit-tx`.

  `table`: table to put the document into.
  `docs`: documents to put.
    * Each must contain an `:xt/id` attribute - currently string, UUID, integer or keyword.

  * `put` operations can be passed to `during`, `starting-from` or `until` to set the effective valid time of the operation.
  * To insert documents using a query, use `insert-into`"
  [table & docs]
  (TxOp/put (expect-table-name table) ^List (mapv expect-doc docs)))

(defn- expect-fn-id [fn-id]
  (if-not (eid? fn-id)
    (throw (err/illegal-arg :xtdb.tx/invalid-fn-id {::err/message "expected fn-id", :fn-id fn-id}))
    fn-id))

(defn- expect-tx-fn [tx-fn]
  (or tx-fn
      (throw (err/illegal-arg :xtdb.tx/invalid-tx-fn {::err/message "expected tx-fn", :tx-fn tx-fn}))))

(defn put-fn
  "Returns an operation that registers a transaction function.

  * `fn-id`: id of the function.
  * `tx-fn`: transaction function body.
    * Transaction functions are run using the Small Clojure Interpreter (SCI).
    * Within transaction functions, the following built-ins are available:
      * `q`: (function, `(q query opts)`): a function to run an XTQL/SQL query.
         See `q` for options.
      * `*current-tx*`: the current transaction key (`:tx-id`, `:sys-time`)
      * `xt/put`, `xt/delete`, etc: transaction operation builders from this namespace."
  [fn-id tx-fn]
  (TxOp/putFn (expect-fn-id fn-id) (expect-tx-fn tx-fn)))

(defn delete
  "Returns a delete operation for passing to `submit-tx`.

  `table`: table to delete from.
  `id`: id of the document to delete.

  * `delete` operations can be passed to `during`, `starting-from` or `until` to set the effective valid time of the operation.
  * To delete documents that match a query, use `delete-from`"
  [table id]
  (TxOp/delete (expect-table-name table) (expect-eid id)))

(defn during
  "Adapts the given transaction operation to take effect (in valid time) between `from` and `until`.

  `from`, `until`: j.u.Date, j.t.Instant or j.t.ZonedDateTime"
  [^TxOp$HasValidTimeBounds tx-op from until]

  (.during tx-op (expect-instant from) (expect-instant until)))

(defn starting-from
  "Adapts the given transaction operation to take effect (in valid time) from `from` until the end of time.

  `from`, `until`: j.u.Date, j.t.Instant or j.t.ZonedDateTime"
  [^TxOp$HasValidTimeBounds tx-op from]

  (.startingFrom tx-op (expect-instant from)))

(defn until
  "Adapts the given transaction operation to take effect (in valid time) from the time of the transaction until `until`.

  `until`: j.u.Date, j.t.Instant or j.t.ZonedDateTime"
  [^TxOp$HasValidTimeBounds tx-op until]
  (.until tx-op (expect-instant until)))

(defn erase
  "Returns an erase operation for passing to `submit-tx`.

  `table`: table to erase from.
  `id`: id of the document to erase.

  * To erase documents that match a query, use `erase-from`"
  [table id]
  (TxOp/erase (expect-table-name table) (expect-eid id)))

(defn call
  "Returns a transaction-function call operation for passing to `submit-tx`.

  See also: `put-fn`"
  [f & args]
  (TxOp/call (expect-fn-id f) (or args [])))

(defn sql-op
  "Returns an SQL DML operation for passing to `submit-tx`

  * `sql`: SQL string - e.g. `\"INSERT INTO ...\"`, `\"UPDATE ...\"`

    See https://docs.xtdb.com/reference/main/sql/txs.html for more details.

  * You can pass the result from this operation to `with-op-args` to supply values for any parameters in the query."
  [sql]
  (if-not (string? sql)
    (throw (err/illegal-arg :xtdb.tx/expected-sql
                            {::err/message "Expected SQL query",
                             :sql sql}))

    (TxOp/sql sql)))

(defn with-op-args
  "Adds the given (variadic) argument rows to the operation.

  e.g.
  (-> (xt/update-table :users {:bind [{:xt/id $uid} version], :set {:version (inc version)}})
      (xt/with-op-args {:uid \"james\"}
                       {:uid \"dave\"}))"
  [^TxOp$HasArgs op & args]
  (.withArgs op ^List args))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn with-op-arg-rows
  "Adds the given (vector of) argument rows to the operation.

  e.g.
  (-> (xt/update-table :users {:bind [{:xt/id $uid} version], :set {:version (inc version)}})
      (xt/with-op-args [{:uid \"james\"}, {:uid \"dave\"}]))"
  [^TxOp$HasArgs op arg-rows]
  (.withArgs op ^List arg-rows))

(defn insert-into
  "Returns an insert operation for passing to `submit-tx`.

  When evaluated, insert operations run the query, and insert every returned row as a document into the specified table.
    * Every row must return an `:xt/id` key - currently string, UUID, integer or keyword.
    * To insert rows for different valid time periods, return `:xt/valid-from` and/or `:xt/valid-to` from the query.

  `table-name` (keyword): the table to insert documents into
  `xtql-query`: query to execute

  * You can pass the result from this operation to `with-op-args` to supply values for any parameters in the query.
  * To put a single document into a table, use `put`."

 [table-name xtql-query]
  (xtql.edn/parse-dml (list 'insert table-name xtql-query)))

(defn update-table
  "Returns an update operation for passing to `submit-tx`.

  `table-name` (keyword): the table to update

  `bind`: a vector of bindings (e.g. `[{:xt/id $uid}]`)
    * If bind specs are not specified, will update every document in the table.

  `set`: a map of fields to update (e.g. `{:set {:version (inc version)}}`)
    * The values of the map can be any XTQL expression.
    * All variables bound in either `bind` or `unify-clauses` are in scope

  `for-valid-time` (optional): to specify the effective valid-time period of the updates
    Either:

    * `(from <valid-from>)`
    * `(to <valid-to>)`
    * `(in <valid-from> <valid-to>)`

    If not provided, defaults to 'from the current-time of the transaction'.

  `unify-clauses` (optional): additional clauses to unify with the above bindings

  * You can pass the result from this operation to `with-op-args` to supply values for any parameters in the query."

  {:arglists '([table-name {:keys [bind set for-valid-time]} & unify-clauses])}
  #_{:clj-kondo/ignore [:unused-binding]}
  [table-name opts & unify-clauses]

  (xtql.edn/parse-dml (list* 'update table-name opts unify-clauses)))

(defn delete-from
  "Returns a delete operation for passing to `submit-tx`.

  `table-name` (keyword): the table to delete from

  `bind`, `bind-specs`: a vector of bindings (e.g. `[{:xt/id $uid}]`)
    * If bind specs are not specified, will delete every document in the table.

  `for-valid-time` (optional): to specify the effective valid-time period of the deletes
    Either:

    * `'(from <valid-from>)`
    * `'(to <valid-to>)`
    * `'(in <valid-from> <valid-to>)`

    If not provided, defaults to 'from the current-time of the transaction'.

  `unify-clauses` (optional): additional clauses to unify with the above bindings

  * You can pass the result from this operation to `with-op-args` to supply values for any parameters in the query.
  * To delete a single document by `:xt/id`, use `delete`"

  {:arglists '([table-name bind-specs & unify-clauses]
               [table-name {:keys [bind]} & unify-clauses])}
  [table-name bind-or-opts & unify-clauses]
  (xtql.edn/parse-dml (list* 'delete table-name bind-or-opts unify-clauses)))

(defn erase-from
  "Returns an erase operation for passing to `submit-tx`.

  `table-name` (keyword): the table to erase from
  `bind`, `bind-specs`: a vector of bindings (e.g. `[{:xt/id $uid}]`)
    * If bind specs are not specified, will erase every document in the table.
  `unify-clauses` (optional): additional clauses to unify with the above bindings

  * You can pass the result from this operation to `with-op-args` to supply values for any parameters in the query.
  * To erase a single document by `:xt/id`, use `erase`"

  {:arglists '([table-name bind-specs & unify-clauses]
               [table-name {:keys [bind]} & unify-clauses])}

  [table-name bind-or-opts & unify-clauses]

  (xtql.edn/parse-dml (list* 'erase table-name bind-or-opts unify-clauses)))

(defn assert-exists
  "Returns an assert-exists operation for passing to `submit-tx`.

  When it's evaluated, this transaction operation runs the given query, and aborts the transaction iff the query doesn't return any rows."
  [xtql-query]
  (xtql.edn/parse-dml (list 'assert-exists xtql-query)))

(defn assert-not-exists
  "Returns an assert-not-exists operation for passing to `submit-tx`.

  When it's evaluated, this transaction operation runs the given query, and aborts the transaction iff the query returns any rows."
  [xtql-query]
  (xtql.edn/parse-dml (list 'assert-not-exists xtql-query)))

(defmacro template
  "This macro quotes the given query, but additionally allows you to use Clojure's unquote (`~`) and unquote-splicing (`~@`) forms within the quoted form.

  Usage:

  (defn build-posts-query [{:keys [with-author?]}]
    (xt/template (from :posts [{:xt/id id} text
                               ~@(when with-author?
                                   '[author])])))"

  {:clj-kondo/ignore [:unresolved-symbol :unresolved-namespace]}
  [query]

  (backtick/quote-fn query))
