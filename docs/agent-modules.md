# Agent modules (`agent/`)

Map of directories under [`agent/`](../agent/) on `main`. For the whole repo,
see [repository-layout.md](repository-layout.md). For deploy modes and the
runtime data path, see [product-map.md](product-map.md).

The agent is what you attach with `-javaagent:…/glowroot.jar`. It weaves
application bytecode, runs plugins, and either stores data **embedded** (H2 in
the same JVM) or sends it to **central** over gRPC.

**Entry point:** `org.glowroot.agent.MainEntryPoint`.

## Quick map

```text
agent/
├── api/                 glowroot-agent-api
├── plugin-api/          glowroot-agent-plugin-api
├── bytecode-api/        glowroot-agent-bytecode-api
├── core/                glowroot-agent-core-unshaded  → shaded as …-core
├── embedded/            glowroot-agent-embedded-unshaded
├── plugins/             one Maven module per plugin
├── shaded/              relocated third-party + agent JARs
├── it-harness/          IT harness (unshaded)
├── integration-tests/   agent integration tests
├── ui-sandbox/          local UI + sample traffic
├── benchmarks/          JMH
├── dist-maven-plugin/   custom Maven plugin for packaging
├── dist/                glowroot-agent → glowroot-*-dist.zip
└── vt-profile-smoke/    virtual-thread profile smoke (not in parent reactor)
```

## Unshaded vs shaded

Several modules build an **unshaded** artifact first (suffix `-unshaded` in the
artifact id). Matching modules under `agent/shaded/` relocate third-party
packages to `org.glowroot.agent.shaded.*` so the agent does not collide with
the monitored application’s classpath.

| Unshaded (sources) | Shaded packaging (typical) |
|--------------------|----------------------------|
| `agent/core` → `glowroot-agent-core-unshaded` | `agent/shaded/core` → `glowroot-agent-core` |
| `agent/embedded` → `…-embedded-unshaded` | `agent/shaded/embedded` |
| `agent/it-harness` → `…-it-harness-unshaded` | `agent/shaded/it-harness` |

When debugging classpath issues in a real app, assume the **shaded** JARs are
what ship in the dist zip — not the unshaded reactor outputs.

## API layer

| Directory | Artifact | Role |
|-----------|----------|------|
| `api/` | `glowroot-agent-api` | Public agent API surface used outside plugin pointcuts |
| `plugin-api/` | `glowroot-agent-plugin-api` | API plugins compile against (`ThreadContext`, `TraceEntry`, timers, …) |
| `bytecode-api/` | `glowroot-agent-bytecode-api` | Bytecode / weaving-facing API used with plugin-api |

Plugin authors normally depend on **plugin-api** (and follow existing plugins).
See the wiki: [Plugin and Pointcut basics](https://github.com/glowroot/glowroot/wiki/Plugin-and-Pointcut-basics).

## `core/`

**Artifact:** `glowroot-agent-core-unshaded` (then shaded).

Runtime heart of the agent: weaving (ASM), advice application, trace and
aggregate collection, live JVM services, config loading, init.

Indicative packages under `org.glowroot.agent` on `main`:

| Package area | Concern |
|--------------|---------|
| `weaving` | Class file transformation |
| `impl` | Agent implementation services |
| `config` | Config model / load |
| `collector` | Hand-off of captured data (embedded or central) |
| `live` | Live trace / JVM views for the UI |
| `init` | Startup sequencing |
| `model`, `util`, `jul` | Supporting types and logging bridge |

Depends on `plugin-api`, `bytecode-api`, and `wire-api` (for central wire types).

## `embedded/`

**Artifact:** `glowroot-agent-embedded-unshaded`.

Embedded **collector**: local **H2** persistence plus serving the shared UI
assets inside the app JVM. Used when no central collector address is
configured (default quick-start mode).

Depends on `core` (unshaded) and `ui`.

## `plugins/`

One Maven module per instrumentation plugin (`META-INF/glowroot.plugin.json` +
aspects). Full catalog, packaging rules, and common dependency edges:

→ **[agent-plugins.md](agent-plugins.md)**

## `shaded/`

Packaging modules (not feature logic). Besides shaded `core` / `embedded` /
`it-harness`:

| Directory | Role |
|-----------|------|
| `central-https-linux`, `central-https-windows`, `central-https-osx` | Native / platform bits for HTTPS to central (packaged into the agent dist) |
| `logging-logstash` | Optional Logstash-oriented logging shade |

## Test and sandbox modules

| Directory | Artifact | Role |
|-----------|----------|------|
| `it-harness/` | `glowroot-agent-it-harness-unshaded` | Harness to run sample app code and assert captured data; supports custom weaving classloader **or** spawned JVM with `-javaagent` |
| `integration-tests/` | `glowroot-agent-integration-tests` | Agent IT suite using the harness |
| `ui-sandbox/` | `glowroot-agent-ui-sandbox` | Starts Glowroot + generates sample traces for UI development (`UiSandboxMain`) |
| `benchmarks/` | `glowroot-agent-benchmarks` | JMH microbenchmarks (see `CONTRIBUTING.md`) |
| `vt-profile-smoke/` | `vt-profile-smoke` | Small Jetty + virtual-threads smoke app for profiling checks; **present on `main` but not listed** in the parent reactor `<modules>` |

## Distribution

| Directory | Artifact | Role |
|-----------|----------|------|
| `dist-maven-plugin/` | `glowroot-agent-dist-maven-plugin` | Custom Maven plugin used to assemble the agent distribution |
| `dist/` | `glowroot-agent` | Produces the release **`glowroot-*-dist.zip`** (the artifact operators download) |

Do not point production at partial reactor JARs under `agent/shaded/*` alone —
use the dist zip from [GitHub Releases](https://github.com/glowroot/glowroot/releases)
(same guidance as the root README).

## Dependency sketch (simplified)

```text
plugin-api
    ↑
bytecode-api
    ↑
core-unshaded  →  shaded/core
    ↑
embedded-unshaded  →  shaded/embedded   (+ ui)
    ↑
dist  (glowroot.jar + plugins + natives)
```

Plugins compile against `plugin-api` and are loaded by the agent at runtime.
Central mode: `core` talks gRPC using `wire-api` types to `glowroot-central`
(outside this folder).

## What this page omits

- Pointcut tutorials and per-plugin option docs (wiki + each plugin’s sources).
- Central collector internals (`central/` — separate page later).
- Fork-only notes under personal `doc/` trees.
