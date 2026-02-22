# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Glowroot is a Java APM (Application Performance Monitoring) tool. It has two deployment modes:
- **Embedded**: Agent runs with the monitored application, stores data locally (H2 database)
- **Central**: Agents send data via gRPC/Protobuf to a central collector backed by Cassandra

## Build Commands

Building requires Java 11+ and Maven 3.8.0+. The agent targets Java 8+, the central module requires Java 17+.

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

## UI Development

```bash
cd ui
npm install
./grunt serve          # Dev server on :9000, proxies API to :4000
./grunt serve:demo     # Proxies API to demo.glowroot.org instead
```

For UI sandbox (generates sample trace data): run `org.glowroot.ui.sandbox.UiSandboxMain` in your IDE, then browse to http://localhost:4000.

## Architecture

### Module Structure

- **`agent/api`**, **`agent/plugin-api`**, **`agent/bytecode-api`** - Public APIs for agent and plugin development
- **`agent/core`** - Agent implementation: bytecode weaving (ASM), trace collection, config management
- **`agent/embedded`** - Embedded collector (all-in-one mode with local H2 storage)
- **`agent/plugins/*`** - 30+ instrumentation plugins (Servlet, Spring, JDBC, Cassandra, etc.)
- **`agent/shaded/*`** - Shaded JARs: all agent dependencies relocated under `org.glowroot.agent.shaded` to avoid classpath conflicts with monitored apps
- **`agent/it-harness`** - Integration test harness (supports custom classloader or real `-javaagent` mode)
- **`agent/dist`** - Distribution packaging (produces `glowroot-*-dist.zip`)
- **`central/`** - Central collector: gRPC server, Cassandra persistence, metric rollups, UI serving (Java 17+)
- **`common/`**, **`common2/`** - Shared config, model, and utility code
- **`wire-api/`** - Protobuf definitions (Trace, Aggregate, AgentConfig, CollectorService, etc.)
- **`ui/`** - AngularJS 1.x SPA with Grunt build pipeline (SCSS, concat, minify, asset revving)
- **`webdriver-tests/`** - Selenium browser integration tests

### Data Flow

Agent (bytecode weaving captures traces/metrics) → gRPC/Protobuf → Central (Cassandra storage + aggregation) → HTTP REST API → AngularJS UI

### Key Entry Points

- **Agent**: `agent/core/src/main/java/org/glowroot/agent/MainEntryPoint.java`
- **Central**: `central/src/main/java/org/glowroot/central/Main.java`

### Plugin System

Plugins use `@Pointcut` annotations to intercept method calls. Key APIs: `ThreadContext`, `TraceEntry`, `Timer`, `QueryEntry`, `MessageSupplier`. See existing plugins in `agent/plugins/` for examples.

### Code Generation and Quality

- **Immutables** annotation processing generates boilerplate (value objects, builders). Eclipse users need [m2e-apt](https://github.com/jbosstools/m2e-apt).
- **Checker Framework** enforces nullness. Annotations like `/*@Nullable*/` are comment-style to avoid runtime dependency; the checker build step uncomments them.
- **Error Prone** for additional static analysis
- **Animal Sniffer** enforces Java 8 API compatibility in agent modules

### Testing

- Unit tests: `*Test.java` (JUnit 5, Mockito, AssertJ)
- Integration tests: `*IT.java` (run during `verify` phase)
- The IT harness supports two modes: custom weaving classloader (convenient for IDE debugging) and spawned JVM with `-javaagent` (realistic)
- Multi-version testing via Maven profiles: `netty-4.x`, `spring-4.x`, `spring-5.1.x`, `mongodb-3.7.x`, `elasticsearch-2.x`, `play-2.x`, etc.
- CI test phases are defined in `.github/scripts/build.sh` (test1 through test4, sonar, checker)

### Dependency Shading

All agent third-party dependencies are shaded under `org.glowroot.agent.shaded.*`. This is critical — the agent must not introduce classpath conflicts with monitored applications. The `agent/shaded/` modules handle this relocation.
