# Try Postgres Central (experimental)

Goal: one machine, Docker Postgres + Central jar, UI on port **4000**, so others can smoke-test `storage.type=postgres` without Cassandra.

Cassandra remains the only supported **production** backend. This path is incomplete vs Cassandra (multi-node cache, CLI tools, full gauge/synthetic rollup parity).

## Prerequisites

- Docker (for Postgres)
- JDK 17+ (Central)
- Maven 3.8+ (to build the dist)

## 1. Postgres

```powershell
cd demo\postgres
docker compose up -d
```

```bash
cd demo/postgres
docker compose up -d
```

Wait until healthy (`docker compose ps`).

## 2. Build dist

From repo root:

```powershell
mvn package -pl :glowroot-central -am "-DskipTests" "-Dglowroot.ui.skip"
```

(Omit `-Dglowroot.ui.skip` if you need rebuilt UI assets.)

Dist folder:

`central/target/glowroot-central-*-SNAPSHOT-dist/glowroot-central/`

## 3. Config (next to the jar)

Dist ships an empty `glowroot-central.properties` template that defaults to **Cassandra**. Overwrite it with the demo file **in the same directory as `glowroot-central.jar`** (there is no `conf/` subdir):

```powershell
Copy-Item demo\postgres\glowroot-central.properties `
  (Get-ChildItem central\target\glowroot-central-*-SNAPSHOT-dist\glowroot-central\glowroot-central.jar).DirectoryName `
  -Force
```

```bash
DIST=$(echo central/target/glowroot-central-*-SNAPSHOT-dist/glowroot-central)
cp demo/postgres/glowroot-central.properties "$DIST/glowroot-central.properties"
```

Confirm the file contains:

```properties
storage.type=postgres
postgres.jdbcUrl=jdbc:postgresql://127.0.0.1:5432/glowroot
```

## 4. Run

```powershell
cd (Get-ChildItem central\target\glowroot-central-*-SNAPSHOT-dist\glowroot-central).FullName
java -jar glowroot-central.jar
```

**UI:** http://127.0.0.1:4000  
**gRPC (agents):** `127.0.0.1:8181`  
**Health:** http://127.0.0.1:4000/health → `Glowroot OK`

Log lines to expect:

- `starting Glowroot Central with storage.type=postgres (experimental)`
- `startup complete`

If you see `waiting for Cassandra`, the properties file was not picked up (wrong path or empty `storage.type`).

## 5. Smoke checklist (no agent required)

| Check | Expect |
|-------|--------|
| `GET /health` | 200, body `Glowroot OK` |
| UI opens | Central layout, empty agent list OK |
| Postgres `\dt` | tables including `schema_version`, `aggregate_*`, `trace`, `trace_point`, `gauge_value`, `full_query_text` |

```powershell
docker compose -f demo\postgres\docker-compose.yml exec postgres `
  psql -U glowroot -d glowroot -c "\dt"
```

## 6. Optional: send traffic from an agent

Point any Glowroot agent at `localhost:8181`, or build/run the agent UI sandbox against Central (see `CLAUDE.md`). Then confirm the agent appears in the UI and transactions/gauges update.

## Automated tests (maintainers)

With Docker available:

```powershell
mvn test -pl :glowroot-central "-Dtest=PostgresCentralConfigIT,PostgresUserRoleIT,PostgresAgentConfigIT,PostgresConfigRepositoryIT,PostgresMetadataIT,PostgresIncidentIT,PostgresActiveAgentIT,PostgresNameDaoIT,PostgresAggregateIT,PostgresGaugeValueIT,PostgresTraceIT,StorageTypeTest" "-Dglowroot.ui.skip"
```

Or profile: `mvn test -pl :glowroot-central -Ppostgres-store "-Dglowroot.ui.skip"`

Without Docker, those ITs skip — that is not a green validation.

## Known gaps (honest)

- Experimental; not Cassandra parity (CLI `create-schema`, multi-node cache, some rollup edge cases).
- Synthetic error intervals not stored (counts only); rollup is simplified vs Cassandra.
- Expired rows are purged in batches hourly (`PostgresPurgeService`); boot also runs a purge.
- Secret encryption still uses property name `cassandra.symmetricEncryptionKey`.

See `docs/central-postgresql-storage.md` for schema versions and roadmap.
