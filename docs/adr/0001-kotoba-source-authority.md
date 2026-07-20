# ADR 0001: bounded channel state machines are sovereign Kotoba

The production implementation is `src/kotoba/lang/async.kotoba`. JVM
Clojure is used only to host compiler qualification tests, never as the
production runtime.

Channel payloads use canonical `:document` values. Capacity is restricted to
1–32 items, matching the document container budget. Unknown buffer kinds and
invalid capacities fail closed. Lookup is option-returning; persistent updates
cannot mutate host-owned values.

The state machine has no threads, clocks, I/O, or scheduler authority. A host
or durable loop may thread states through it, but must obtain those effects
through separately admitted capabilities.

Conformance compares observable state transitions, typed ABI behavior,
resource bounds, and rejection behavior across reference execution,
restricted JavaScript, and instantiated typed Wasm. Wasm byte identity is not
a compatibility requirement.
