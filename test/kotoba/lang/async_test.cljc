(ns kotoba.lang.async-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.lang.async :as a]))

(deftest fixed-buffer-put-and-take
  (let [c0 (a/chan 2)
        [c1 ok1] (a/put c0 :a)
        [c2 ok2] (a/put c1 :b)
        [c3 ok3] (a/put c2 :c)]
    (is (true? ok1))
    (is (true? ok2))
    (is (false? ok3))
    (is (= :a (first (:buffer c2))))
    (let [[c4 v] (a/take c2)]
      (is (= :a v))
      (is (= [:b] (:buffer c4))))))

(deftest fixed-can-put-and-can-take
  (let [c (a/chan 2)]
    (is (true? (a/can-put? c)))
    (is (false? (a/can-take? c)))
    (let [c (-> c (a/put :a) first (a/put :b) first)]
      (is (false? (a/can-put? c)))
      (is (true? (a/can-take? c))))))

(deftest dropping-buffer-drops-new
  (let [c0 (a/chan :dropping 2)
        c1 (-> c0 (a/put :a) first (a/put :b) first)
        [c2 ok] (a/put c1 :c)]
    (is (true? ok))
    (is (= [:a :b] (:buffer c2)))
    (is (true? (a/can-put? c2)))))

(deftest sliding-buffer-drops-oldest
  (let [c0 (a/chan :sliding 2)
        c1 (-> c0 (a/put :a) first (a/put :b) first)
        [c2 ok] (a/put c1 :c)]
    (is (true? ok))
    (is (= [:b :c] (:buffer c2)))))

(deftest drain-takes-all-immediately
  (let [c (-> (a/chan 3) (a/put :a) first (a/put :b) first)
        [out vs] (a/drain c)]
    (is (= [:a :b] vs))
    (is (empty? (:buffer out)))))

(deftest close-refuses-puts
  (let [c (a/close (a/chan 2))
        [_ ok] (a/put c :a)]
    (is (false? ok))
    (is (true? (a/closed? c)))))

(deftest take-on-empty-returns-nil
  (let [[_ v] (a/take (a/chan 2))]
    (is (nil? v))))
