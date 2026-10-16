# Central collector — optional PostgreSQL storage (experimental)

**Status:** experimental and incomplete. Cassandra remains the only supported production backend for Central.

This page tracks the optional `storage.type=postgres` path in glowroot-central. Prefer Cassandra unless you are developing or evaluating this work.

Related: [Choosing Embedded vs Central](https://github.com/glowroot/glowroot/wiki/Choosing-Embedded-vs-Central) (wiki still describes Cassandra-only Central today).

## What works today

| Area | Postgres path |
|------|----------------|
| Config flag + JDBC pool (HikariCP) | Yes |
| Schema migrator (`schema_version`) | Yes (v1–v7) |
| Central / user / role / agent config | Yes |
| Heartbeat, environment, alerting disabled | Yes |
| Incidents, active-agent rollups, transaction types, trace attribute names | Yes |
| Legacy v0.9 agent rollup id map (`v09_agent_rollup`) | Yes |
| Aggregates store + summary/overview/percentile/throughput reads + level rollup | **Partial** (Aggregate protobuf rows; not full Cassandra parity for profiles/service-calls/FQT) |
| Traces, gauges, synthetics, full query text DAO | **No** |
| Full Central boot (`glowroot-central.jar` serving UI + gRPC) | **No** — see [Boot gate](#boot-gate) |
| Online migration Cassandra ↔ Postgres | **No** (not planned for v1) |
| Dual-write Cassandra + Postgres | **No** |

Embedded collector is unchanged (local H2). This option applies only to Central.

## Boot gate

`CentralModule` still refuses full Postgres boot after validating JDBC + schema + admin/metadata + aggregate truncate smoke.

**Intentional:** do not lift the gate until traces (and remaining hot-path DAOs) exist. Shipping a UI/gRPC process that cannot store traces would mislead operators.

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

Cassandra TTLs are emulated with `expires_at` columns and filtered on read. Scheduled purge jobs are not shipped yet.

Aggregates store the `Aggregate` protobuf (`aggregate_bytes`) plus denormalized summary columns and delimited `SharedQueryText` bytes for query merge.

## Code layout

| Path | Role |
|------|------|
| `central/.../storage/StorageType.java` | `cassandra` / `postgres` |
| `central/.../storage/postgres/` | JDBC DAOs, Hikari factory, schema upgrade |
| `central/.../repo/PostgresCentralAdminModule.java` | Wires admin `ConfigRepository`, metadata, v09, aggregate slice |
| `central/.../CentralModule.java` | Startup branch; rejects incomplete Postgres boot |

Cassandra DAOs under `central/.../repo/` stay the default path. New Postgres work should mirror repository contracts and leave the Cassandra path untouched when `storage.type` is not `postgres`.

## Tests

Integration tests use Testcontainers (`postgres:16-alpine`), JUnit `@Tag("postgres-store")`, and `@Testcontainers(disabledWithoutDocker = true)`.

```bash
mvn test -pl :glowroot-central -Ppostgres-store -Dglowroot.ui.skip
```

Or name classes explicitly (PowerShell: quote `-D*` arguments):

```bash
mvn test -pl :glowroot-central "-Dtest=PostgresCentralConfigIT,PostgresUserRoleIT,PostgresAgentConfigIT,PostgresConfigRepositoryIT,PostgresMetadataIT,PostgresIncidentIT,PostgresActiveAgentIT,PostgresNameDaoIT,PostgresAggregateIT,StorageTypeTest" "-Dglowroot.ui.skip"
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

1. Harden aggregate rollup (child rollups, profiles, service calls, FQT) toward `AggregateDaoIT` parity.
2. Traces, gauges, synthetics, full query text.
3. Readiness/liveness parity, CLI tooling story, wiki + Compose example; only then lift the boot gate.

## Non-goals (v1)

- Removing Cassandra
- SQLite or other SQL dialects
- Dual-write or live C* ↔ PG migration
- Changing Embedded (H2) storage
