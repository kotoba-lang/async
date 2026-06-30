(ns kotoba.lang.async
  "Bounded channels as pure state machines. Layer 2 (cap/effect) of the kotoba
  foundational stdlib.

  The kotoba-WASM premise (no threads, no wall clock) rules out core.async
  go-blocks and tokio-style runtimes. This lib models a channel as plain EDN
  state and `put` / `take` as `[new-state result]` transitions a host or durable
  loop threads through — the 'bounded state-machine channel' the
  foundational-stdlib ADR specified. It composes with the durable outer loop
  (lease / tick / budget) in CLAUDE.md without inventing a runtime.

  Buffer semantics (matching core.async):
  - :fixed    — put is not accepted when full (the caller would block); take
                removes from the front.
  - :dropping — put is always accepted; when full the NEW value is discarded
                (buffer unchanged).
  - :sliding  — put is always accepted; when full the OLDEST value is dropped
                (front removed) and the new value appended.

  Zero third-party runtime deps; .cljc (JVM / SCI / CLJS / GraalVM / kotoba-WASM)."
  (:refer-clojure :exclude [chan put take close empty?]))

(defn chan
  "Create a bounded channel. Forms:
    (chan n)            ; fixed buffer of capacity n
    (chan :fixed n)     ; explicit
    (chan :dropping n)  ; drop-new when full
    (chan :sliding n)   ; drop-oldest when full"
  ([cap] (chan :fixed cap))
  ([buf-type cap]
   (let [bt (if (number? buf-type) :fixed buf-type)
         cap (if (number? buf-type) buf-type cap)]
     {:buf-type bt
      :cap      (long cap)
      :buffer   []
      :closed?  false})))

(defn closed? [ch] (:closed? ch))

(defn can-put?
  "True iff a put would be accepted right now. Fixed buffers refuse when full;
  dropping/sliding buffers always accept."
  [ch]
  (if (= (:buf-type ch) :fixed)
    (not= (count (:buffer ch)) (:cap ch))
    true))

(defn can-take?
  "True iff a value is immediately available."
  [ch]
  (pos? (count (:buffer ch))))

(defn put
  "Offer `val` onto `ch`. Returns `[ch' accepted?]`.
  - fixed: not accepted when full (ch unchanged).
  - dropping: always accepted; the new value is dropped when full.
  - sliding: always accepted; the oldest value is dropped when full.
  Putting onto a closed channel is not accepted."
  [ch val]
  (if (:closed? ch)
    [ch false]
    (let [buf  (:buffer ch)
          cap  (:cap ch)
          full (= (count buf) cap)]
      (case (:buf-type ch)
        :fixed
        (if full
          [ch false]
          [(assoc ch :buffer (conj buf val)) true])

        :dropping
        ;; always accepted; discard new value when full
        [(assoc ch :buffer (if full buf (conj buf val))) true]

        :sliding
        ;; always accepted; drop oldest when full, then append
        [(assoc ch :buffer
                (conj (if full (subvec buf 1) buf) val)) true]))))

(defn take
  "Remove the front value from `ch`. Returns `[ch' val]`, or `[ch' nil]` when
  nothing is available (the caller would block). Taking from a closed, empty
  channel returns nil."
  [ch]
  (let [buf (:buffer ch)]
    (if (pos? (count buf))
      [(assoc ch :buffer (subvec buf 1)) (first buf)]
      [ch nil])))

(defn close
  "Mark `ch` closed. Further puts are refused; remaining buffered values may
  still be taken."
  [ch]
  (assoc ch :closed? true))

(defn drain
  "Take every immediately-available value from `ch`. Returns `[ch' [vals…]]`."
  [ch]
  (let [buf (:buffer ch)]
    [(assoc ch :buffer []) buf]))
