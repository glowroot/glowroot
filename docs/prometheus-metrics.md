# Prometheus metrics (embedded)

Glowroot embedded can expose a Prometheus text scrape endpoint for **process/storage health** (not transaction aggregates).

## Enable

Off by default. Start the JVM with:

```bash
-Dglowroot.metrics.prometheus=true
```

Then scrape:

```text
GET http://<host>:<ui-port>/metrics
```

When disabled, `/metrics` returns **404**.

## Auth

Same as `/health`: no login when enabled. Bind the UI to localhost or put it behind a firewall/VPN.

## Metrics

| Name | Meaning |
|------|---------|
| `glowroot_up` | `1` if scrape succeeded |
| `glowroot_h2_data_file_bytes` | Size of embedded H2 `data.mv.db` |
| `glowroot_info{version=…}` | Glowroot version label |

## Example scrape config

```yaml
scrape_configs:
  - job_name: glowroot-embedded
    metrics_path: /metrics
    static_configs:
      - targets: ["127.0.0.1:4000"]
```

## Out of scope (for now)

- Central collector `/metrics`
- Per-transaction throughput / error rates ([#1099](https://github.com/glowroot/glowroot/issues/1099))
- OpenTelemetry export ([#1249](https://github.com/glowroot/glowroot/issues/1249))
