(ns xtdb.operator.scan
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [juxt.clojars-mirrors.integrant.core :as ig]
            [xtdb.bloom :as bloom]
            [xtdb.buffer-pool :as bp]
            [xtdb.expression :as expr]
            [xtdb.expression.metadata :as expr.meta]
            xtdb.indexer.live-index
            [xtdb.logical-plan :as lp]
            [xtdb.metadata :as meta]
            xtdb.object-store
            [xtdb.trie :as trie]
            [xtdb.types :as types]
            [xtdb.util :as util]
            [xtdb.vector.reader :as vr]
            [xtdb.vector.writer :as vw]
            xtdb.watermark)
  (:import (clojure.lang IPersistentMap MapEntry)
           (java.io Closeable)
           java.nio.ByteBuffer
           (java.nio.file Path)
           (java.util Comparator HashMap Iterator LinkedList Map PriorityQueue)
           (java.util.function IntPredicate)
           (java.util.stream IntStream)
           (org.apache.arrow.memory ArrowBuf BufferAllocator)
           [org.apache.arrow.memory.util ArrowBufPointer]
           (org.apache.arrow.vector VectorLoader)
           (org.apache.arrow.vector.types.pojo FieldType)
           [org.roaringbitmap.buffer MutableRoaringBitmap]
           xtdb.api.protocols.TransactionInstant
           (xtdb.bitemporal Ceiling Polygon IRowConsumer)
           xtdb.IBufferPool
           xtdb.ICursor
           (xtdb.metadata IMetadataManager ITableMetadata)
           xtdb.operator.IRelationSelector
           (xtdb.trie ArrowHashTrie$Leaf EventRowPointer HashTrie LiveHashTrie$Leaf)
           (xtdb.util TemporalBounds TemporalBounds$TemporalColumn)
           (xtdb.vector IRelationWriter IRowCopier IVectorReader IVectorWriter RelationReader)
           (xtdb.watermark ILiveTableWatermark IWatermark IWatermarkSource Watermark)))

(s/def ::table symbol?)

;; TODO be good to just specify a single expression here and have the interpreter split it
;; into metadata + col-preds - the former can accept more than just `(and ~@col-preds)
(defmethod lp/ra-expr :scan [_]
  (s/cat :op #{:scan}
         :scan-opts (s/keys :req-un [::table]
                            :opt-un [::lp/for-valid-time ::lp/for-system-time ::lp/default-all-valid-time?])
         :columns (s/coll-of (s/or :column ::lp/column
                                   :select ::lp/column-expression))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(definterface IScanEmitter
  (tableColNames [^xtdb.watermark.IWatermark wm, ^String table-name])
  (allTableColNames [^xtdb.watermark.IWatermark wm])
  (scanFields [^xtdb.watermark.IWatermark wm, scan-cols])
  (emitScan [scan-expr scan-fields param-fields]))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn ->scan-cols [{:keys [columns], {:keys [table]} :scan-opts}]
  (for [[col-tag col-arg] columns]
    [table (case col-tag
             :column col-arg
             :select (key (first col-arg)))]))

(def ^:dynamic *column->pushdown-bloom* {})

(defn- ->temporal-bounds [^RelationReader params, {^TransactionInstant basis-tx :tx}, {:keys [for-valid-time for-system-time]}]
  (let [bounds (TemporalBounds.)]
    (letfn [(->time-μs [[tag arg]]
              (case tag
                :literal (-> arg
                             (util/sql-temporal->micros (.getZone expr/*clock*)))
                :param (some-> (-> (.readerForName params (name arg))
                                   (.getObject 0))
                               (util/sql-temporal->micros (.getZone expr/*clock*)))
                :now (-> (.instant expr/*clock*)
                         (util/instant->micros))))]

      (when-let [system-time (some-> basis-tx (.system-time) util/instant->micros)]
        (.lte (.systemFrom bounds) system-time)

        (when-not for-system-time
          (.gt (.systemTo bounds) system-time)))

      (letfn [(apply-constraint [constraint ^TemporalBounds$TemporalColumn start-col, ^TemporalBounds$TemporalColumn end-col]
                (when-let [[tag & args] constraint]
                  (case tag
                    :at (let [[at] args
                              at-μs (->time-μs at)]
                          (.lte start-col at-μs)
                          (.gt end-col at-μs))

                    ;; overlaps [time-from time-to]
                    :in (let [[from to] args]
                          (.gt end-col (->time-μs (or from [:now])))
                          (when-let [to-μs (some-> to ->time-μs)]
                            (.lt start-col to-μs)))

                    :between (let [[from to] args]
                               (.gt end-col (->time-μs (or from [:now])))
                               (when-let [to-μs (some-> to ->time-μs)]
                                 (.lte start-col to-μs)))

                    :all-time nil)))]

        (apply-constraint for-valid-time (.validFrom bounds) (.validTo bounds))
        (apply-constraint for-system-time (.systemFrom bounds) (.systemTo bounds))))
    bounds))

(defn tables-with-cols [basis ^IWatermarkSource wm-src ^IScanEmitter scan-emitter]
  (let [{:keys [tx, after-tx]} basis
        wm-tx (or tx after-tx)]
    (with-open [^Watermark wm (.openWatermark wm-src wm-tx)]
      (.allTableColNames scan-emitter wm))))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn- copy-row-consumer [^IRelationWriter out-rel, ^RelationReader leaf-rel, col-names]
  (letfn [(writer-for [normalised-col-name]
            (let [wtrs (->> col-names
                            (into [] (keep (fn [^String col-name]
                                             (when (= normalised-col-name (util/str->normal-form-str col-name))
                                               (.colWriter out-rel col-name (FieldType/notNullable (types/->arrow-type types/temporal-col-type))))))))]
              (reify IVectorWriter
                (writeLong [_ l]
                  (doseq [^IVectorWriter wtr wtrs]
                    (.writeLong wtr l))))))]
    (let [op-rdr (.readerForName leaf-rel "op")
          put-rdr (.legReader op-rdr :put)
          doc-rdr (.structKeyReader put-rdr "xt$doc")

          row-copiers (object-array
                       (for [^String col-name col-names
                             :let [normalized-name (util/str->normal-form-str col-name)
                                   copier (case normalized-name
                                            "xt$iid"
                                            (.rowCopier (.readerForName leaf-rel "xt$iid")
                                                        (.colWriter out-rel col-name (FieldType/notNullable (types/->arrow-type [:fixed-size-binary 16]))))
                                            ("xt$system_from" "xt$system_to" "xt$valid_from" "xt$valid_to") nil
                                            (some-> (.structKeyReader doc-rdr normalized-name)
                                                    (.rowCopier (.colWriter out-rel col-name))))]
                             :when copier]
                         copier))

          ^IVectorWriter valid-from-wtr (writer-for "xt$valid_from")
          ^IVectorWriter valid-to-wtr (writer-for "xt$valid_to")
          ^IVectorWriter sys-from-wtr (writer-for "xt$system_from")
          ^IVectorWriter sys-to-wtr (writer-for "xt$system_to")]

      (reify IRowConsumer
        (accept [_ idx valid-from valid-to sys-from sys-to]
          (.startRow out-rel)

          (dotimes [i (alength row-copiers)]
            (let [^IRowCopier copier (aget row-copiers i)]
              (.copyRow copier idx)))

          (.writeLong valid-from-wtr valid-from)
          (.writeLong valid-to-wtr valid-to)
          (.writeLong sys-from-wtr sys-from)
          (.writeLong sys-to-wtr sys-to)

          (.endRow out-rel))))))

(defn- duplicate-ptr [^ArrowBufPointer dst, ^ArrowBufPointer src]
  (.set dst (.getBuf src) (.getOffset src) (.getLength src)))

(defn polygon-calculator ^xtdb.bitemporal.Polygon [^TemporalBounds temporal-bounds, {:keys [skip-iid-ptr prev-iid-ptr current-iid-ptr]}]
  (let [ceiling (Ceiling.)
        polygon (Polygon.)]
    (fn calculate-polygon [^EventRowPointer ev-ptr]
      (when-not (= skip-iid-ptr (.getIidPointer ev-ptr current-iid-ptr))
        (when-not (= prev-iid-ptr current-iid-ptr)
          (.reset ceiling)
          (duplicate-ptr prev-iid-ptr current-iid-ptr))

        (let [idx (.getIndex ev-ptr)
              leg (.getLeg (.opReader ev-ptr) idx)]
          (if (= :evict leg)
            (do
              (.reset ceiling)
              (duplicate-ptr skip-iid-ptr current-iid-ptr)
              nil)

            (let [system-from (.getSystemTime ev-ptr)]
              (when (and (<= (.lower (.systemFrom temporal-bounds)) system-from)
                         (<= system-from (.upper (.systemFrom temporal-bounds))))
                (case leg
                  :put
                  (let [valid-from (.getLong (.putValidFromReader ev-ptr) idx)
                        valid-to (.getLong (.putValidToReader ev-ptr) idx)]
                    (when (< valid-from valid-to)
                      (.calculateFor polygon ceiling valid-from valid-to)
                      (.applyLog ceiling system-from valid-from valid-to)
                      polygon))

                  :delete
                  (let [valid-from (.getLong (.deleteValidFromReader ev-ptr) idx)
                        valid-to (.getLong (.deleteValidToReader ev-ptr) idx)]
                    (when (< valid-from valid-to)
                      (.calculateFor polygon ceiling valid-from valid-to)
                      (.applyLog ceiling system-from valid-from valid-to)
                      nil)))))))))))

(defn iid-selector [^ByteBuffer iid-bb]
  (reify IRelationSelector
    (select [_ allocator rel-rdr _params]
      (with-open [arrow-buf (util/->arrow-buf-view allocator iid-bb)]
        (let [iid-ptr (ArrowBufPointer. arrow-buf 0 (.capacity iid-bb))
              ptr (ArrowBufPointer.)
              iid-rdr (.readerForName rel-rdr "xt$iid")
              value-count (.valueCount iid-rdr)]
          (if (pos-int? value-count)
            ;; lower-bound
            (loop [left 0 right (dec value-count)]
              (if (= left right)
                (if (= iid-ptr (.getPointer iid-rdr left ptr))
                  ;; upper bound
                  (loop [right left]
                    (if (or (>= right value-count) (not= iid-ptr (.getPointer iid-rdr right ptr)))
                      (.toArray (IntStream/range left right))
                      (recur (inc right))))
                  (int-array 0))
                (let [mid (quot (+ left right) 2)]
                  (if (<= (.compareTo iid-ptr (.getPointer iid-rdr mid ptr)) 0)
                    (recur left mid)
                    (recur (inc mid) right)))))
            (int-array 0)))))))

(defrecord VSRCache [^IBufferPool buffer-pool, ^BufferAllocator allocator, ^Map cache]
  Closeable
  (close [_] (util/close cache)))

(defn ->vsr-cache [buffer-pool allocator]
  (->VSRCache buffer-pool allocator (HashMap.)))

(defn cache-vsr [{:keys [^Map cache, buffer-pool, allocator]} ^Path trie-leaf-file]
  (.computeIfAbsent cache trie-leaf-file
                    (util/->jfn
                      (fn [trie-leaf-file]
                        (bp/open-vsr buffer-pool trie-leaf-file allocator)))))

(defn merge-task-data-reader ^IVectorReader [buffer-pool vsr-cache ^Path table-path [leaf-tag leaf-arg]]
  (case leaf-tag
    :arrow
    (let [{:keys [page-idx trie-key]} leaf-arg
          data-file-path (trie/->table-data-file-path table-path trie-key)]
      (util/with-open [rb (bp/open-record-batch buffer-pool data-file-path page-idx)]
        (let [vsr (cache-vsr vsr-cache data-file-path)
              loader (VectorLoader. vsr)]
          (.load loader rb)
          (vr/<-root vsr))))

    :live (:rel-rdr leaf-arg)))

(defn- consume-polygon [^Polygon polygon, ^EventRowPointer ev-ptr, ^IRowConsumer row-consumer, ^TemporalBounds temporal-bounds]
  (let [sys-from (.getSystemTime ev-ptr)
        idx (.getIndex ev-ptr)
        valid-times (.validTimes polygon)
        sys-time-ceilings (.sysTimeCeilings polygon)]
    (dotimes [i (.size sys-time-ceilings)]
      (let [valid-from (.get valid-times i)
            valid-to (.get valid-times (inc i))
            sys-to (.get sys-time-ceilings i)]
        (when (and (.inRange temporal-bounds valid-from valid-to sys-from sys-to)
                   (not (= valid-from valid-to))
                   (not (= sys-from sys-to)))
          (.accept row-consumer idx valid-from valid-to sys-from sys-to))))))

(deftype TrieCursor [^BufferAllocator allocator, ^Iterator merge-tasks
                     ^Path table-path, col-names, ^Map col-preds,
                     ^TemporalBounds temporal-bounds
                     params, ^IPersistentMap picker-state
                     vsr-cache, buffer-pool]
  ICursor
  (tryAdvance [_ c]
    (if (.hasNext merge-tasks)
      (let [{:keys [leaves path]} (.next merge-tasks)
            is-valid-ptr (ArrowBufPointer.)]
        (with-open [out-rel (vw/->rel-writer allocator)]
          (let [^IRelationSelector iid-pred (get col-preds "xt$iid")
                merge-q (PriorityQueue. (Comparator/comparing (util/->jfn :ev-ptr) (EventRowPointer/comparator)))
                calculate-polygon (polygon-calculator temporal-bounds picker-state)]

            (doseq [leaf leaves
                    :when leaf
                    :let [^RelationReader data-rdr (merge-task-data-reader buffer-pool vsr-cache table-path leaf)
                          ^RelationReader leaf-rdr (cond-> data-rdr
                                                     iid-pred (.select (.select iid-pred allocator data-rdr params)))
                          rc (copy-row-consumer out-rel leaf-rdr col-names)
                          ev-ptr (EventRowPointer. leaf-rdr path)]]
              (when (.isValid ev-ptr is-valid-ptr path)
                (.add merge-q {:ev-ptr ev-ptr, :row-consumer rc})))

            (loop []
              (when-let [{:keys [^EventRowPointer ev-ptr, row-consumer] :as q-obj} (.poll merge-q)]
                (some-> (calculate-polygon ev-ptr)
                        (consume-polygon ev-ptr row-consumer temporal-bounds))

                (.nextIndex ev-ptr)
                (when (.isValid ev-ptr is-valid-ptr path)
                  (.add merge-q q-obj))
                (recur)))

            (.accept c (loop [^RelationReader rel (-> (vw/rel-wtr->rdr out-rel)
                                                      (vr/with-absent-cols allocator col-names))
                              [^IRelationSelector col-pred & col-preds] (vals (dissoc col-preds "xt$iid"))]
                         (if col-pred
                           (recur (.select rel (.select col-pred allocator rel params)) col-preds)
                           rel)))))
        true)

      false))

  (close [_]
    (util/close vsr-cache)))

(defn- eid-select->eid [eid-select]
  (if (= 'xt/id (second eid-select))
    (nth eid-select 2)
    (second eid-select)))

(defn selects->iid-byte-buffer ^ByteBuffer [selects ^RelationReader params-rel]
  (when-let [eid-select (or (get selects "xt/id") (get selects "xt$id"))]
    (when (= '= (first eid-select))
      (let [eid (eid-select->eid eid-select)]
        (cond
          (s/valid? ::lp/value eid)
          (trie/->iid eid)

          (s/valid? ::lp/param eid)
          (let [eid-rdr (.readerForName params-rel (name eid))]
            (when (= 1 (.valueCount eid-rdr))
              (trie/->iid (.getObject eid-rdr 0)))))))))

(defn filter-pushdown-bloom-page-idx-pred ^IntPredicate [^ITableMetadata table-metadata ^String col-name]
  (when-let [^MutableRoaringBitmap pushdown-bloom (get *column->pushdown-bloom* (symbol col-name))]
    (let [metadata-rdr (.metadataReader table-metadata)
          bloom-rdr (-> (.structKeyReader metadata-rdr "columns")
                        (.listElementReader)
                        (.structKeyReader "bloom"))]
      (reify IntPredicate
        (test [_ page-idx]
          (boolean
           (when-let [bloom-vec-idx (.rowIndex table-metadata col-name page-idx)]
             (and (not (nil? (.getObject bloom-rdr bloom-vec-idx)))
                  (MutableRoaringBitmap/intersects pushdown-bloom
                                                   (bloom/bloom->bitmap bloom-rdr bloom-vec-idx))))))))))

(defn- ->path-pred [^ArrowBuf iid-arrow-buf]
  (if iid-arrow-buf
    (let [iid-ptr (ArrowBufPointer. iid-arrow-buf 0 (.capacity iid-arrow-buf))]
      #(zero? (HashTrie/compareToPath iid-ptr %)))
    (constantly true)))

(defn- ->merge-tasks
  "scan-tries :: [{:keys [meta-file trie-key table-metadata page-idx-pred]}]"
  [scan-tries, ^ILiveTableWatermark live-table-wm, path-pred]

  (letfn [(merge-tasks* [path [mn-tag mn-arg]]
            (when (path-pred path)
              (case mn-tag
                :branch (into [] cat mn-arg)
                :leaf (let [trie-nodes mn-arg
                            ^MutableRoaringBitmap cumulative-iid-bitmap (MutableRoaringBitmap.)
                            trie-nodes-it (.iterator ^Iterable trie-nodes)
                            scan-tries-it (.iterator ^Iterable scan-tries)]
                        (loop [node-taken? false, leaves []]
                          (if (.hasNext trie-nodes-it)
                            (let [{:keys [^IntPredicate page-idx-pred ^ITableMetadata table-metadata trie-key]}
                                  (when (.hasNext scan-tries-it)
                                    (.next scan-tries-it))]

                              (if-let [trie-node (.next trie-nodes-it)]
                                (condp = (class trie-node)
                                  ArrowHashTrie$Leaf
                                  (let [page-idx (.getDataPageIndex ^ArrowHashTrie$Leaf trie-node)
                                        take-node? (.test page-idx-pred page-idx)]
                                    (when take-node?
                                      (.or cumulative-iid-bitmap (.iidBloomBitmap table-metadata page-idx)))

                                    (recur (or node-taken? take-node?)
                                           (conj leaves (when (or take-node?
                                                                  (when node-taken?
                                                                    (when-let [iid-bitmap (.iidBloomBitmap table-metadata page-idx)]
                                                                      (MutableRoaringBitmap/intersects cumulative-iid-bitmap iid-bitmap))))
                                                          [:arrow {:page-idx page-idx
                                                                   :trie-key trie-key}]))))

                                  LiveHashTrie$Leaf
                                  (recur true (conj leaves
                                                    [:live {:rel-rdr
                                                            (.select (.liveRelation live-table-wm)
                                                                     (.mergeSort ^LiveHashTrie$Leaf trie-node (.liveTrie live-table-wm)))}])))

                                (recur node-taken? (conj leaves nil))))

                            (when node-taken?
                              [{:path path
                                :leaves leaves}])))))))]

    (trie/postwalk-merge-plan (cond-> (mapv (comp :trie :meta-file) scan-tries)
                                live-table-wm (conj (.liveTrie live-table-wm)))
                              merge-tasks*)))

(defmethod ig/prep-key ::scan-emitter [_ opts]
  (merge opts
         {:metadata-mgr (ig/ref ::meta/metadata-manager)
          :buffer-pool (ig/ref :xtdb/buffer-pool)}))

(defmethod ig/init-key ::scan-emitter [_ {:keys [^IMetadataManager metadata-mgr, ^IBufferPool buffer-pool]}]
  (reify IScanEmitter
    (tableColNames [_ wm table-name]
      (let [normalized-table (util/str->normal-form-str table-name)]
        (into #{} cat [(keys (.columnFields metadata-mgr normalized-table))
                       (some-> (.liveIndex wm)
                               (.liveTable normalized-table)
                               (.columnFields)
                               keys)])))

    (allTableColNames [_ wm]
      (merge-with set/union
                  (update-vals (.allColumnFields metadata-mgr)
                               (comp set keys))
                  (update-vals (some-> (.liveIndex wm)
                                       (.allColumnFields ))
                               (comp set keys))))

    (scanFields [_ wm scan-cols]
      (letfn [(->field [[table col-name]]
                (let [normalized-table (util/str->normal-form-str (str table))
                      normalized-col-name (util/str->normal-form-str (str col-name))]
                  (if (types/temporal-column? (util/str->normal-form-str (str col-name)))
                    ;; TODO move to fields here
                    (types/col-type->field [:timestamp-tz :micro "UTC"])
                    (types/merge-fields (.columnField metadata-mgr normalized-table normalized-col-name)
                                        (some-> (.liveIndex wm)
                                                (.liveTable normalized-table)
                                                (.columnFields)
                                                (get normalized-col-name))))))]
        (->> scan-cols
             (into {} (map (juxt identity ->field))))))

    (emitScan [_ {:keys [columns], {:keys [table] :as scan-opts} :scan-opts} scan-fields param-fields]
      (let [col-names (->> columns
                           (into #{} (map (fn [[col-type arg]]
                                            (case col-type
                                              :column arg
                                              :select (key (first arg)))))))

            fields (->> col-names
                        (into {} (map (juxt identity
                                            (fn [col-name]
                                              (get scan-fields [table col-name]))))))

            col-names (into #{} (map str) col-names)

            normalized-table-name (util/str->normal-form-str (str table))

            selects (->> (for [[tag arg] columns
                               :when (= tag :select)
                               :let [[col-name pred] (first arg)]]
                           (MapEntry/create (str col-name) pred))
                         (into {}))

            col-preds (->> (for [[col-name select-form] selects]
                             ;; for temporal preds, we may not need to re-apply these if they can be represented as a temporal range.
                             (let [input-types {:col-types (update-vals fields types/field->col-type)
                                                :param-types (update-vals param-fields types/field->col-type)}]
                               (MapEntry/create col-name
                                                (expr/->expression-relation-selector (expr/form->expr select-form input-types)
                                                                                     input-types))))
                           (into {}))

            metadata-args (vec (for [[col-name select] selects
                                     :when (not (types/temporal-column? (util/str->normal-form-str col-name)))]
                                 select))

            row-count (->> (for [{:keys [tables]} (vals (.chunksMetadata metadata-mgr))
                                 :let [{:keys [row-count]} (get tables normalized-table-name)]
                                 :when row-count]
                             row-count)
                           (reduce +))]

        {:fields fields
         :stats {:row-count row-count}
         :->cursor (fn [{:keys [allocator, ^IWatermark watermark, basis, params default-all-valid-time?]}]
                     (let [iid-bb (selects->iid-byte-buffer selects params)
                           col-preds (cond-> col-preds
                                       iid-bb (assoc "xt$iid" (iid-selector iid-bb)))
                           metadata-pred (expr.meta/->metadata-selector (cons 'and metadata-args) (update-vals fields types/field->col-type) params)
                           scan-opts (-> scan-opts
                                         (update :for-valid-time
                                                 (fn [fvt]
                                                   (or fvt (if default-all-valid-time? [:all-time] [:at [:now :now]])))))
                           ^ILiveTableWatermark live-table-wm (some-> (.liveIndex watermark) (.liveTable normalized-table-name))
                           table-path (util/table-name->table-path normalized-table-name)
                           current-meta-files (->> (trie/list-meta-files buffer-pool table-path)
                                                   (trie/current-trie-files))]

                       (util/with-open [iid-arrow-buf (when iid-bb (util/->arrow-buf-view allocator iid-bb))]
                         (let [merge-tasks (util/with-open [meta-files (LinkedList.)]
                                             (or (->merge-tasks (mapv (fn [meta-file-path]
                                                                        (let [{meta-rdr :rdr, :as meta-file} (trie/open-meta-file buffer-pool meta-file-path)]
                                                                          (.add meta-files meta-file)
                                                                          (let [^ITableMetadata table-metadata (.tableMetadata metadata-mgr meta-rdr meta-file-path)]
                                                                            {:meta-file meta-file
                                                                             :trie-key (:trie-key (trie/parse-trie-file-path meta-file-path))
                                                                             :table-metadata table-metadata
                                                                             :page-idx-pred (reduce (fn [^IntPredicate page-idx-pred col-name]
                                                                                                      (if-let [bloom-page-idx-pred (filter-pushdown-bloom-page-idx-pred table-metadata col-name)]
                                                                                                        (.and page-idx-pred bloom-page-idx-pred)
                                                                                                        page-idx-pred))
                                                                                                    (.build metadata-pred table-metadata)
                                                                                                    col-names)})))
                                                                      current-meta-files)
                                                                live-table-wm
                                                                (->path-pred iid-arrow-buf))
                                                 []))]

                           ;; The consumers for different leafs need to share some state so the logic of how to advance
                           ;; is correct. For example if the `skip-iid-ptr` gets set in one leaf consumer it should also affect
                           ;; the skipping in another leaf consumer.

                           (->TrieCursor allocator (.iterator ^Iterable merge-tasks)
                                         table-path col-names col-preds
                                         (->temporal-bounds params basis scan-opts)
                                         params
                                         {:skip-iid-ptr (ArrowBufPointer.)
                                          :prev-iid-ptr (ArrowBufPointer.)
                                          :current-iid-ptr (ArrowBufPointer.)}
                                         (->vsr-cache buffer-pool allocator)
                                         buffer-pool)))))}))))

(defmethod lp/emit-expr :scan [scan-expr {:keys [^IScanEmitter scan-emitter scan-fields, param-fields]}]
  (.emitScan scan-emitter scan-expr scan-fields param-fields))
