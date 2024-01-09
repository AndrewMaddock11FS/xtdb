(ns xtdb.node.impl
  (:require [clojure.pprint :as pp]
            [juxt.clojars-mirrors.integrant.core :as ig]
            xtdb.indexer
            [xtdb.log :as log]
            [xtdb.operator.scan :as scan]
            [xtdb.protocols :as xtp]
            [xtdb.query :as q]
            [xtdb.sql :as sql]
            [xtdb.time :as time]
            [xtdb.util :as util])
  (:import (java.io Closeable Writer)
           (java.lang AutoCloseable)
           (java.time ZoneId)
           (java.util.concurrent CompletableFuture)
           [java.util.stream Stream]
           (org.apache.arrow.memory BufferAllocator RootAllocator)
           (xtdb.api IXtdb TransactionKey TxOptions)
           (xtdb.api.log Log)
           xtdb.indexer.IIndexer
           (xtdb.query Basis IRaQuerySource)
           (xtdb.tx Sql)))

(set! *unchecked-math* :warn-on-boxed)

(defmethod ig/init-key :xtdb/allocator [_ _] (RootAllocator.))
(defmethod ig/halt-key! :xtdb/allocator [_ ^BufferAllocator a]
  (util/close a))

(defmethod ig/prep-key :xtdb/default-tz [_ default-tz]
  (cond
    (instance? ZoneId default-tz) default-tz
    (string? default-tz) (ZoneId/of default-tz)
    :else time/utc))

(defmethod ig/init-key :xtdb/default-tz [_ default-tz] default-tz)

(defn- validate-tx-ops [tx-ops]
  (try
    (doseq [tx-op tx-ops
            :when (instance? Sql tx-op)]
      (sql/parse-query (.sql ^Sql tx-op)))
    (catch Throwable e
      (CompletableFuture/failedFuture e))))

(defn- with-after-tx-default [opts]
  (-> opts
      (update :after-tx time/max-tx (get-in opts [:basis :at-tx]))))

(defn- submit-tx& ^java.util.concurrent.CompletableFuture
  [{:keys [^BufferAllocator allocator, ^Log log, default-tz]} tx-ops ^TxOptions opts]

  (or (validate-tx-ops tx-ops)
      (let [system-time (.getSystemTime opts)]
        (.appendRecord log (log/serialize-tx-ops allocator tx-ops
                                                 {:default-tz (or (.getDefaultTz opts) default-tz)
                                                  :system-time system-time
                                                  :default-all-valid-time? (.getDefaultAllValidTime opts)})))))

(defrecord Node [^BufferAllocator allocator
                 ^IIndexer indexer
                 ^Log log
                 ^IRaQuerySource ra-src, wm-src, scan-emitter
                 default-tz
                 !latest-submitted-tx
                 system, close-fn]
  IXtdb
  (submitTxAsync [this tx-ops opts]
    (let [system-time (.getSystemTime opts)]
      (-> (submit-tx& this tx-ops opts)
          (util/then-apply
            (fn [^TransactionKey tx-key]
              (let [tx-key (cond-> tx-key
                             system-time (.withSystemTime system-time))]

                (swap! !latest-submitted-tx time/max-tx tx-key)
                tx-key))))))

  (openQueryAsync [this query opts]
    (xtp/open-query& this query
                     (-> (into {:default-all-valid-time? false} opts)
                         (time/after-latest-submitted-tx this))))

  xtp/PNode
  (open-query& [_ query query-opts]
    (let [query-opts (-> (into {:default-tz default-tz} query-opts)
                         (update :basis (fn [b] (cond->> b (instance? Basis b) (into {}))))
                         (with-after-tx-default))]
      (-> (.awaitTxAsync indexer (get query-opts :after-tx) (:tx-timeout query-opts))
          (util/then-apply
            (fn [_]
              (let [table-info (scan/tables-with-cols query-opts wm-src scan-emitter)
                    [lang plan] (q/compile-query query query-opts table-info)]
                (if (:explain? query-opts)
                  (Stream/of {:plan plan})

                  (q/open-query allocator ra-src wm-src
                                lang plan query-opts))))))))

  (latest-submitted-tx [_] @!latest-submitted-tx)

  xtp/PStatus
  (status [this]
    {:latest-completed-tx (.latestCompletedTx indexer)
     :latest-submitted-tx (xtp/latest-submitted-tx this)})

  Closeable
  (close [_]
    (when close-fn
      (close-fn))))

(defmethod print-method Node [_node ^Writer w] (.write w "#<XtdbNode>"))
(defmethod pp/simple-dispatch Node [it] (print-method it *out*))

(defmethod ig/prep-key :xtdb/node [_ opts]
  (merge {:allocator (ig/ref :xtdb/allocator)
          :indexer (ig/ref :xtdb/indexer)
          :wm-src (ig/ref :xtdb/indexer)
          :log (ig/ref :xtdb/log)
          :default-tz (ig/ref :xtdb/default-tz)
          :ra-src (ig/ref :xtdb.query/ra-query-source)
          :scan-emitter (ig/ref :xtdb.operator.scan/scan-emitter)}
         opts))

(defmethod ig/init-key :xtdb/node [_ deps]
  (map->Node (-> deps
                 (assoc :!latest-submitted-tx (atom nil)))))

(defmethod ig/halt-key! :xtdb/node [_ node]
  (util/try-close node))

(defn- with-default-impl [opts parent-k impl-k]
  (cond-> opts
    (not (ig/find-derived opts parent-k)) (assoc impl-k {})))

(defn node-system [opts]
  (-> (into {:xtdb/node {}
             :xtdb/allocator {}
             :xtdb/default-tz nil
             :xtdb/indexer {}
             :xtdb.indexer/live-index {}
             :xtdb.log/watcher {}
             :xtdb.metadata/metadata-manager {}
             :xtdb.operator.scan/scan-emitter {}
             :xtdb.query/ra-query-source {}
             :xtdb.stagnant-log-flusher/flusher {}
             :xtdb/compactor {}}
            opts)
      (doto ig/load-namespaces)
      (with-default-impl :xtdb/log :xtdb.log/memory-log)
      (with-default-impl :xtdb/buffer-pool :xtdb.buffer-pool/in-memory)
      (doto ig/load-namespaces)))

(defn start-node ^java.lang.AutoCloseable [opts]
  (let [!closing (atom false)
        system (-> (node-system opts)
                   ig/prep
                   ig/init)]

    (-> (:xtdb/node system)
        (assoc :system system
               :close-fn #(when (compare-and-set! !closing false true)
                            (ig/halt! system)
                            #_(println (.toVerboseString ^RootAllocator (:xtdb/allocator system))))))))

(defrecord SubmitNode [^BufferAllocator allocator, ^Log log, default-tz
                       !system, close-fn]
  IXtdb
  (submitTxAsync [this tx-ops opts]
    (submit-tx& this tx-ops opts))

  AutoCloseable
  (close [_]
    (when close-fn
      (close-fn))))

(defmethod print-method SubmitNode [_node ^Writer w] (.write w "#<XtdbSubmitNode>"))
(defmethod pp/simple-dispatch SubmitNode [it] (print-method it *out*))

(defmethod ig/prep-key ::submit-node [_ opts]
  (merge {:allocator (ig/ref :xtdb/allocator)
          :log (ig/ref :xtdb/log)
          :default-tz (ig/ref :xtdb/default-tz)}
         opts))

(defmethod ig/init-key ::submit-node [_ deps]
  (map->SubmitNode (-> deps (assoc :!system (atom nil)))))

(defmethod ig/halt-key! ::submit-node [_ ^SubmitNode node]
  (.close node))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn start-submit-node ^xtdb.node.impl.SubmitNode [opts]
  (let [!closing (atom false)
        system (-> (into {::submit-node {}
                          :xtdb/allocator {}
                          :xtdb/default-tz nil}
                         opts)
                   (doto ig/load-namespaces)
                   (with-default-impl :xtdb/log :xtdb.log/memory-log)
                   (doto ig/load-namespaces)
                   ig/prep
                   ig/init)]

    (-> (::submit-node system)
        (doto (-> :!system (reset! system)))
        (assoc :close-fn #(when-not (compare-and-set! !closing false true)
                            (ig/halt! system))))))
