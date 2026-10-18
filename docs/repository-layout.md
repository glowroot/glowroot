# Repository layout (top level)

What each **top-level directory** on `main` is for. This is the tree you see at
the root of [glowroot/glowroot](https://github.com/glowroot/glowroot) — not a
full tour of every Maven submodule (those are listed in the root `pom.xml`).

For deploy modes and the runtime data path, see [product-map.md](product-map.md).

## Quick map

```text
glowroot/
├── agent/              Agent, plugins, embedded collector, dist zip
├── central/            Central collector (gRPC + Cassandra + UI serve)
├── wire-api/           Protobuf / gRPC contracts (agent ↔ central)
├── common/             Shared libraries (used by agent + central + UI)
├── common2/            Newer shared layer on top of common
├── ui/                 AngularJS SPA sources + Grunt build
├── webdriver-tests/    Selenium browser tests
├── docs/               In-repo documentation (this tree)
├── build/              Build helpers (licenses, multi-lib tests, …)
├── .github/            CI workflows and scripts
├── skills/             Optional AI operator skill (glowroot-ops)
└── pom.xml             Parent Maven reactor
```

## Maven reactor vs folders

The root `pom.xml` is a **multi-module reactor**. Many modules live *under*
`agent/` (e.g. `agent/core`, `agent/plugins/jdbc-plugin`) and are listed
individually in that POM. Top-level folders that are **not** themselves a
single Maven module still matter for contributors (`docs/`, `.github/`,
`skills/`).

`central` and `webdriver-tests` are added by the parent POM profile
`auto-activated-java-8` (active on JDK 8+). The **central** module itself
still requires **Java 17+** to build and run (see root README). Agent code
targets Java 8 APIs (Animal Sniffer); see `CONTRIBUTING.md` for build setup.

## `agent/`

**Role:** everything that runs inside (or packages for) the monitored JVM.

| Area | Purpose |
|------|---------|
| `api`, `plugin-api`, `bytecode-api` | Stable APIs for plugins and weaving |
| `core` | Agent runtime: instrumentation, traces, config |
| `embedded` | Embedded collector + local H2 |
| `plugins/*` | One module per instrumentation plugin |
| `shaded/*` | Relocated third-party deps (`org.glowroot.agent.shaded.*`) |
| `it-harness`, `integration-tests` | Agent integration testing |
| `dist`, `dist-maven-plugin` | Builds `glowroot-*-dist.zip` |
| `ui-sandbox` | Dev process that generates sample traffic for the UI |
| `benchmarks` | JMH microbenchmarks |

**Entry:** javaagent → `org.glowroot.agent.MainEntryPoint`.

Full submodule map: **[agent-modules.md](agent-modules.md)**.

## `central/`

**Role:** standalone **glowroot-central** process.

- Ingests agent data over gRPC (`wire-api`).
- Stores and rolls up data in **Cassandra**.
- Serves the shared UI over HTTP.
- Runs alerting, synthetic monitors, schema commands (`create-schema`, …).

**Entry:** `org.glowroot.central.Main`.

Requires **Java 17+**. Depends on `wire-api`, `common`, `common2`, `ui`, and
agent API bits as declared in `central/pom.xml`.

Full module map: **[central.md](central.md)**.

## `wire-api/`

**Role:** shared **Protobuf** definitions and generated gRPC stubs for the
agent ↔ central protocol (and shared message types such as `Trace`,
`Aggregate`, `AgentConfig`).

Both agent (central mode) and `central` depend on this module. Changing a
`.proto` here is a **wire compatibility** concern — treat it like a public API.

Full proto / RPC map: **[wire-api.md](wire-api.md)**.

## `common/` and `common2/`

**Role:** shared Java libraries (config, model helpers, utilities) used across
agent, central, and UI backend code.

| Module | Artifact | Notes |
|--------|----------|--------|
| `common/` | `glowroot-common` | Base shared code; depends on `wire-api` |
| `common2/` | `glowroot-common2` | Additional shared layer; depends on `common` + `wire-api` |

Prefer putting new **cross-cutting** types here only when both agent and
central (or UI) truly need them. Agent-only code stays under `agent/`;
central-only under `central/`.

## `ui/`

**Role:** source for the **AngularJS 1.x** single-page app.

- Built with Node / Bower / Grunt (via `frontend-maven-plugin` on Maven build).
- Output assets are packaged into the embedded agent UI and into central.
- Same SPA for both deploy modes; pages that are central-only are gated in the
  UI (`layout.central` and friends).

Dev: see `CONTRIBUTING.md` (`./grunt serve`, UI sandbox).

Full UI map: **[ui.md](ui.md)**.

## `webdriver-tests/`

**Role:** **Selenium** browser integration tests against a running Glowroot UI
(embedded and/or central setups as the tests configure).

Depends on `glowroot-central` and Selenium. Runs in CI as part of the broader
test matrix (see `.github/workflows`).

## `docs/`

**Role:** documentation that ships **in the git tree** (maps, upgrade notes,
topic pages). Operator install guides mostly stay on the
[wiki](https://github.com/glowroot/glowroot/wiki).

Index: [README.md](README.md).

## `build/`

**Role:** build-time helpers, not product runtime.

Typical contents on `main`:

| Path | Purpose |
|------|---------|
| `license-bundle` | License aggregation for distributions |
| `multi-lib-tests` | Tests involving multiple library versions |
| `checker-stubs` | Stubs for Checker Framework analysis |
| `scripts` | Assorted build scripts |

Wired from the parent POM where listed as `<module>`.

## `.github/`

**Role:** GitHub Actions and automation.

| Area | Purpose |
|------|---------|
| `workflows/` | `build.yml`, `reusable-build.yml`, `release.yml`, bots |
| `scripts/` | CI build phases (unit/IT/checker, etc.) |

Pull requests are validated by these workflows — see `CONTRIBUTING.md`.

## `skills/`

**Role:** optional **AI assistant** skill pack (`glowroot-ops`) for operators /
contributors using compatible tooling. Not on the agent or central runtime
classpath.

See the root README “Documentation” table for the pointer.

## Other root files (not directories)

| File | Role |
|------|------|
| `pom.xml` | Parent reactor and dependency management |
| `README.md` | Product intro, quick start, doc links |
| `CONTRIBUTING.md` | Build, UI, tests, contributor conventions |
| `LICENSE` | Apache License 2.0 |

## What to document next

Natural follow-ups under `docs/` (same style: as-on-`main` only):

1. ~~Agent submodule map~~ → [agent-modules.md](agent-modules.md)
2. ~~Central package / service map~~ → [central.md](central.md)
3. ~~Agent plugins catalog~~ → [agent-plugins.md](agent-plugins.md)
4. ~~Wire-api message / service index~~ → [wire-api.md](wire-api.md)
5. ~~UI package / page map~~ → [ui.md](ui.md)

This page stays the **top-level** index; avoid duplicating those details here.
