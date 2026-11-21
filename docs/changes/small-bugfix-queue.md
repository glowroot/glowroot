# Small bugfix queue (alert subject, Cassandra DNS, pending limits, OpenJ9 heapHisto)

## What

Four focused fixes: alert email subject includes threshold; Cassandra contact
points stay unresolved for DNS refresh; configurable pending transaction /
aggregate limits; OpenJ9 heap histogram via attach `heapHisto`.

## Why

- #1088: inbox list showed name only — threshold was body-only
- #1151: `InetSocketAddress` cached first headless pod IP under K8s
- #686: hard-coded pending queue caps
- #1087: heap histogram missing / broken on OpenJ9

## How

- Alert subject includes threshold text
- Leave Cassandra contact points unresolved so DNS can refresh
- `-Dglowroot.internal.transactionPendingLimit` /
  `aggregatePendingLimit` (defaults 1000 / 5)
- OpenJ9: attach `heapHisto` (HotSpot attach fallback); jstack still blocked on J9

## Choices

Small independent fixes in one PR for review bandwidth. Defaults keep prior
pending-limit behavior unless overridden.

## Verification

- [x] `AlertingServiceTest` (`:glowroot-common2`)
- [x] `CentralModuleCassandraContactPointsTest` (`:glowroot-central`)
- [x] `TransactionProcessorPendingLimitsTest` (`:glowroot-agent-core`)
- [x] `HeapHistogramToolTest` (`:glowroot-agent-core`)
- [ ] Manual alert email subject; OpenJ9 heapHisto in UI if available
