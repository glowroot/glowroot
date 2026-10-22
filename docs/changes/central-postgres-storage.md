# Experimental PostgreSQL storage for Central

## What

Optional `storage.type=postgres` path for Glowroot Central (Cassandra remains
default / production). Includes DAOs, TTL purge, Testcontainers ITs, docs, and
`demo/postgres/`.

## Why

Operators who already run Postgres want Central without Cassandra. Keep
Cassandra as the supported default while an experimental path matures.

## How

- HikariCP, schema migrations, JDBC executor sized to pool
- DAOs: config/admin, aggregates (+ FQT), gauges, traces, synthetics, incidents,
  heartbeat, environment
- Rollups + `expires_at` purge (`PostgresPurgeService`)
- ITs `@Tag(postgres-store)`; `docs/central-postgresql-storage.md`; demo compose

## Choices

Marked experimental. Gaps vs Cassandra called out (gauge/synthetic needs-queue
parity, CLI tools, multi-node cache invalidation, etc.). Does not change default
Cassandra boot.

## Verification

- [ ] Focused Postgres IT list on `:glowroot-central`
- [ ] `demo/postgres` compose + `/health` + schema tables
- [ ] Default Cassandra config still boots without `storage.type=postgres`
