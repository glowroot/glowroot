# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

For human contributor setup, PR change-doc policy, and the full in-repo map index, see [CONTRIBUTING.md](CONTRIBUTING.md) and [docs/README.md](docs/README.md).

## Project Overview

Glowroot is a Java APM (Application Performance Monitoring) tool. It has two deployment modes:

- **Embedded**: Agent runs with the monitored application, stores data locally (H2 database), serves the UI in-process
- **Central**: Agents send data via gRPC/Protobuf to a central collector backed by **Cassandra**; central serves the same UI

## Documentation (in-repo)

Prefer these over rediscovering the tree from scratch:

| Doc | Use when |
|-----|----------|
| [docs/product-map.md](docs/product-map.md) | Deploy modes, data path, entry points |
| [docs/repository-layout.md](docs/repository-layout.md) | Top-level directories |
| [docs/agent-modules.md](docs/agent-modules.md) / [docs/agent-plugins.md](docs/agent-plugins.md) | Agent internals / plugins |
| [docs/central.md](docs/central.md) | Central gRPC, `repo/`, util, live adapters |
| [docs/wire-api.md](docs/wire-api.md) | Protobuf / gRPC contracts |
| [docs/ui.md](docs/ui.md) | AngularJS SPA + `org.glowroot.ui` |
| [docs/pr-change-template.md](docs/pr-change-template.md) | Required note for non-trivial PRs → `docs/changes/` |

Operator install guides remain on the [wiki](https://github.com/glowroot/glowroot/wiki).

## Build Commands

Building requires Java 11+ and Maven 3.8.0+. The agent targets Java 8+ (Animal Sniffer); the central module requires Java 17+.

```bash
# Full build
mvn clean install

# Build skipping tests
mvn clean install -DskipTests

# Build a single module
mvn clean install -pl :glowroot-central
mvn clean install -pl :glowroot-agent-spring-plugin

# Run unit tests for a specific class
mvn test -Dtest=ClassName -pl :module-name

# Run a specific integration test
mvn verify -Dit.test=ClassNameIT -pl :module-name

# Run integration tests with javaagent harness (more realistic)
mvn verify -Dglowroot.it.harness=javaagent -pl :module-name

# Run webdriver (browser) tests
mvn verify -pl :glowroot-webdriver-tests

# Skip UI asset build (faster for backend-only changes)
mvn clean install -Dglowroot.ui.skip

# Run Checker Framework nullness analysis
# (see .github/scripts/build.sh "checker" target for full steps)
```

## Session start

```bash
git fetch upstream main
# fast-forward local main if behind, then push origin main on the fork if needed
```

## UI Development

```bash
cd ui
npm install
./grunt serve          # Dev server on :9000, proxies API to :4000
./grunt serve:demo     # Proxies API to demo.glowroot.org instead (host often down — #1102)
```

Do **not** use `-Dglowroot.ui.skip` when changing UI (assets must rebuild). Styles are **SCSS** (`ui/app/styles/*.scss`) via Grunt/sass.

### Local UI gates

Two backends share the same AngularJS UI — pick the one that matches the change. They can run **at the same time** if you put embedded on another port:

| Mode | URL | How |
|------|-----|-----|
| **Central** | http://127.0.0.1:4000 (gRPC `:8181`) | Cassandra on `127.0.0.1:9042`, then `java -jar glowroot-central.jar` |
| **Embedded** | http://127.0.0.1:4001 | `UiSandboxMain` with `-Dglowroot.agent.port=4001` (see below) |

**Embedded sandbox** (H2 + in-process agent that generates sample traces):

```bat
cd agent\ui-sandbox
mvn org.codehaus.mojo:exec-maven-plugin:3.6.3:exec ^
  -Dexec.executable=java ^
  -Dexec.classpathScope=test ^
  "-Dexec.args=-Dglowroot.agent.port=4001 -cp %%classpath org.glowroot.agent.ui.sandbox.UiSandboxMain"
```

**Agent → Central** (second JVM; uses `agent/ui-sandbox/target-central` so it does not lock the embedded data dir):

```bat
cd agent\ui-sandbox
mvn org.codehaus.mojo:exec-maven-plugin:3.6.3:exec ^
  -Dexec.executable=java ^
  -Dexec.classpathScope=test ^
  "-Dexec.args=-Dglowroot.sandbox.central=true -Dglowroot.sandbox.javaagent=true -cp %%classpath org.glowroot.agent.ui.sandbox.UiSandboxMain"
```

Optional agent against an existing dist: `-javaagent:…/glowroot.jar -Dglowroot.collector.address=localhost:8181`.

Use Central when verifying Central-only pages (Cassandra storage TTL, agent rollups, gRPC) or confirming embedded-only UI is hidden (`layout.central`).

## Architecture

Deep maps: [docs/product-map.md](docs/product-map.md) and linked pages. Summary below.

### Module Structure

- **`agent/api`**, **`agent/plugin-api`**, **`agent/bytecode-api`** — Public APIs for agent and plugin development
- **`agent/core`** — Agent runtime: bytecode weaving (ASM), trace collection, config
- **`agent/embedded`** — Embedded collector (local H2 + UI in the app JVM)
- **`agent/plugins/*`** — 27 instrumentation plugins on `main` (Servlet/Jakarta, Spring, JDBC, …); see [docs/agent-plugins.md](docs/agent-plugins.md)
- **`agent/shaded/*`** — Shaded JARs under `org.glowroot.agent.shaded.*` (avoid app classpath clashes)
- **`agent/it-harness`**, **`agent/integration-tests`** — Agent IT harness and suites
- **`agent/dist`** — Distribution packaging (`glowroot-*-dist.zip`)
- **`agent/ui-sandbox`** — Local sample-traffic UI sandbox
- **`central/`** — Central collector: gRPC, Cassandra, rollups, alerts, UI serving (Java 17+)
- **`common/`**, **`common2/`** — Shared config, model, utilities
- **`wire-api/`** — Protobuf / gRPC (`CollectorService`, `DownstreamService`, Trace, Aggregate, …)
- **`ui/`** — AngularJS 1.x SPA + Java `org.glowroot.ui` HTTP/JSON layer (Grunt/SCSS)
- **`webdriver-tests/`** — Selenium browser integration tests
- **`docs/`** — In-repo documentation maps and topic notes
- **`build/`**, **`.github/`** — Build helpers and CI

### Data Flow

- **Embedded:** weaving / plugins → embedded collector → H2 → HTTP UI (same JVM)
- **Central:** weaving / plugins → gRPC (`wire-api`) → central → Cassandra (+ rollups) → HTTP UI

UI JSON APIs live under `org.glowroot.ui` (`*JsonService` / `HttpService`). On central, live JVM/trace/weaving UI actions use gRPC **downstream** to the agent ([docs/central.md](docs/central.md), [docs/ui.md](docs/ui.md)).

### Key Entry Points

- **Agent (javaagent):** `org.glowroot.agent.MainEntryPoint`
- **Central process:** `org.glowroot.central.Main`
- **UI sandbox (dev):** `org.glowroot.agent.ui.sandbox.UiSandboxMain`

### Plugin System

Plugins use `@Pointcut` annotations and ship `META-INF/glowroot.plugin.json` (`id`, properties, aspects). Key APIs: `ThreadContext`, `TraceEntry`, `Timer`, `QueryEntry`, `MessageSupplier`. Catalog and conventions: [docs/agent-plugins.md](docs/agent-plugins.md).

### Code Generation and Quality

- **Immutables** annotation processing generates boilerplate (value objects, builders). Eclipse users need [m2e-apt](https://github.com/jbosstools/m2e-apt).
- **Checker Framework** enforces nullness. Annotations like `/*@Nullable*/` are comment-style to avoid runtime dependency; the checker build step uncomments them.
- **Error Prone** for additional static analysis
- **Animal Sniffer** enforces Java 8 API compatibility in agent modules

### Testing

- Unit tests: `*Test.java` (JUnit 5, Mockito, AssertJ)
- Integration tests: `*IT.java` (run during `verify` phase)
- The IT harness supports two modes: custom weaving classloader (convenient for IDE debugging) and spawned JVM with `-javaagent` (realistic)
- Multi-version testing via Maven profiles on plugins (e.g. `netty-4.x`, `spring-4.x`, `spring-5.1.x`, `mongodb-3.7.x`, `elasticsearch-2.x`, `play-2.x`, …)
- CI phases in `.github/scripts/build.sh`: `test1`–`test4`, `sonar`, `checker`

### Dependency Shading

All agent third-party dependencies are shaded under `org.glowroot.agent.shaded.*`. This is critical — the agent must not introduce classpath conflicts with monitored applications. The `agent/shaded/` modules handle this relocation.

### Pull requests

Non-trivial PRs need an in-repo change note (what / why / how / choices): see [CONTRIBUTING.md](CONTRIBUTING.md#pull-requests-and-change-documentation) and [docs/pr-change-template.md](docs/pr-change-template.md).
