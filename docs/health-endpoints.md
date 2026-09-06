# HTTP health endpoints (UI / Central)

Applies to the Glowroot HTTP UI port (embedded agent UI and glowroot-central).

## Endpoints (0.14.8+)

| Path | Role | Behavior |
|------|------|----------|
| `/liveness`, `/healthz`, **`/health`** | **Liveness** | Always `200` + `Glowroot OK` if the HTTP process is up. Does **not** check Cassandra (Central) or H2. |
| `/readiness`, `/ready` | **Readiness** | Runs storage health (`RepoAdmin.runHealthCheck()`). Central: Cassandra probe. Embedded: H2. Failure → `503` + plain-text error. |

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
