# Glowroot wiki — live fetch index

Base: **https://github.com/glowroot/glowroot/wiki**

`WebFetch` only the page(s) needed. URL = base + `/` + title with spaces → hyphens.

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
| Service Calls empty | Plugin-coverage-gaps + Plugins |
| Errors tab | Errors-tab |
| JVM / Gauges / heap | Gauges-and-JMX |
| Slow threshold / sampling | Transaction-configuration |
| Storage / retention / H2 | Administration-Storage |
| UI port / bind | Administration-Web |
| K8s | Kubernetes |
| Central install | Central-Collector-Installation |
| Agent → Central | Agent-Installation-(for-Central-Collector) |
| HTTP API | HTTP-API-and-export |
| Custom instrumentation | Instrumentation |
| Plugins list | Plugins |
| Alerts | Alerts-and-incidents |

Full title list: fetch wiki **home** once, then the linked page.

## Local supplement (not wiki)

Issue patterns with `#NNN`: optional repo file `doc/issue-map/TRIAGE-KNOWLEDGE.md`.

Org paths and stack: `ORG-CONTEXT.md` (from `ORG-CONTEXT.md.template`).
