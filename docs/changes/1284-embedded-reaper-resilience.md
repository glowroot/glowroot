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

- `ReaperRunnable` catches per delete step; `AggregateDao` splits overall vs transaction-name
  deletes so tt/tn isolate.
- `ReaperFailureTracker` records consecutive failed cycles; Admin storage GET and
  `org.glowroot:type=H2Database` expose status; logs ERROR once then WARN periodically.
- `RepoAdmin.runHealthCheck()` (embedded) queries tt and tn; streak alone does not trip
  readiness (avoids restart loops on irreparable corruption).

## Choices

- No global UI banner or new Prometheus endpoint — reuse Admin Storage + existing JMX
  `FileSize` / new streak attribute.
- Readiness stays a storage probe, not a streak gate.

## Verification

- `mvn test -Dtest=ReaperRunnableTest -pl :glowroot-agent-embedded`
- Manual: Admin → Storage shows H2 size; force a failing delete in a test harness or
  inject tracker via unit test for streak warning copy.
