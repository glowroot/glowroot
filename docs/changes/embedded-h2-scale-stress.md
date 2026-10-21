# Embedded H2 scale stress harness + index/SQL hardening

## What

Adds `agent/stress` lab tooling (`stress.jar`) and schema/query indexes for
large embedded H2 stores. Not a second JDBC pool.

## Why

Embedded H2 at tens of GB is where Glowroot hurts (#755, #1180-ish). Need
measurable load tooling and planner-friendly indexes before claiming larger
stores are workable.

## How

- `agent/stress`: fill / dual (JDBC + HTTP harvester) / JMH benches
- UNIQUE indexes in `Schemas`; covering indexes in `TraceDao` / `AggregateDao`
- Stable `ORDER BY` in `TracePointQueryBuilder`
- Opt-in `-Dglowroot.internal.h2.timingLog`

## Choices

Lab module stays out of agent dist. Hikari read/write split deferred. Existing
~60 GB DBs may spend hours rebuilding indexes on first boot after upgrade —
call out maintenance window. Closes #1243 as part of H2 workstream.

## Verification

- [x] `SchemasTest`, stress module tests, fill `--target-gb=1`
- [x] Small/empty store still boots
- [ ] Maintainer dual run on large dir after index sync
