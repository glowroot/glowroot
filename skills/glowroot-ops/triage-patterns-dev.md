# Triage patterns — dev-first

Patterns for developers opening Glowroot for the first time.  
Full upstream triage KB (if available in repo): `doc/issue-map/TRIAGE-KNOWLEDGE.md`.

Org paths and stack: `ORG-CONTEXT.md`.

## Install — common confusion

| Pattern | Dev assumption | Reality |
|---------|----------------|---------|
| Too many steps | Complex setup | Unzip → `-javaagent` → start app → open `:4000` |
| JVM arg order | Any order | `-javaagent` before `-jar` |
| Wrong jar | Built from source | Releases `*-dist.zip` only |
| UI before app | Browser first | UI starts with the JVM |
| One-click script | Needs automation | Valid — keep script minimal: path + one JVM arg line |

## UI — where to look

| Looking for… | Tab | Not… |
|--------------|-----|------|
| Slowest requests | Transactions (sort by time) | Log grep |
| SQL | Trace → Queries | Service Calls |
| Outbound HTTP | Trace → Service Calls | Local `@Service` |
| Method time | Trace → Profile | Breakdown alone |
| Timer tree | Trace → Breakdown | Entry row count |
| All logs | App log file | Errors tab |
| Errors in requests | Errors | catalina.out only |
| JVM / GC | JVM / Gauges | Transaction list |

## Glowroot is NOT

| Myth | Fact |
|------|------|
| Replaces logging | Complements logs; limited retention |
| Infinite storage | H2 retention + caps on query text |
| Full distributed tracing | Per-JVM; no cross-service trace IDs |
| Local `@Service` in Service Calls | Outbound plugins only |
| Native Prometheus | HTTP API only (`#952`) |
| Request/response body | Not in mainline |
| Browser RUM | Server-side JVM only |
| 100% sampling knob | Lower slow threshold for more traces |

## Retention

- Embedded data in local H2 (`data/`)
- Retention in Administration → Storage
- Raising retention does not keep old data retroactively
- Not a log archive

## Common issue refs

| ID | Topic |
|----|-------|
| `#746` `#812` | Service Calls empty on Spring beans |
| `#937` | Queries empty (non-JDBC) |
| `#761` | Breakdown count vs Entries rows |
| `#714` | Breakdown timers vs root |
| `#887` | Errors vs container log |
| `#1003` | H2 locked — shared data.dir |
| `#710` | `-javaagent` before `-jar` |
| `#1124` | Classes loaded before agent |

## Five-minute first exercise

1. **Transactions** — last 1 hour
2. Sort by average time
3. Open slowest trace
4. **Breakdown** → **Queries** → **Profile**
5. Ask: what would logs alone miss?

Skip on first pass: Alerts, deep Errors, `config.json`, Central, GC deep dive.
