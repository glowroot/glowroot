# Glowroot documentation (in-repo)

Operator install guides and most UI how-tos live on the
[Glowroot wiki](https://github.com/glowroot/glowroot/wiki).
This `docs/` tree holds **in-repo** maps and notes that travel with the source
on `main`.

## Start here

| Doc | Audience | What it is |
|-----|----------|------------|
| [Product map](product-map.md) | Contributors, operators who want the big picture | Deploy modes, data path, entry points — as shipped on `main` |
| [Repository layout](repository-layout.md) | Contributors | Top-level directories (`agent/`, `central/`, `ui/`, …) |
| [Agent modules](agent-modules.md) | Contributors | Map of `agent/*` (APIs, core, shaded, dist) |
| [Agent plugins](agent-plugins.md) | Contributors | `agent/plugins/*` catalog, `glowroot.plugin.json`, aspects |
| [Central](central.md) | Contributors | `central/` services, gRPC, Cassandra `repo/` |
| [Wire API](wire-api.md) | Contributors | Protobuf / gRPC contracts (`wire-api/`) |
| [UI](ui.md) | Contributors | AngularJS SPA + `org.glowroot.ui` HTTP/JSON layer |
| [Contributing](../CONTRIBUTING.md) | Contributors | Build, UI sandbox, tests, PR change-doc policy |
| [PR change template](pr-change-template.md) | Contributors | Required note for non-trivial PRs → [`changes/`](changes/) |

## Topic notes (in-repo)

| Doc | Topic |
|-----|--------|
| [HTTP health endpoints](health-endpoints.md) | `/health` vs `/readiness` (0.14.8+) |
| [0.14.8 upgrade notes](0.14.8-upgrade-notes.md) | Breaking / notable changes for that release |
| [Embedded H2 upgrade](embedded-h2-upgrade.md) | H2 1.x → 2.x data migration |
| [JVM thread “Allocated memory”](jvm-thread-stats-allocated-memory.md) | Meaning of that UI column |

## Wiki (outside this tree)

| Topic | Link |
|-------|------|
| Wiki home | [glowroot/glowroot wiki](https://github.com/glowroot/glowroot/wiki) |
| Embedded vs Central | [Choosing Embedded vs Central](https://github.com/glowroot/glowroot/wiki/Choosing-Embedded-vs-Central) |
| Embedded install | [Agent Installation (Embedded)](https://github.com/glowroot/glowroot/wiki/Agent-Installation-(with-Embedded-Collector)) |
| Central install | [Agent Installation (Central)](https://github.com/glowroot/glowroot/wiki/Agent-Installation-(for-Central-Collector)) |
| Plugins / instrumentation | [Plugins](https://github.com/glowroot/glowroot/wiki/Plugins) · [Instrumentation](https://github.com/glowroot/glowroot/wiki/Instrumentation) |
