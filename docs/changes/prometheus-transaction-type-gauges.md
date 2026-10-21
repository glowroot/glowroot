# Prometheus /metrics: per-transaction-type gauges

## What

Extends opt-in embedded `/metrics` with per-`transaction_type` gauges over the
last 60s from live aggregates (rollup level 0).

## Why

#1099 wants type-level alerting without per-transaction-name cardinality.
Builds on #1265 health scrape.

## How

- Metrics: count, error count, error rate, avg duration seconds
- Same gate: `-Dglowroot.metrics.prometheus=true`
- Docs updated in `docs/prometheus-metrics.md`

## Choices

Type labels only (no per-name). Branch may include #1265 commit until that PR
lands — rebase to follow-up commit if needed.

## Verification

- [x] `PrometheusMetricsHttpServiceTest`
- [ ] Flag on + Web/Background traffic → type labels; flag off → 404
