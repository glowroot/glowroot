# UI (`ui/`)

The Glowroot UI is **one AngularJS 1.x SPA** plus a **Java HTTP/JSON layer**
in the same Maven module (`glowroot-ui`). The same assets and most JSON
services are used in **embedded** mode (agent JVM) and **central** mode
(`glowroot-central`).

Parent maps: [repository-layout.md](repository-layout.md) ·
[product-map.md](product-map.md) · [central.md](central.md).

Operator how-tos (tabs, alerts, …) mostly live on the
[wiki](https://github.com/glowroot/glowroot/wiki) — this page is the **in-repo
map** of sources and seams.

## Quick map

```text
ui/
├── app/                    AngularJS SPA (sources)
│   ├── scripts/            app.js, routes.js, controllers/, services/
│   ├── views/              HTML templates (ui-router states)
│   ├── styles/, template/, fonts/, hbs/
│   └── …
├── src/main/java/org/glowroot/ui/   HTTP server + *JsonService / *HttpService
├── Gruntfile.js, package.json, bower.json
└── pom.xml                 frontend-maven-plugin → grunt build → jar resources
```

**Dev entry (local):** UI sandbox / central on `:4000`; `./grunt serve` on
`:9000` proxies API to `:4000` (see `CONTRIBUTING.md`).

## Two halves of the module

| Half | Path | Role |
|------|------|------|
| Frontend | `ui/app/` | AngularJS SPA: routes, controllers, templates, LESS/SCSS-style assets via Grunt |
| Backend (in-process) | `ui/src/main/java/org/glowroot/ui/` | Netty (or servlet-hosted) HTTP: static index, JSON APIs, health, trace export |

Embedded and central **depend on** `glowroot-ui` and wire a `UiModule` /
handlers into their process. Central also exposes the UI through
`GlowrootServlet` ([central.md](central.md)).

Do **not** use `-Dglowroot.ui.skip` when changing `ui/app` — assets must
rebuild into the jar.

## Frontend (`app/`)

### Key scripts

| File / dir | Role |
|------------|------|
| `scripts/app.js` | Angular module bootstrap |
| `scripts/routes.js` | **ui-router** states and URL query params (`agent-id`, `agent-rollup-id`, `transaction-type`, …) |
| `scripts/controllers/` | Page controllers (`transaction/`, `jvm/`, `config/`, `admin/`, …) |
| `scripts/services/` | Shared Angular services (HTTP helpers, charts, login, …) |
| `scripts/directives.js`, `filters.js` | Shared directives / filters |
| `scripts/handlebars-rendering.js`, `trace-export.js` | Trace detail / export helpers |

### Main UI areas (`views/` + matching states)

| Area | Typical routes (prefix) | Templates under |
|------|-------------------------|-----------------|
| Transactions | `/transaction/…` | `views/transaction/` (average, percentiles, throughput, traces, queries, service-calls, thread-profile, flame graph) |
| Errors | `/error/…` | Same transaction shell + error tabs/messages |
| JVM | `/jvm/…` | gauges, thread dump, jstack, heap dump/histogram, force GC, MBeans, system properties, environment, capabilities |
| Config | `/config/…` | transaction, JVM, plugins, instrumentation, gauges, alerts, synthetics, UI defaults, advanced, JSON |
| Admin | `/admin/…` | users/roles, SMTP, storage, web, HTTP proxy, integrations, … |
| Profile / report / incidents / synthetics / login | matching top-level states | `views/profile/`, `report/`, … |

Exact state names and `templateUrl`s: `app/scripts/routes.js`.

### Central vs embedded in the SPA

`routes.js` waits for `$rootScope.layout` (from the backend layout API).

- If `layout.central` is true, navigation resolves **agent / agent-rollup**
  query params and loads rollup metadata (`backend/agent-rollup?id=…`).
- Embedded mode skips rollup selection and uses the single local agent.

**Rule for UI changes:** central-only chrome/pages must stay gated the same
way neighboring code uses `layout.central` (and related permission flags).
Do not show central-only admin/storage concepts in embedded without a reason.

Wiki UI orientation:
[Transaction tabs](https://github.com/glowroot/glowroot/wiki/Transaction-tabs)
and the wiki Home “UI / configuration” section.

## Backend Java (`org.glowroot.ui`)

Shared by embedded and central. Embedded/central supply **repository** and
**live** implementations; `UiModule` only wires HTTP + JSON.

### Boot: `UiModule`

`UiModule.createUiModule(…)` (Immutables `@Builder.Factory`) takes flags
`central`, `servlet`, `offlineViewer`, plus repos (`ConfigRepository`,
`AggregateRepository`, `TraceRepository`, …) and live ports
(`LiveJvmService`, `LiveTraceRepository`, `LiveAggregateRepository`,
`LiveWeavingService`).

It builds:

1. Shared helpers — `TransactionCommonService`, `TraceCommonService`,
   `ErrorCommonService`, `LayoutService`
2. The list of `@JsonService` objects (see below)
3. Pattern → `HttpService` map (SPA shell, export, health, logs, …)
4. `CommonHandler` — single dispatcher for Netty **or** servlet
5. Either:
   - **Netty** `HttpServer` (standalone embedded / standalone central), or
   - **Servlet** mode: only `CommonHandler` (central in a servlet container via
     `GlowrootServlet`)

Central also gets a bounded report thread pool
(`Glowroot-UI-Report-Worker`); embedded uses a direct executor for reports.

### Dispatch: two endpoint styles

| Style | Mechanism | Typical paths |
|-------|-----------|---------------|
| **HttpService** | `Map<Pattern, HttpService>` registered in `UiModule`; `handleRequest(CommonRequest, Authentication)` | `/`, `/transaction/…`, `/export/trace`, `/backend/trace/entries`, `/health`, `/readiness`, `/log`, … |
| **JsonService** | Classes annotated `@JsonService`; methods `@GET` / `@POST` with `path` + `permission`; scanned by `CommonHandler` into `JsonServiceMapping`s | Almost everything under `/backend/…` that returns JSON |

Annotations (package-private in `JsonService.java`): `@GET`, `@POST`,
`@BindAgentId`, `@BindAgentRollupId`, `@BindRequest`, `@BindAutoRefresh`,
`@BindAuthentication`. Expected client errors use `JsonServiceException`
(status without full stack spam).

`CommonHandler` enforces permissions from the annotation (and special-cases
layout/login/sign-out). Paths under `/backend/` go through the JSON mapping
table first.

`HttpServices` (plural) is only Netty channel helper utilities (log vs ignore
browser disconnect), not a registry.

### HttpService routes (registered in `UiModule`)

| Pattern | Service | Role |
|---------|---------|------|
| `^/$`, `^/transaction/.*$`, `^/error/.*$`, `^/jvm/.*$`, `^/incidents$`, `^/report/.*$`, `^/config/.*$`, `^/admin/.*$`, `^/profile/.*$`, `^/login$` | `IndexHtmlHttpService` | SPA shell (same index for deep links) |
| `^/synthetic-monitors$` | `IndexHtmlHttpService` | **Central only** |
| `^/export/trace$` | `TraceExportHttpService` | Downloadable export (not under `/backend` on purpose) |
| `^/backend/trace/entries$`, `…/queries$`, `…/main-thread-profile$`, `…/aux-thread-profile$` | `TraceDetailHttpService` | Larger / streamed trace detail payloads |
| `^/log$` | `GlowrootLogHttpService` | Agent/central log file HTTP access |
| `^/health$`, `^/liveness$`, `^/healthz$` | `LivenessHttpService` | Process up |
| `^/readiness$`, `^/ready$` | `HealthCheckHttpService` | Storage check via `RepoAdmin` |

Health semantics: [health-endpoints.md](health-endpoints.md).

### Session / auth

| Type | Role |
|------|------|
| `HttpSessionManager` | Login session lifecycle; uses `SessionMapFactory` |
| `SessionMapFactory` | Session map abstraction (local vs clustered central) |
| `LdapAuthentication` | LDAP bind path when configured |
| `Authentication` (inner to session manager) | Permission checks against `@GET/@POST` permission strings |

### JSON services (wired in `UiModule`)

Each class is `@JsonService`. Paths are method annotations — examples from
`TransactionJsonService`: `/backend/transaction/average`, `…/percentiles`,
`…/throughput`, `…/queries`, `…/service-calls`, `…/profile`, `…/flame-graph`,
…. `LayoutJsonService` exposes `/backend/top-level-agent-rollups`,
`/backend/child-agent-rollups`, `/backend/agent-rollup`, etc.

| Service | Domain | Notes |
|---------|--------|--------|
| `LayoutJsonService` + `LayoutService` | Layout, nav, permissions, `central` flag | First paint dependency for the SPA |
| `TransactionJsonService` | Transaction charts / tabs | Uses `TransactionCommonService` + aggregates |
| `TracePointJsonService`, `TraceJsonService` | Trace points + trace JSON | Points vs full trace payload |
| `ErrorJsonService` | Error messages / error traces | |
| `GaugeValueJsonService` | Gauge charts | |
| `JvmJsonService` | JVM pages | Uses `LiveJvmService` when live ops are needed |
| `IncidentJsonService` | Incidents | |
| `ReportJsonService` | Ad-hoc reports | Uses `reportingExecutor` on central |
| `ConfigJsonService` | General agent/UI config pages | |
| `AlertConfigJsonService` | Alert config | |
| `GaugeConfigJsonService` | Gauge config | May call `LiveJvmService` for MBean meta |
| `InstrumentationConfigJsonService` | Custom instrumentation | Uses `LiveWeavingService` / `LiveJvmService`; central-aware |
| `UserConfigJsonService`, `RoleConfigJsonService` | Users / roles | |
| `AdminJsonService` | Admin (web, storage, SMTP, …) | Holds `HttpServer` ref when Netty-bound (port changes) |
| `SyntheticResultJsonService`, `SyntheticMonitorConfigJsonService` | Synthetics | **Added only if `central`** |

### Shared helpers (not HTTP endpoints)

| Type | Role |
|------|------|
| `TransactionCommonService`, `TraceCommonService`, `ErrorCommonService` | Merge live + stored data for JSON services |
| `DataSeries`, `DataSeriesHelper`, `ChartMarking` | Chart series shaping |
| `AggregateMerging`, `StackedTimerTotals`, `BreakdownTimerMetrics` | Aggregate / timer breakdown math |
| `MultiErrorIntervalCollector`, `MultiErrorIntervalMerger` | Error interval merging |
| `QueryStrings`, `ObjectNames`, `AllConfigDto` | Request/config DTO helpers |
| `ConditionalHttpContentCompressor`, `ChunkedInputs`, `ChunkSource` | Response encoding / chunking |

### Live vs stored (central)

On **central**, `JvmJsonService` / instrumentation / some trace paths call
`Live*` interfaces that are backed by gRPC downstream
([central.md — gRPC surface](central.md#grpc-surface)). On **embedded**, the
same interfaces talk to in-process agent services. JSON service code stays
shared; only the `UiModule` constructor args differ.

## Build pipeline

1. Maven `ui` module runs **frontend-maven-plugin** (Node/npm as pinned in
   `pom.xml`).
2. **Grunt** concatenates/minifies JS, compiles styles, templates, revving.
3. Built assets land on the classpath of `glowroot-ui` and are served by
   embedded/central.

Local iteration:

```bash
cd ui
./grunt serve          # :9000 → proxy API to :4000
./grunt serve:demo     # proxy to public demo (host may be down — see README #1102)
```

Browser ITs that click this UI: [`webdriver-tests/`](../webdriver-tests/)
(see [repository-layout.md](repository-layout.md)).

## Changing the UI safely

1. Match existing AngularJS patterns in the same folder (controllers/services).
2. Gate central-only behavior with `layout.central` (or the same checks
   neighbors use).
3. Rebuild UI assets (no `glowroot.ui.skip`).
4. Smoke embedded **and** central if the page exists in both modes.
5. Prefer a webdriver or focused manual check for nav/query-param regressions.

## What this page omits

- Pixel-level UX / wiki operator tutorials.
- Every ui-router state and every `*JsonService` method.
- Modern rewrite / alternate UI trees (not on `main` product path).
