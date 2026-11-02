# Glowroot wiki — live fetch index

Base: **https://github.com/glowroot/glowroot/wiki**

`WebFetch` only the page(s) needed. URL = base + `/` + title with spaces → hyphens.

After wiki: if topic appears in `docs-index.md`, read that in-repo note next (clone `docs/` or raw GitHub URL).

## Fetch first

| Topic | URL |
|-------|-----|
| Wiki home (index) | https://github.com/glowroot/glowroot/wiki |
| Embedded install | https://github.com/glowroot/glowroot/wiki/Agent-Installation-(with-Embedded-Collector) |
| Troubleshooting | https://github.com/glowroot/glowroot/wiki/Troubleshooting-Tips |
| What Glowroot does NOT do | https://github.com/glowroot/glowroot/wiki/What-Glowroot-does-not-do |
| Plugin coverage gaps | https://github.com/glowroot/glowroot/wiki/Plugin-coverage-gaps |

## By question type

| Question | Fetch |
|----------|-------|
| Embedded vs Central | Choosing-Embedded-vs-Central |
| Multi JVM / agent.id | Multiple-JVMs-and-agent.id |
| UI tabs / Breakdown / Queries | Transaction-tabs |
| How to read a trace | How-to-read-a-Trace |
| Slow-call playbook | Re-engineering-a-slow-call |
| What's new (operator highlights) | What's-new |
| Service Calls empty | Plugin-coverage-gaps + Plugins |
| Errors tab | Errors-tab |
| JVM / Gauges / heap | Gauges-and-JMX |
| Slow threshold / sampling | Transaction-configuration |
| Storage / retention / H2 | Administration-Storage |
| Embedded H2 1.x → 2.x upgrade | Embedded-H2-upgrade (+ `docs/embedded-h2-upgrade.md`) |
| UI port / bind | Administration-Web |
| K8s | Kubernetes |
| Central install | Central-Collector-Installation |
| Central + Docker / Compose | Central-Collector-with-Docker |
| Cassandra driver / contact points | Cassandra-Client-Configuration |
| Agent → Central | Agent-Installation-(for-Central-Collector) |
| Agent upgrade | Agent-Upgrade |
| Central upgrade | Central-Collector-Upgrade |
| HTTP `/health` / readiness | Health-endpoints (+ `docs/health-endpoints.md`) |
| 0.14.8 upgrade notes | (in-repo) → `docs/0.14.8-upgrade-notes.md` |
| HTTP API | HTTP-API-and-export |
| Custom instrumentation | Instrumentation |
| Plugins list | Plugins |
| Alerts | Alerts-and-incidents |

Full title list: fetch wiki **home** once, then the linked page.

## Local supplement (not wiki)

Issue patterns with `#NNN`: optional repo file `doc/issue-map/TRIAGE-KNOWLEDGE.md` (fork-local; may be absent).

In-repo topic notes: `docs-index.md`.

Org paths and stack: `ORG-CONTEXT.md` (from `ORG-CONTEXT.md.template`).
