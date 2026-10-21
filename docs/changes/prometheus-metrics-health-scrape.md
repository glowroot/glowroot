# Opt-in embedded Prometheus /metrics health scrape

## What

Embedded-only `GET /metrics` (Prometheus text 0.0.4), gated by
`-Dglowroot.metrics.prometheus=true` (default off → 404). Exposes `glowroot_up`,
H2 data file bytes, and version info.

## Why

Toward epic #1249 / wishlist #1099: operators want scrapeable health without
pulling Micrometer. Aggregates stay out of this PR.

## How

- Hand-written exposition (no Micrometer/client JARs)
- Same anonymous model as `/health`
- Docs: `docs/prometheus-metrics.md` + README link

## Choices

Opt-in flag (default off). Embedded only — not registered on Central. Health/
info gauges first; transaction aggregates in follow-up (#1266).

## Verification

- [x] `PrometheusMetricsHttpServiceTest` (`:glowroot-ui`)
- [ ] Flag on → 200 + `glowroot_up 1`; flag off → 404; `/health` unchanged
