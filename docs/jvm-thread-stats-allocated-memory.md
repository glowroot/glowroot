# JVM thread stats — Allocated memory

> **Wiki paste target:** publish this page as
> [`JVM-thread-stats-allocated-memory`](https://github.com/glowroot/glowroot/wiki/JVM-thread-stats-allocated-memory)
> (same title / slug). Tracks [#630](https://github.com/glowroot/glowroot/issues/630).

## What the number is

**Allocated memory** on transaction / trace JVM thread stats is the **cumulative** number of bytes that thread allocated while the transaction (or live trace) was running.

It is **not**:

- how much heap the JVM is “holding” right now
- a high-water mark of live heap for that thread
- RSS / process memory

Bytes that were allocated and later garbage-collected still count. That is why the figure can look huge compared to `-Xmx` or the app’s heap size (reporters have seen multi‑GB or even TB-scale values on long or allocation-heavy work).

## How Glowroot calculates it

1. Capture must be on: **Configuration → Transaction → Capture JVM thread stats**.
2. At transaction start (on that thread), Glowroot records
   `com.sun.management.ThreadMXBean.getThreadAllocatedBytes(threadId)` (when the JVM supports it).
3. At completion (or when reading a still-running trace), it records the same counter again.
4. The displayed value is the **delta**: `end − start` for that thread.

Main-thread and auxiliary-thread stats are tracked separately when aux work is attributed to the transaction.

On **aggregate** transaction views (e.g. Average), the UI shows an **average per transaction**:
`totalAllocatedBytes / transactionCount` (same pattern as CPU / blocked / waited times).

If the JVM does not support allocated-memory tracking, or capture is off, the row is omitted (`-1` / unavailable).

## How to use it

- High allocated memory on a transaction name → that path creates a lot of short-lived (or retained) objects; pair with **Profile** / heap tools if you need *what* allocates.
- Compare transactions relative to each other; do not treat the number as “RAM in use.”
- For a single slow request, open the **trace**: the value is measured from the start of that trace.

## See also

- [[Transaction configuration]] — enable thread stats
- [[Transaction tabs]] — Average and related views
- Issue [#630](https://github.com/glowroot/glowroot/issues/630)
