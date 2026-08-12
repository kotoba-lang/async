(ns kotoba.lang.async-parity-test
  "Parity gate between `src/kotoba/lang/async.kotoba` (the semantic authority)
  and `src/kotoba/lang/async.cljc` (the load path `kotoba-lang/scheduler`
  requires).

  Shape follows `kotoba-lang/css` (`css.kotoba-parity-test`, ADR-2607270100
  §10): the `.kotoba` is compiled here and executed through the KIR interpreter
  in this same JVM, so nothing crosses a runtime boundary. The functions under
  test take and return typed `:document` values, so the comparison encodes the
  `.cljc` channel state into the same tagged document form the interpreter
  returns (`->doc` below). `kotoba.lang.async-test` in this repo already pins
  that encoding independently.

  Every transition is compared, not just the endpoints: each scripted run
  threads the same operation sequence through both implementations and asserts
  the whole channel state matches after each step, so a divergence cannot hide
  inside an intermediate state and cancel out.

  WHAT THIS DOES NOT CLAIM — capacity. The guest restricts capacity to 1..32
  (the KIR document container budget). The `.cljc` does not, because the only
  consumer opens `(a/chan :dropping 64)`. Parity is asserted for capacities
  1..32; outside that range the test asserts the *divergence* explicitly rather
  than pretending it is not there. See `capacity-bound-is-a-guest-bound-only`."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.compiler.core :as compiler]
            [kotoba.kir :as ir]
            [kotoba.lang.async :as a]))

(def ^:private source (slurp "src/kotoba/lang/async.kotoba"))

(def ^:private kir (delay (:kir (compiler/compile-source source :js-kotoba-v1))))

(defn- call [f & args] (ir/execute @kir f (vec args)))

(defn- ->doc
  "Encode an EDN value as the tagged canonical document the KIR interpreter
  returns: map keys are tagged and entries sorted by key text."
  [value]
  (cond
    (nil? value) ["null"]
    (boolean? value) ["bool" value]
    (keyword? value) ["keyword" value]
    (string? value) ["string" value]
    (integer? value) ["i64" value]
    (map? value) ["map" (->> value
                             (sort-by (comp str key))
                             (mapv (fn [[k v]] [["keyword" k] (->doc v)])))]
    (sequential? value) ["vector" (mapv ->doc value)]
    :else (throw (ex-info "value has no document encoding" {:value value}))))

(def ^:private payloads [:a :b :c :d :e :f])

;; --- one scripted run threaded through both implementations ----------------

(defn- run-script
  "Thread `ops` through both implementations from a fresh channel, asserting
  the states agree after every step. `ops` is a seq of
  `[:put v] | [:take] | [:drain] | [:close]`."
  [buf-type cap ops]
  (let [host0 (a/chan buf-type cap)
        guest0 (call 'chan buf-type cap)]
    (is (= (->doc host0) guest0)
        (str "fresh " buf-type " channel of capacity " cap))
    (loop [host host0 guest guest0 ops ops step 0]
      (if-let [[op v] (first ops)]
        (let [[host' guest']
              (case op
                :put (let [[h accepted] (a/put host v)
                           [g-ch g-ok] (second (call 'put guest (->doc v)))]
                       (is (= (->doc accepted) g-ok)
                           (str "step " step " put " v " acceptance"))
                       [h g-ch])
                :take (let [[h value] (a/take host)
                            [g-ch g-val] (second (call 'take guest))]
                        (is (= (->doc value) g-val)
                            (str "step " step " take value"))
                        [h g-ch])
                :drain (let [[h values] (a/drain host)
                             [g-ch g-vals] (second (call 'drain guest))]
                         (is (= (->doc (vec values)) g-vals)
                             (str "step " step " drained values"))
                         [h g-ch])
                :close [(a/close host) (call 'close guest)])]
          (is (= (->doc host') guest')
              (str "step " step " " op " channel state"))
          (is (= (a/closed? host') (call 'closed? guest'))
              (str "step " step " " op " closed?"))
          (is (= (a/can-put? host') (call 'can-put? guest'))
              (str "step " step " " op " can-put?"))
          (is (= (a/can-take? host') (call 'can-take? guest'))
              (str "step " step " " op " can-take?"))
          (recur host' guest' (rest ops) (inc step)))
        [host guest]))))

(def ^:private fill-then-overflow
  (concat (for [v payloads] [:put v]) [[:take]] [[:put :g]] [[:drain]]))

(deftest every-buffer-kind-agrees-transition-by-transition
  (doseq [buf-type [:fixed :dropping :sliding]
          cap [1 2 3 5]]
    (testing (str buf-type " capacity " cap)
      (run-script buf-type cap fill-then-overflow))))

(deftest close-agrees-transition-by-transition
  (doseq [buf-type [:fixed :dropping :sliding]]
    (testing (str buf-type " close then put then take")
      (run-script buf-type 3
                  [[:put :a] [:put :b] [:close] [:put :c] [:take] [:take] [:take]]))))

(deftest taking-and-draining-an-empty-channel-agrees
  (doseq [buf-type [:fixed :dropping :sliding]]
    (testing (str buf-type)
      (run-script buf-type 2 [[:take] [:drain] [:take]]))))

(deftest a-full-capacity-channel-at-the-guest-limit-agrees
  ;; 32 is exactly the guest's container limit; this is the largest channel
  ;; both implementations can represent.
  (run-script :sliding 32
              (concat (for [i (range 32)] [:put (keyword (str "v" i))])
                      [[:put :overflow] [:take] [:drain]])))

(deftest fixed-chan-agrees
  (doseq [cap [1 2 8 32]]
    (testing (str "capacity " cap)
      (is (= (->doc (a/fixed-chan cap)) (call 'fixed-chan cap))))))

(deftest document-payloads-agree
  ;; Channels carry documents, not only keywords: strings, integers, booleans,
  ;; nested vectors and maps all round-trip identically.
  (let [values [:kw "text" 42 true nil ["a" 1] {:id "x" :n 3}]]
    (run-script :sliding 4 (for [v values] [:put v]))))

(deftest both-refuse-an-unknown-kind-or-a-non-positive-capacity
  (doseq [[kind cap] [[:unknown 2] [:queue 2] [:fixed 0] [:sliding 0] [:dropping -1]]]
    (testing (str kind " " cap)
      (is (thrown? clojure.lang.ExceptionInfo (a/chan kind cap)))
      (is (thrown? clojure.lang.ExceptionInfo (call 'chan kind cap))))))

(deftest capacity-bound-is-a-guest-bound-only
  ;; The one divergence, asserted rather than glossed. The guest cannot hold
  ;; more than 32 items in a document container, so it refuses to build such a
  ;; channel; the .cljc must not, because kotoba-lang/scheduler opens its ready
  ;; queue as (chan :dropping 64). If the guest ever grows past 32 this test
  ;; fails and the divergence note in async.cljc must be revisited.
  (doseq [cap [33 64 128]]
    (testing (str "capacity " cap)
      (is (thrown? clojure.lang.ExceptionInfo (call 'chan :dropping cap))
          "the guest refuses a channel larger than one document container")
      (let [ch (a/chan :dropping cap)]
        (is (= cap (:cap ch)))
        (is (true? (a/can-put? ch))))))
  (testing "the scheduler's actual ready queue works on the load path"
    (let [q (a/chan :dropping 64)
          filled (reduce (fn [c i] (first (a/put c {:id i :at i :fired-at i})))
                         q (range 100))
          [emptied fired] (a/drain filled)]
      (is (= 64 (count fired)))
      (is (= 0 (count (:buffer emptied))))
      (is (= (range 64) (map :id fired))))))
