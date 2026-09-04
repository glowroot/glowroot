# Glowroot

[![Latest release](https://img.shields.io/github/v/release/glowroot/glowroot?display_name=tag)](https://github.com/glowroot/glowroot/releases/latest)
[![Build](https://github.com/glowroot/glowroot/actions/workflows/build.yml/badge.svg)](https://github.com/glowroot/glowroot/actions/workflows/build.yml)
[![License](https://img.shields.io/github/license/glowroot/glowroot)](https://github.com/glowroot/glowroot/blob/main/LICENSE)
[![Java](https://img.shields.io/badge/Java-8%2B%20agent%20%7C%2017%2B%20central-blue)](https://github.com/glowroot/glowroot#requirements)  
[![GitHub stars](https://img.shields.io/github/stars/glowroot/glowroot)](https://github.com/glowroot/glowroot/stargazers)
[![GitHub forks](https://img.shields.io/github/forks/glowroot/glowroot)](https://github.com/glowroot/glowroot/network/members)
[![Contributors](https://img.shields.io/github/contributors/glowroot/glowroot)](https://github.com/glowroot/glowroot/graphs/contributors)
[![Discussions](https://img.shields.io/github/discussions/glowroot/glowroot)](https://github.com/glowroot/glowroot/discussions)

**Easy to use, very low overhead, open source Java APM.**

- **(Very) easy to use** — drop in a `-javaagent`, open the UI, see traces and profiles
- **Very (very) low overhead** — tuned with extensive microbenchmarking
- **Embedded or central** — local H2 collector out of the box, or scale out with a central collector

**Website:** [glowroot.org](https://glowroot.org/) ·
**Features:** [glowroot.org/features](https://glowroot.org/features.html) ·
**Releases:** [GitHub Releases](https://github.com/glowroot/glowroot/releases) ·
**UI preview:** [archived demo snapshot](https://web.archive.org/web/20230325052222/https://demo.glowroot.org/)
(the live `demo.glowroot.org` host is currently unreachable — see [#1102](https://github.com/glowroot/glowroot/issues/1102))

## Features

- Trace capture for slow requests and errors
- Continuous profiling (with filtering) and flame graphs
- Response time breakdown and percentile charts
- SQL and service-call capture with aggregation
- MBean attribute capture and charts
- Configurable alerting and historical rollups
- Full support for async requests that span multiple threads
- Responsive UI (including mobile)
- Optional central collector

See the full list and instrumentation coverage on the [features](https://glowroot.org/features.html) and [instrumentation](https://glowroot.org/instrumentation.html) pages.

## Requirements

* Java 8+ for the agent
* Java 17+ for glowroot-central

## Quick start (embedded)

1. Download and unzip the latest **stable** agent distribution from [GitHub Releases](https://github.com/glowroot/glowroot/releases/latest) (`glowroot-*-dist.zip`)
2. Add `-javaagent:path/to/glowroot.jar` to your application's JVM args — **before** `-jar` if you use an executable JAR
   ([where are my application server's JVM args?](https://github.com/glowroot/glowroot/wiki/Where-are-my-application-server's-JVM-args%3F))
3. Ensure the user that runs the JVM can write to the glowroot folder
4. Start your application
5. Open http://localhost:4000 (wait for a **UI listening on …** line in the agent log)

Post-install details:
[Agent Installation (Embedded Collector)](https://github.com/glowroot/glowroot/wiki/Agent-Installation-(with-Embedded-Collector)).

**Central collector?** Start with [Agent Installation (for Central Collector)](https://github.com/glowroot/glowroot/wiki/Agent-Installation-(for-Central-Collector)) (agents on app JVMs, glowroot-central + Cassandra on a monitor host).

## Deployment

| Mode | What you run | Typical use |
|------|----------------|-------------|
| **Embedded** (default) | `-javaagent` + local H2 + UI in the **same JVM** as your app | Single app, dev, small installs |
| **Central** | `-javaagent` on each app JVM → **glowroot-central** (+ Cassandra) on a monitor host | Many agents, shared UI, longer retention |

Central needs its own install — see [Agent Installation (for Central Collector)](https://github.com/glowroot/glowroot/wiki/Agent-Installation-(for-Central-Collector)) and [Central Collector with Docker](https://github.com/glowroot/glowroot/wiki/Central-Collector-with-Docker). Embedded JSON/H2 storage is **not** a substitute for Central.

## Documentation

| Topic | Wiki |
|-------|------|
| Wiki home | [github.com/glowroot/glowroot/wiki](https://github.com/glowroot/glowroot/wiki) |
| Embedded vs Central | [Choosing Embedded vs Central](https://github.com/glowroot/glowroot/wiki/Choosing-Embedded-vs-Central) |
| Embedded install | [Agent Installation (with Embedded Collector)](https://github.com/glowroot/glowroot/wiki/Agent-Installation-(with-Embedded-Collector)) |
| Central install | [Agent Installation (for Central Collector)](https://github.com/glowroot/glowroot/wiki/Agent-Installation-(for-Central-Collector)) |
| Central on Docker | [Central Collector with Docker](https://github.com/glowroot/glowroot/wiki/Central-Collector-with-Docker) |
| Troubleshooting | [Troubleshooting Tips](https://github.com/glowroot/glowroot/wiki/Troubleshooting-Tips) |
| JBoss / WildFly `GeneratedMethodMeta` CNFE | See FAQ below (`jboss.modules.system.pkgs`) · [#1112](https://github.com/glowroot/glowroot/issues/1112) |
| Storage (H2 / Cassandra TTL) | [Administration-Storage](https://github.com/glowroot/glowroot/wiki/Administration-Storage) |
| Plugins / custom Instrumentation | [Plugins](https://github.com/glowroot/glowroot/wiki/Plugins) · [Instrumentation](https://github.com/glowroot/glowroot/wiki/Instrumentation) |
| Empty Queries / Service Calls / Web? | [Plugin coverage gaps](https://github.com/glowroot/glowroot/wiki/Plugin-coverage-gaps) · [What Glowroot does not do](https://github.com/glowroot/glowroot/wiki/What-Glowroot-does-not-do) |
| UI orientation (tabs, alerts, gauges, …) | [Transaction tabs](https://github.com/glowroot/glowroot/wiki/Transaction-tabs) · [wiki UI / configuration](https://github.com/glowroot/glowroot/wiki#ui--configuration) |
| Contributing (modules / engine map) | [For contributors](https://github.com/glowroot/glowroot/wiki/For-contributors) · [Plugin and Pointcut basics](https://github.com/glowroot/glowroot/wiki/Plugin-and-Pointcut-basics) |
| Community Q&A | [GitHub Discussions](https://github.com/glowroot/glowroot/discussions) |
| AI assistant (`glowroot-ops`) | [skills/glowroot-ops/](skills/glowroot-ops/) |

## FAQ

**Where should I download Glowroot?**  
Use the `glowroot-*-dist.zip` from [GitHub Releases](https://github.com/glowroot/glowroot/releases). Do not point production at random JARs from a partial Maven build (`agent/shaded/*` is not the distribution).

**Which Java versions are supported?**  
Java **8+** for the agent. Java **17+** for glowroot-central. There is no maintained agent for Java 6/7.

**Spring Boot 3 / Jakarta EE (`jakarta.servlet`)?**  
Use a current **0.14.x** agent from Releases.

**The UI does not open / I never see “UI listening”.**  
Confirm `-javaagent` is on the JVM that runs your app, the glowroot directory is writable, and nothing else is bound to port 4000. With embedded mode the UI starts after application startup — if the JVM exits immediately, the UI never binds. Bind/port details: [Administration-Web](https://github.com/glowroot/glowroot/wiki/Administration-Web).

**Several JVMs on one host.**  
Embedded: give each JVM its own `data.dir` (or use Central). Central: use a **unique `agent.id` per JVM**; you can group agents in the UI with rollup IDs (for example `App::pod` in Kubernetes). See [Multiple JVMs and agent.id](https://github.com/glowroot/glowroot/wiki/Multiple-JVMs-and-agent.id) and [Agents and rollups](https://github.com/glowroot/glowroot/wiki/Agents-and-rollups).

**Agent cannot connect to Central.**  
Set `collector.address` to the Central host and **gRPC port (8181 by default)** — not the UI port (4000). Use `host:port` only (no URL path). Check firewall/`nc` from the agent host. See the [Central install wiki](https://github.com/glowroot/glowroot/wiki/Agent-Installation-(for-Central-Collector)) and [Troubleshooting Tips](https://github.com/glowroot/glowroot/wiki/Troubleshooting-Tips).

**H2 “locked by another process”.**  
Only one embedded collector may use a given data directory. Stop the other JVM or point `data.dir` / `multi.dir` at separate folders.

**Few traces / empty Queries tab?**  
Aggregates cover all traffic; **Traces** only store requests above the slow threshold ([Transaction configuration](https://github.com/glowroot/glowroot/wiki/Transaction-configuration)). **Queries** come from the JDBC plugin, not custom Instrumentation ([Plugins](https://github.com/glowroot/glowroot/wiki/Plugins) · [Transaction tabs](https://github.com/glowroot/glowroot/wiki/Transaction-tabs)).

**Can I run Glowroot together with another Java APM agent?**  
Usually **no** — two bytecode-weaving agents on the same JVM is fragile. Pick one agent per process.

**JBoss / WildFly: `ClassNotFoundException: GeneratedMethodMeta*` (or `NoClassDefFoundError`)?**  
JBoss Modules isolation: woven app classes get a `MetaHolder` in the deployment `ModuleClassLoader`, but `GeneratedMethodMeta*` / related weaving classes live in the agent/bootstrap loader. Mark Glowroot as a system package so deployments can see those classes:

```bash
-Djboss.modules.system.pkgs=org.glowroot
```

If the property is already set (e.g. for Byteman), **append** `org.glowroot` to the comma-separated list. Keep using only `-javaagent:/path/to/glowroot.jar`. Do **not** ship `glowroot.jar` inside the EAR once `system.pkgs` is set. See [#1112](https://github.com/glowroot/glowroot/issues/1112).

## Support

Pick the channel that fits — it helps everyone respond faster:

| Need | Where |
|------|--------|
| Questions, how-to, configuration doubts, “is this expected?” | **[GitHub Discussions → Q&A](https://github.com/glowroot/glowroot/discussions/categories/q-a)** |
| Ideas, proposals, “would it make sense to…?” | **[GitHub Discussions → Ideas](https://github.com/glowroot/glowroot/discussions/categories/ideas)** |
| Reproducible bugs (with steps, version, logs) | **[GitHub Issues](https://github.com/glowroot/glowroot/issues)** |
| Release announcements / community | [Google Group](https://groups.google.com/forum/#!forum/glowroot) · [@glowroot](https://twitter.com/glowroot) |

**Discussions** (Q&A or Ideas) are usually the best fit for questions and feature ideas. **Issues** work best when you can share Glowroot version, deployment mode (embedded or central), JDK version, and steps to reproduce — that keeps the tracker useful for real defects.

## AI assistant (`glowroot-ops`)

Portable skill pack for AI tools (Cursor, Claude Code, Copilot, ChatGPT, and others). It helps with install, UI, traces, and JVM questions by **fetching the [live wiki](https://github.com/glowroot/glowroot/wiki)** instead of guessing from model memory.

| What it does | What it does not do |
|--------------|---------------------|
| Classify your question (how-to, expected behavior, bug, idea) | Open GitHub Issues for you |
| Point to the right wiki page | Change configs or JVM args |
| Suggest Discussion vs Issue when unsure | Replace reading the docs |

### Install

Paste into any coding agent:

```text
Install the /glowroot-ops skill globally from https://github.com/glowroot/glowroot/tree/main/skills/glowroot-ops
```

From a local clone of this repo:

```sh
npx skills add ./skills/glowroot-ops --skill glowroot-ops --global --yes --copy
```

Windows: `.\skills\glowroot-ops\install.ps1` · macOS/Linux: `./skills/glowroot-ops/install.sh`

### Customize

Copy [`skills/glowroot-ops/ORG-CONTEXT.md.template`](skills/glowroot-ops/ORG-CONTEXT.md.template) to `ORG-CONTEXT.md` and fill in your install paths, JDK, and app server. The skill uses that file for org-specific advice; the template ships without any vendor data.

### Use

| Tool | How |
|------|-----|
| **Cursor** | `/glowroot-ops <your question>` |
| **Claude Code** | invoke skill `glowroot-ops` |
| **Any chat** | paste [`PROMPT.md`](skills/glowroot-ops/PROMPT.md) + your question |

Example: `/glowroot-ops Service Calls empty on @Service — is that normal?`

More detail: [`skills/glowroot-ops/README.md`](skills/glowroot-ops/README.md) · [`INSTALL.md`](skills/glowroot-ops/INSTALL.md)

## Contributing

Build instructions, UI sandbox, integration tests, and code-quality checks: **[CONTRIBUTING.md](CONTRIBUTING.md)**.

Code orientation (modules, agent data path, embedded vs central in the tree): wiki **[For contributors](https://github.com/glowroot/glowroot/wiki/For-contributors)**.

## Star History

<a href="https://star-history.dera.page/?type=date&repos=glowroot%2Fglowroot#glowroot/glowroot&legend=bottom-right">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://star-history.dera.page/svg?repos=glowroot/glowroot&theme=dark&legend=bottom-right" />
    <source media="(prefers-color-scheme: light)" srcset="https://star-history.dera.page/svg?repos=glowroot/glowroot&legend=bottom-right" />
    <img alt="Star History Chart" src="https://star-history.dera.page/svg?repos=glowroot/glowroot&legend=bottom-right" />
  </picture>
</a>

Chart served by [star-history.dera.page](https://star-history.dera.page/) — a token-free fork that uses public star snapshots (GitHub restricted the stargazers API in 2026).

## License

Glowroot source code is licensed under the Apache License, Version 2.0.

See [Third Party Software](https://github.com/glowroot/glowroot/wiki/Third-Party-Software) for license detail of third party software included in the binary distribution.
