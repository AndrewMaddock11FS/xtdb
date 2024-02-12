(ns xtdb.trie-test
  (:require [clojure.test :as t :refer [deftest]]
            [xtdb.test-util :as tu]
            [xtdb.trie :as trie]
            [xtdb.util :as util]
            [xtdb.time :as time])
  (:import (org.apache.arrow.memory RootAllocator)
           org.apache.arrow.vector.VectorSchemaRoot
           (xtdb.trie ArrowHashTrie ArrowHashTrie$Leaf)))

(deftest test-merge-plan-with-nil-nodes-2700
  (letfn [(->arrow-hash-trie [^VectorSchemaRoot meta-root]
            (ArrowHashTrie. (.getVector meta-root "nodes")))]

    (with-open [al (RootAllocator.)
                t1-root (tu/open-arrow-hash-trie-root al [{Long/MAX_VALUE [nil 0 nil 1]} 2 nil
                                                          {Long/MAX_VALUE 3, (time/instant->micros (time/->instant #inst "2023-01-01")) 4}])
                log-root (tu/open-arrow-hash-trie-root al 0)
                log2-root (tu/open-arrow-hash-trie-root al [nil nil 0 1])]

      (t/is (= {:path [],
                :node [:branch-iid
                       [{:path [0],
                         :node [:branch-iid
                                [{:path [0 0], :node [:leaf [nil nil {:seg :log, :page-idx 0} nil]]}
                                 {:path [0 1], :node [:leaf [nil {:seg :t1, :page-idx 0} {:seg :log, :page-idx 0} nil]]}
                                 {:path [0 2], :node [:leaf [nil nil {:seg :log, :page-idx 0} nil]]}
                                 {:path [0 3], :node [:leaf [nil {:seg :t1, :page-idx 1} {:seg :log, :page-idx 0} nil]]}]]}
                        {:path [1],
                         :node [:leaf [nil {:seg :t1, :page-idx 2} {:seg :log, :page-idx 0} nil]]}
                        {:path [2],
                         :node [:leaf [nil nil {:seg :log, :page-idx 0} {:seg :log2, :page-idx 0}]]}
                        {:path [3],
                         :node [:leaf
                                [nil {:seg :t1, :page-idx 4} {:seg :t1, :page-idx 3} {:seg :log, :page-idx 0} {:seg :log2, :page-idx 1}]]}]]}

               (trie/postwalk-merge-plan [nil
                                          {:seg :t1, :trie (->arrow-hash-trie t1-root)}
                                          {:seg :log, :trie (->arrow-hash-trie log-root)}
                                          {:seg :log2, :trie (->arrow-hash-trie log2-root)}]
                                         (fn [path [mn-tag & mn-args :as merge-node]]
                                           {:path (vec path)
                                            :node (case mn-tag
                                                    :branch-iid merge-node
                                                    :leaf (let [[segments nodes] mn-args]
                                                            [:leaf (mapv (fn [{:keys [seg]} ^ArrowHashTrie$Leaf leaf]
                                                                           (when leaf
                                                                             {:seg seg, :page-idx (.getDataPageIndex leaf)}))
                                                                         segments nodes)]))})))))))

(t/deftest test-selects-current-tries
  (letfn [(f [trie-keys]
            (->> (trie/current-trie-files (for [[level nr] trie-keys]
                                            (trie/->table-meta-file-path (util/->path "tables/xt_docs") (trie/->log-trie-key level nr))))
                 (mapv (comp (juxt :level :next-row) trie/parse-trie-file-path))))]
    (t/is (= [] (f [])))

    (t/is (= [[0 1] [0 2] [0 3]]
             (f [[0 1] [0 2] [0 3]])))

    (t/is (= [[1 2] [0 3]]
             (f [[1 2] [0 1] [0 2] [0 3]])))

    (t/is (= [[2 4] [1 6] [0 7] [0 8]]
             (f [[2 4]
                 [1 2] [1 4] [1 6]
                 [0 1] [0 2] [0 3] [0 4] [0 5] [0 6] [0 7] [0 8]])))))
