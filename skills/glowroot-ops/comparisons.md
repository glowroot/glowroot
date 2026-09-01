# Glowroot vs alternatives — one-liners

Use when devs ask "why Glowroot and not X?" Position honestly — Glowroot is not for everyone.

## vs logs only

**Logs** tell you *what* was printed, when you thought to log it. **Glowroot** shows per-request structure: which SQL ran, how long each layer took, without adding log lines. It does **not** replace logs — retention is limited and not every event is captured.

## vs Pinpoint (Java OSS APM)

**Pinpoint** is a fuller Java APM with more distributed-tracing features and a heavier footprint. **Glowroot** is lighter: one JAR, embedded UI, faster time-to-value for single-JVM or small deployments. Choose Pinpoint when you need deeper topology across many services out of the box.

## vs Apache SkyWalking

**SkyWalking** is polyglot, OTel-aligned, ecosystem-heavy — great for cloud-native multi-language stacks. **Glowroot** is **Java-only**, simpler to attach (`-javaagent`), ideal when the team is Java/Tomcat/Spring and wants minimal ops overhead.

## vs Datadog / New Relic / SaaS APM

**SaaS APM** = managed, rich features, cost scales with hosts. **Glowroot** = self-hosted, Apache 2.0, no per-host license — you own the data and the ops burden. Not comparable on feature breadth; comparable on "I need traces on my Java app without a platform team."

## vs OpenTelemetry (DIY)

**OTel** is the standard — assemble collectors, backends, dashboards yourself. **Glowroot** is batteries-included for Java: agent + UI + storage in one package. OTel wins long-term for polyglot + vendor-neutral; Glowroot wins for "working in 5 minutes on one Tomcat."

## When to say "not Glowroot"

- Need full distributed tracing across 50 microservices with one trace ID
- Need browser RUM
- Need Prometheus exporter without custom work
- Non-Java workloads
- Team wants zero on-prem footprint (SaaS only)
