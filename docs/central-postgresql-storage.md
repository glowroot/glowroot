# Central collector — optional PostgreSQL storage (experimental)

**Status:** experimental and incomplete. Cassandra remains the only supported production backend for Central.

This page tracks the optional `storage.type=postgres` path in glowroot-central. Prefer Cassandra unless you are developing or evaluating this work.

Related: [Choosing Embedded vs Central](https://github.com/glowroot/glowroot/wiki/Choosing-Embedded-vs-Central) (wiki still describes Cassandra-only Central today).

## What works today

| Area | Postgres path |
|------|----------------|
| Config flag + JDBC pool (HikariCP) | Yes |
| Schema migrator (`schema_version`) | Yes (v1–v11) |
| Central / user / role / agent config | Yes |
| Heartbeat, environment, alerting disabled | Yes |
| Incidents, active-agent rollups, transaction types, trace attribute names | Yes |
| Legacy v0.9 agent rollup id map (`v09_agent_rollup`) | Yes |
| Aggregates store + summary/overview/percentile/throughput reads + level rollup | **Partial** (queries/service-calls/profiles carried through rollup + from-child; not full Cassandra table-split parity) |
| Traces (leaf body + parent `trace_point` indexes) | **Partial** (schema v9+v11; not full Cassandra table-split) |
| Gauges (L0 + name discovery + simple level rollup / read fallback) | **Partial** (schema v9) |
| Synthetics (L0 store/read + simple level rollup / read fallback) | **Partial** (schema v9; no error-interval blobs) |
| Full query text DAO | **Yes** (schema v10; truncate + sha1 body + per-agent check) |
| Full Central boot (`glowroot-central.jar` serving UI + gRPC) | **Yes (experimental)** — UI default http://127.0.0.1:4000 |
| Online migration Cassandra ↔ Postgres | **No** (not planned for v1) |
| Dual-write Cassandra + Postgres | **No** |

Embedded collector is unchanged (local H2). This option applies only to Central.

## Boot (experimental)

With `storage.type=postgres`, Central skips Cassandra connect and boots via `PostgresCentralRepoModule` (UI + gRPC). Default UI port is **4000**.

Cassandra remains the supported production backend. Postgres path is incomplete vs Cassandra (multi-node cache, CLI tools, full gauge/synthetic rollup parity).

## v0.9 agent rollups

Cassandra `V09AgentRollupDao` maps legacy agent ids (slash-separated) for agents that may still connect. Postgres implements the same table (`v09_agent_rollup`) via `PostgresV09AgentRollupDao` — **not** a silent no-op. Gap vs Cassandra: no ClusterManager cache invalidation across Central nodes yet (single-node Postgres first).

## Configuration

In `glowroot-central.properties`:

```properties
# default = cassandra (omit or leave empty)
storage.type=postgres

postgres.jdbcUrl=jdbc:postgresql://127.0.0.1:5432/glowroot
postgres.username=
postgres.password=
# optional; default 20
postgres.maximumPoolSize=20
```

Rules:

- Empty / `cassandra` → existing Cassandra path; all `cassandra.*` properties apply as today.
- `postgres` → Central does not use Cassandra contact points for repo storage. Missing `postgres.jdbcUrl` fails fast.
- Secret encryption still uses `cassandra.symmetricEncryptionKey` for now (same property name as the Cassandra path).

## Schema (Postgres)

Migrations live in `org.glowroot.central.storage.postgres.PostgresSchemaUpgrade`.

| Version | Tables |
|---------|--------|
| 1 | `central_config` |
| 2 | `"user"`, `role` (seeded anonymous / Administrator) |
| 3 | `agent_config`, `agent_display` |
| 4 | `heartbeat`, `environment`, `alerting_disabled` |
| 5 | `open_incident`, `resolved_incident`, `active_top_level_rollup`, `active_child_rollup`, `transaction_type`, `trace_attribute_name` |
| 6 | `v09_agent_rollup` |
| 7 | `aggregate_overall`, `aggregate_transaction`, `aggregate_needs_rollup` |
| 8 | `aggregate_needs_rollup_from_child` |
| 9 | `trace`, `gauge_value`, `synthetic_result` |
| 10 | `full_query_text`, `full_query_text_check` |
| 11 | `trace_point` (listing indexes for leaf + parent agent rollups) |

Cassandra TTLs are emulated with `expires_at` columns and filtered on read. Boot-time `PostgresPurge` deletes expired rows on admin-module create; a scheduled sweeper is not shipped yet. Schema upgrades lock `schema_version` with `SELECT … FOR UPDATE`.

Local try (Docker Postgres + properties + jar smoke): see `demo/postgres/README.md`.

Aggregates store the `Aggregate` protobuf (`aggregate_bytes`) plus denormalized summary columns and delimited `SharedQueryText` bytes for query merge. Long query texts are truncated like Cassandra; full text lives in `full_query_text` keyed by sha1.

## Code layout

| Path | Role |
|------|------|
| `central/.../storage/StorageType.java` | `cassandra` / `postgres` |
| `central/.../storage/postgres/` | JDBC DAOs, Hikari factory, schema upgrade |
| `central/.../repo/PostgresCentralAdminModule.java` | Wires admin `ConfigRepository`, metadata, v09, aggregate slice |
| `central/.../CentralModule.java` | Startup branch; Postgres via `PostgresCentralRepoModule` |

Cassandra DAOs under `central/.../repo/` stay the default path. New Postgres work should mirror repository contracts and leave the Cassandra path untouched when `storage.type` is not `postgres`.

## Tests

Integration tests use Testcontainers (`postgres:16-alpine`), JUnit `@Tag("postgres-store")`, and `@Testcontainers(disabledWithoutDocker = true)`.

```bash
mvn test -pl :glowroot-central -Ppostgres-store -Dglowroot.ui.skip
```

Or name classes explicitly (PowerShell: quote `-D*` arguments):

```bash
mvn test -pl :glowroot-central "-Dtest=PostgresCentralConfigIT,PostgresUserRoleIT,PostgresAgentConfigIT,PostgresConfigRepositoryIT,PostgresMetadataIT,PostgresIncidentIT,PostgresActiveAgentIT,PostgresNameDaoIT,PostgresAggregateIT,PostgresGaugeValueIT,PostgresTraceIT,PostgresSyntheticResultIT,StorageTypeTest" "-Dglowroot.ui.skip"
```

Without Docker, those ITs skip. That is not a green run.

## Adding a DAO

1. Match the Cassandra DAO / `*Repository` contract.
2. Add a migration in `PostgresSchemaUpgrade` (bump `CURRENT_VERSION`).
3. Implement under `storage/postgres/`.
4. Wire through `PostgresCentralAdminModule` (or a future full Postgres repo module when boot is enabled).
5. Add a Testcontainers IT.
6. Update the tables on this page.

TTL data: store `expires_at` (see `PostgresExpires`). Do not block the shared JDBC async executor by nesting `.join()` on another task from that same pool — use a synchronous helper when already on the executor thread.

## Roadmap (high level)

1. Gauge/synthetic needs-queue parity; synthetic error-interval blobs.
2. CLI tooling story + wiki; scheduled purge sweeper.
3. Multi-node cache invalidation for Postgres path.

## Non-goals (v1)

- Removing Cassandra
- SQLite or other SQL dialects
- Dual-write or live C* ↔ PG migration
- Changing Embedded (H2) storage
