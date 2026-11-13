# HTTP health endpoints (UI / Central)

Applies to the Glowroot HTTP UI port (embedded agent UI and glowroot-central).

## Endpoints (0.14.8+)

| Path | Role | Behavior |
|------|------|----------|
| `/liveness`, `/healthz`, **`/health`** | **Liveness** | Always `200` + `Glowroot OK` if the HTTP process is up. Does **not** check Cassandra (Central) or H2. |
| `/readiness`, `/ready` | **Readiness** | Runs storage health (`RepoAdmin.runHealthCheck()`). Central: Cassandra probe. Embedded: cheap `COUNT` on `aggregate_tt_rollup_0` **and** `aggregate_tn_rollup_0` for the last ~4 hours (index range on recent rows). This does **not** exercise the retention-reaper delete path on old pages — corruption that only breaks old deletes (see [#1284](https://github.com/glowroot/glowroot/issues/1284)) can leave readiness green while the reaper streak rises. If the probe itself fails, readiness stays `503` until H2 recovers or data is wiped (watch restart loops). Reaper failure-streak counters never gate readiness; use Admin → Storage / JMX / logs for that. |

## Breaking change vs 0.14.7

On **0.14.7**, **`GET /health` was readiness** (storage check).

From **0.14.8**, **`GET /health` is liveness** (same as `/liveness` / `/healthz`).

If Kubernetes, Docker `HEALTHCHECK`, load balancers, or [Healthchecks.io](https://healthchecks.io)-style pingers still call `/health` expecting Cassandra/H2 readiness, switch them to **`/readiness`** or **`/ready`** before upgrading. Leaving `/health` unchanged will keep routing traffic to a Central whose Cassandra is down.

## Examples

```bash
# process up?
curl -sf http://127.0.0.1:4000/liveness

# storage ready? (use this for k8s readinessProbe / LB backend health)
curl -sf http://127.0.0.1:4000/readiness
```

Central Docker Compose / k8s: prefer `readinessProbe` → `/readiness` and `livenessProbe` → `/liveness` (or `/healthz`).
