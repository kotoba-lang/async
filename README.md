# kotoba-lang/async

[![CI](https://github.com/kotoba-lang/async/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/async/actions/workflows/ci.yml)

**Layer 2 (cap/effect) of the kotoba foundational stdlib** — bounded channels as
**pure state machines**. `put` / `take` are pure transitions a host or durable
loop drives — **no threads, no wall-clock, no core.async dependency** — so the
same CSP model runs on kotoba-WASM (SCI), where real concurrency is not
available. Zero third-party runtime deps; every namespace is `.cljc` (JVM / SCI
/ ClojureScript / GraalVM / kotoba-WASM). See
[`docs/adr/ADR-kotoba-lang-foundational-stdlib.md`](https://github.com/kotoba-lang/kotoba-lang/blob/main/docs/adr/ADR-kotoba-lang-foundational-stdlib.md).

## Why this shape

The kotoba-WASM premise (no threads, no wall clock) rules out `core.async`
go-blocks and `tokio`-style runtimes. But the vertical libs (`langgraph`
interrupts / resume, the actor durable outer loop) still need a vocabulary for
*bounded queues* and *backpressure*. This lib models a channel as plain EDN
state and `put`/`take` as `[new-state result]` transitions — the host loop
threading the state through. That is exactly the "bounded state-machine channel"
the foundational-stdlib ADR specified, and it composes with the durable outer
loop in `CLAUDE.md` (lease / tick / budget) without inventing a runtime.

## Current surface

`kotoba.lang.async`:

- `chan` — make a channel: `(chan n)` fixed, `(chan :dropping n)`,
  `(chan :sliding n)`
- `put [ch val]` → `[ch' accepted?]` — fixed blocks (not accepted) when full;
  dropping discards the new value when full; sliding discards the oldest
- `take [ch]` → `[ch' val-or-nil]` — `nil` means "nothing available now"
- `close [ch]`, `closed? [ch]`
- `can-put? [ch]`, `can-take? [ch]`
- `drain [ch]` → `[ch' [vals…]]` — take everything immediately available

## Install

```clojure
io.github.kotoba-lang/async {:git/sha "<sha>"}
```

## Use

```clojure
(require '[kotoba.lang.async :as a])

(let [[c1 _] (a/put (a/chan 2) :a)
      [c2 _] (a/put c1 :b)]
  (a/take c2))   ;=> [<> :a]

;; dropping buffer never blocks; the 3rd put silently drops
(let [d (a/chan :dropping 2)]
  (-> d (a/put :a) first (a/put :b) first (a/put :c) first a/drain))
;; => [<> [:a :b]]
```

## Verify

```sh
clojure -M:test
```
