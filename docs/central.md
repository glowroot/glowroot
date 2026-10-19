# Central collector (`central/`)

Map of the **glowroot-central** module on `main`. For the whole repo, see
[repository-layout.md](repository-layout.md). For how agents reach central, see
[product-map.md](product-map.md).

Central is a **separate JVM** (Java **17+**) that:

1. Accepts agent data over **gRPC** (`wire-api`)
2. Persists and rolls up data in **Cassandra**
3. Serves the shared **UI** over HTTP
4. Runs **alerting**, heartbeats, and **synthetic monitors**

**Entry:** `org.glowroot.central.Main` → `CentralModule`.

Also: `Main` supports commands such as `create-schema` (and other admin
commands via `CentralModule.runCommand`).

## Quick map

```text
central/src/main/java/org/glowroot/central/
├── Main.java                      Process entry + CLI commands
├── CentralModule.java             Wires repos, gRPC, rollup, alerts, UI
├── GrpcServer.java                gRPC server lifecycle
├── CollectorServiceImpl.java      Agent → central ingest (CollectorService)
├── DownstreamServiceImpl.java     Central → agent downstream RPCs
├── GlowrootServlet.java           HTTP UI / API servlet
├── RollupService.java             Periodic metric / aggregate rollups
├── CentralAlertingService.java    Central-side alert orchestration
├── HeartbeatAlertingService.java  Heartbeat-based alerts
├── SyntheticMonitorService.java   Synthetic monitor runs / results
├── Live*ServiceImpl.java          Live JVM / trace / weaving for UI
├── UpdateAgentConfigIfNeededService.java
├── Directories.java               Central data / config directories
├── Procrun.java                   Windows service helper
├── repo/                          Cassandra DAOs + schema
├── util/                          Session, cache, cluster helpers
└── v09support/                    Compatibility shims for older agent rollups
```

## Boot and wiring

`CentralModule` owns the long-lived pieces:

| Piece | Role |
|-------|------|
| `Directories` | Resolves central home / config / data paths |
| `CentralRepoModule` | Builds Cassandra-backed repositories |
| `GrpcServer` | Hosts collector + downstream gRPC services |
| `RollupService` | Background rollups |
| `AlertingService` / `CentralAlertingService` / `HeartbeatAlertingService` | Incident evaluation and notification paths |
| `SyntheticMonitorService` | Synthetic checks |
| `UpdateAgentConfigIfNeededService` | Pushes / syncs agent config when needed |
| HTTP UI | Via `GlowrootServlet` (standalone) or `createForServletContainer` when embedded in a servlet container |

Standalone process: `Main.start()`. Schema-only: `Main` → `create-schema`.

## gRPC surface

Wire contracts: **[wire-api.md](wire-api.md)**. Implementations live next to
`CentralModule` under `org.glowroot.central`.

### `GrpcServer`

Owns one or two Netty gRPC servers (`NettyServerBuilder`):

| Listener | When |
|----------|------|
| HTTP gRPC port | `httpPort` configured |
| HTTPS gRPC port | `httpsPort` configured (SSL context from conf dir; watches cert files and rebuilds context) |

Registers both service impls on each started server:

- `CollectorServiceImpl` (agent → central ingest)
- `DownstreamServiceImpl` (bidi live channel)

Default agent collector address in docs/wiki is typically **`:8181`** (configure
in central properties — not hard-coded in this map).

### `CollectorServiceImpl`

Extends generated `CollectorServiceGrpc.CollectorServiceImplBase`. Writes
through Cassandra DAOs (`AgentConfigDao`, `ActiveAgentDao`, `EnvironmentDao`,
`HeartbeatDao`, `AggregateDao`, `GaugeValueDao`, `TraceDao`, …) and may trigger
`CentralAlertingService` after ingest.

| RPC (from proto) | Impl notes |
|------------------|------------|
| `collectInit` | Registers agent env + config; returns `InitResponse` (config / central version) |
| `collectAggregateStream` | Preferred path; stream observer assembles header + shared query texts + overall/transaction aggregates |
| `collectAggregates` | Legacy unary path still implemented |
| `collectGaugeValues` | Gauge samples → `GaugeValueDao` |
| `collectTraceStream` | Preferred trace ingest (header / entries / profiles / counts messages) |
| `collectTrace` | Legacy unary path still implemented |
| `log` | Agent log events |

`GrpcCommon` maps **v09 agent ids / rollup ids** to post-v09 agent ids for
logging and storage keys (`V09AgentRollupDao`).

### `DownstreamServiceImpl`

Extends generated `DownstreamServiceImplBase`. Agents call `connect` and keep a
**bidi stream**: central pushes `CentralRequest`, agent answers
`AgentResponse` with the same `request_id`.

Central UI “live” actions do **not** speak gRPC from the browser. They call
`Live*Service` interfaces; on central those are:

| Adapter | Interface | Forwards to `DownstreamServiceImpl` |
|---------|-----------|-------------------------------------|
| `LiveJvmServiceImpl` | `LiveJvmService` | thread dump, jstack, heap dump/histogram, force GC, MBeans, system props, capabilities, disk space, … |
| `LiveTraceRepositoryImpl` | `LiveTraceRepository` | live/partial trace header, entries, queries, profiles, full trace |
| `LiveWeavingServiceImpl` | `LiveWeavingService` | classpath/class/method matching, reweave, global meta |

Also used for **agent config update** requests over the same stream
(`AgentConfigUpdateRequest`). `ClusterManager` +
`DistributedExecutionMap` help route a request to the central node that holds
the agent connection in a multi-node setup.

```text
Browser → ui *JsonService → LiveJvmService / LiveTraceRepository / LiveWeavingService
                              → (central) Live*ServiceImpl
                              → DownstreamServiceImpl.connect stream
                              → Agent
```

## `repo/` (Cassandra)

Persistence and admin live under `org.glowroot.central.repo`.
`CentralRepoModule` constructs the DAO graph; `SchemaUpgrade` migrates schema.

| DAO / type (name on `main`) | Concern |
|----------------------------|---------|
| `TraceDao` / `TraceDaoImpl` | Stored traces |
| `AggregateDao` / `AggregateDaoImpl` | Transaction aggregates |
| `GaugeValueDao` / `GaugeValueDaoImpl`, `GaugeNameDao` | Gauge series |
| `FullQueryTextDao`, `SharedQueryTextsForExport` | Query text storage / export |
| `AgentConfigDao`, `ConfigRepositoryImpl` | Per-agent and central config |
| `ActiveAgentDao`, `AgentDisplayDao`, `AgentRollupIds` | Agent inventory / rollup ids |
| `EnvironmentDao`, `HeartbeatDao` | Environment + heartbeats |
| `IncidentDao`, `AlertingDisabledDao` | Alerting state |
| `SyntheticMonitorIdDao`, `SyntheticResultDao*` | Synthetics |
| `TransactionTypeDao`, `TraceAttributeNameDao` | Dimension / attribute names |
| `UserDao`, `RoleDao` | UI authz users/roles |
| `CentralConfigDao` | Central-level config |
| `RepoAdminImpl` | Health / admin (`/readiness` storage check uses repo admin) |
| `Tools`, `SchemaUpgrade` | Maintenance and schema evolution |
| `V09AgentRollupDao` | Legacy agent-rollup support |

Exact CQL and table names are in the DAO implementations and schema upgrade
code — this page is a **navigation map**, not a schema dump.

## `util/`

Package `org.glowroot.central.util` — Cassandra session helpers, optional
**Infinispan/JGroups** clustering, and small concurrency utilities used by
`repo/` and gRPC services.

### Cassandra session

| Type | Role |
|------|------|
| `Session` | Wrapper around DataStax `CqlSession`: execute / async execute, retries around timeouts and node failures, schema introspection helpers, consistency helpers. DAOs go through this rather than raw driver calls. |
| `CassandraWriteMetrics` | Tracks write volume / totals exposed via repo admin (`CassandraWriteTotals`) for the storage UI |
| `Messages` | Serialize / parse lists of Protobuf messages to/from `ByteBuffer` (delimited) for blob columns |
| `ByteBufferInputStream` | Stream view over a `ByteBuffer` for parsers |

### Clustering (`ClusterManager`)

`ClusterManager.create()` — local-only; `create(confDir, jgroupsProperties)` —
embedded **Infinispan** manager for multi-central nodes.

Factory-style APIs (implementations differ for local vs clustered):

| API | Role |
|-----|------|
| `createPerAgentCache` / `createPerAgentAsyncCache` | Sized caches keyed for per-agent data; `invalidate` is cluster-wide when clustered |
| `createSelfBoundedAsyncCache` | Async cache with invalidation-oriented cache mode |
| `createReplicatedMap` / `createReplicatedLockSet` | Shared maps / locks across nodes |
| `createDistributedExecutionMap` | Register values (e.g. connected downstream agents) and `execute(key, …)` a task on the node that owns the entry — used so live gRPC calls hit the right central instance |

Interfaces: `Cache`, `AsyncCache` (sync/async get + global invalidate +
loader), `DistributedExecutionMap`.

`LocalCacheStats` / `LocalCacheStatsMXBean` expose Guava cache stats over JMX
where wired.

### Executors, futures, limits

| Type | Role |
|------|------|
| `MoreExecutors2` | Named thread pools / scheduled executors (`newSingleThreadExecutor`, `newFixedThreadPool`, …) used across central (gRPC conf watcher, cluster workers, …) |
| `MoreFutures` | `waitForAll`, async transform helpers over Cassandra `AsyncResultSet` / `CompletableFuture` (rollup pipelines) |
| `RateLimiter` | Guava-cache-backed “try acquire once per key per day” style limiter (dedupe noisy work) |

## `v09support/`

Compatibility wrappers so newer central can still read / roll up data shapes
associated with older (v09) agent rollup layouts — e.g.
`TraceDaoWithV09Support`, `AggregateDaoWithV09Support`,
`GaugeValueDaoWithV09Support`, `SyntheticResultDaoWithV09Support`,
`V09Support`.

Touch these only when changing rollup identity or cross-version storage.

## Dependencies (Maven)

From `central/pom.xml` on `main` (high level):

- `glowroot-wire-api`, `glowroot-common`, `glowroot-common2`, `glowroot-ui`
- `glowroot-agent-api` (shared agent-facing types)
- gRPC / Netty stack, Cassandra driver

UI assets come from the `ui` module; central serves them — it does not own a
separate SPA.

## Related docs

| Doc | Topic |
|-----|--------|
| [product-map.md](product-map.md) | Embedded vs central, data path diagram |
| [agent-modules.md](agent-modules.md) | What runs inside the app JVM |
| [health-endpoints.md](health-endpoints.md) | `/health` vs `/readiness` on the UI port |
| Wiki: Central install | [Agent Installation (Central)](https://github.com/glowroot/glowroot/wiki/Agent-Installation-(for-Central-Collector)) |

## What this page omits

- Cassandra TTL / storage admin UI details (wiki Administration-Storage).
- Protobuf field-level schemas (see `.proto` files / [wire-api.md](wire-api.md)).
- Docker / k8s install steps (wiki).
