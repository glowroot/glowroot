# Prometheus metrics (embedded)

Glowroot embedded can expose a Prometheus text scrape endpoint for **process/storage health** and **per-transaction-type** gauges (not per transaction name).

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

### Health

| Name | Meaning |
|------|---------|
| `glowroot_up` | `1` if scrape succeeded |
| `glowroot_h2_data_file_bytes` | Size of embedded H2 `data.mv.db` |
| `glowroot_info{version=…}` | Glowroot version label |

### Transaction type (last 60 seconds, live aggregates)

Label: `transaction_type` (e.g. `Web`, `Background`). These are **gauges** over a sliding 60s window — do **not** apply Prometheus `rate()` to the count series.

| Name | Meaning |
|------|---------|
| `glowroot_transaction_count` | Transactions in the window |
| `glowroot_transaction_error_count` | Errors in the window |
| `glowroot_transaction_error_rate` | `error_count / count` (0 if count is 0) |
| `glowroot_transaction_avg_duration_seconds` | Mean duration in the window from live overview aggregates (0 if no overview samples) |

Per-transaction-**name** series are out of scope (cardinality). See [#1099](https://github.com/glowroot/glowroot/issues/1099).

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
- Per-transaction-name metrics
- OpenTelemetry export ([#1249](https://github.com/glowroot/glowroot/issues/1249))
