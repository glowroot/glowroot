# Embedded retention reaper resilience and operator signals

Tracks [#1284](https://github.com/glowroot/glowroot/issues/1284).

## What

Embedded H2 retention reaper continues other tables after a per-step failure, exposes
failure streak + H2 file size on Admin → Storage (and JMX), and readiness probes both
`aggregate_tt_rollup_0` and `aggregate_tn_rollup_0`. Docs cover corrupt-H2 recovery.

## Why

A single corrupt rollup table (H2 `ArrayIndexOutOfBoundsException` in PageStore/MVStore)
aborted the whole reaper cycle via `ScheduledRunnable`, so other retention never ran and
`data/` could grow without bound while the monitored JVM kept running.

## How

- `ReaperRunnable` catches per delete step (`Exception`, not `Error`); interrupts restore the
  interrupt flag and abort the cycle without updating the failure streak. Failures outside a
  step (e.g. storage config read) still update the streak.
- `AggregateDao` splits overall vs transaction-name deletes; `TraceDao` splits attribute-name /
  trace / trace_attribute the same way.
- `ReaperFailureTracker` records consecutive failed cycles; Admin storage GET and
  `org.glowroot:type=H2Database` expose status; logs ERROR once then WARN periodically.
- `RepoAdmin.runHealthCheck()` (embedded) runs a cheap `COUNT` on tt and tn for the last ~4
  hours (index range). That is **not** the reaper delete path on old pages — a corrupt old
  leaf can still leave readiness green. The operator signal for retention stalls is the reaper
  streak (Admin / JMX / logs), not readiness alone.
- Streak counters never gate readiness (avoids restart loops on irreparable corruption). If
  the 4h `COUNT` itself fails (e.g. table/index unreadable in that window), readiness returns
  `503` until storage recovers or is wiped — document that for k8s users.

## Choices

- No global UI banner or new Prometheus endpoint — reuse Admin Storage + existing JMX
  `FileSize` / new streak attribute.
- Readiness stays a storage liveness-style probe of recent rollup rows, not a reaper-streak
  gate and not a delete-path corruption detector.
- A persistently failing table can still grow until wipe/Central; this PR only stops one bad
  table from aborting retention for the others.
- When some data deletes fail, metadata deletes for the same cutoff may still run
  (`minCaptureTime` follows config, not step success) — expired orphans are possible until
  the bad table is repaired.

## Verification

- `mvn test -Dtest=ReaperRunnableTest -pl :glowroot-agent-embedded`
- Manual: Admin → Storage shows H2 size; force a failing delete in a test harness or
  inject tracker via unit test for streak warning copy.
