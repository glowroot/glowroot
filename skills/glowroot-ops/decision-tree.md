# Decision tree — classify Glowroot requests

Read this first. Then wiki. Then `issue-gate.md` for verdict.

```
                    User question
                         │
         ┌───────────────┼───────────────┐
         ▼               ▼               ▼
    Trace/report    How-to / UI      Behavior /
    on disk         config           performance
         │               │               │
         ▼               ▼               ▼
  trace analysis   dev-setup.md    triage-patterns-dev.md
  + ORG-CONTEXT    + wiki          + wiki
```

## 1. How-to / config

**Signals:** "How do I…?", "UI won't open", "filter slow traces", "filter errors"

| Example | First response |
|---------|----------------|
| UI won't open | App running? `UI listening on` in log? Port/bind? → `dev-setup.md` + `ORG-CONTEXT.md` |
| Find slow traces | Transactions → sort by time → open trace → Queries / Profile |
| Filter errors | Errors tab = errors in **traced** transactions only; not container stdout |

**Verdict:** `wiki-only` or `no-issue`

## 2. Expected behavior

**Signals:** "missing X in UI", "broken", "should show Y"

| Example | First response |
|---------|----------------|
| Service Calls empty on `@Service` | **Expected** — outbound HTTP/RPC only, not local beans. `#746` `#812` |
| Queries tab empty | **Expected** if not `java.sql.*` — plugin gap |
| Breakdown timers ≠ root total | **Expected** — see Profile tab |
| Breakdown count ≫ Entries rows | **Expected** — UI truncates entries, counts stay full |
| Errors empty but logs full | **Expected** — Errors ≠ server log file |

**Verdict:** `expected-behavior` + wiki Plugin coverage gaps / Transaction tabs

## 3. Performance / JVM

**Signals:** heap, GC, CPU, percentiles, report accuracy, JVM vs OS memory

| Example | First response |
|---------|----------------|
| How does heap work? | JMX heap used/max via Gauges; no built-in % of -Xmx (`#946`) |
| p99 vs p95? | Check wiki Transaction tabs for aggregate type |
| CPU spike after upgrade | Version path, embedded vs central, H2 upgrade `#1180` |
| JVM RAM vs OS | JMX heap ≠ process RSS |

**Verdict:** `wiki-only` or `discussion`

## 4. Suspected bug

**Signals:** regression, NPE, "value disappeared", repro steps

**Verdict:** `needs-repro` or `possible-bug` only with full `issue-gate.md` checklist

## 5. Idea / enhancement

**Signals:** UI wish, Prometheus exporter, unsaved-config indicator

**Verdict:** `discussion` (Ideas) — not Issue

## 6. Trace / export analysis

1. Orient: Transactions → trace → Queries / Profile
2. Deep forensics: dedicated trace-analysis tool if available; else read export in context
3. Do not invent SQL rankings without the files

## 7. Instrumentation curiosity

**Signals:** how metrics are captured, nested servlets, threads, improving code for Glowroot

Point to wiki Plugins, Instrumentation, Plugin and Pointcut basics.

**Verdict:** `wiki-only`

## 8. Uncertain

> Not enough for a verdict. Open a **GitHub Discussion** (Q&A) with `issue-gate.md` checklist — not an Issue.

## Mode selection

Use `{{DEFAULT_MODE}}` from `ORG-CONTEXT.md`. Default rule:

| Use case | Mode |
|----------|------|
| Local dev | Embedded |
| Single-app prod | Embedded |
| Multi-agent dashboard | Central |
