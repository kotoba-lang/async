# kotoba-lang/async

[![CI](https://github.com/kotoba-lang/async/actions/workflows/ci.yml/badge.svg)](https://github.com/kotoba-lang/async/actions/workflows/ci.yml)

**Layer 2 (cap/effect) of the kotoba foundational stdlib** — bounded channels as
**pure state machines**. `put` / `take` are pure transitions a host or durable
loop drives — **no threads, no wall-clock, no core.async dependency** — so the
same CSP model runs through the Kotoba reference evaluator, restricted
JavaScript, and typed Wasm. The sole production source is `.kotoba`; JVM
Clojure is only a compiler/test host. See
[`docs/adr/ADR-kotoba-lang-foundational-stdlib.md`](https://github.com/kotoba-lang/kotoba-lang/blob/main/docs/adr/ADR-kotoba-lang-foundational-stdlib.md).

## Why this shape

The kotoba-WASM premise (no threads, no wall clock) rules out `core.async`
go-blocks and `tokio`-style runtimes. But the vertical libs (`langgraph`
interrupts / resume, the actor durable outer loop) still need a vocabulary for
*bounded queues* and *backpressure*. This lib models a channel as plain EDN
canonical bounded document state and `put`/`take` as `[new-state result]`
transitions — the host loop
threading the state through. That is exactly the "bounded state-machine channel"
the foundational-stdlib ADR specified, and it composes with the durable outer
loop in `CLAUDE.md` (lease / tick / budget) without inventing a runtime.

## Current surface

`kotoba.lang.async`:

- `fixed-chan` — make a fixed channel; `chan` selects `:fixed`, `:dropping`, or
  `:sliding`. Capacity must be 1–32 and invalid construction fails closed.
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
(def channel (chan :dropping 2))
(def offered (put channel (document-keyword :a)))
```

Payloads and transition pairs are canonical `:document` values, so arbitrary
host objects cannot cross the boundary unchecked.

## Verify

```sh
clojure -M:test
```
