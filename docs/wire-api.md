# Wire API (`wire-api/`)

Shared **Protobuf / gRPC** contracts between the agent and central (and shared
message types used in storage/export paths). Artifact:
`glowroot-wire-api`.

Java package: `org.glowroot.wire.api.model`  
Proto package: `org_glowroot_wire_api_model`  
Sources: [`wire-api/src/main/protobuf/`](../wire-api/src/main/protobuf/).

Parent maps: [repository-layout.md](repository-layout.md) ·
[central.md](central.md) · [agent-modules.md](agent-modules.md).

## Why this module matters

Changing a `.proto` here is a **wire compatibility** change for every agent
talking to central. Prefer additive fields, keep deprecated RPCs until agents
in the wild are gone, and treat renames/removals as breaking.

Generated stubs are produced at build time (`protoc` + `protoc-gen-grpc-java`
via `protoc-jar-maven-plugin` in `wire-api/pom.xml`).

## Files on `main`

| Proto | Kind | Role |
|-------|------|------|
| `CollectorService.proto` | gRPC **service** + messages | Agent → central ingest |
| `DownstreamService.proto` | gRPC **service** + messages | Bidirectional central ↔ agent (live tools, config push) |
| `Trace.proto` | messages | Slow/error (and related) trace payload |
| `Aggregate.proto` | messages | Transaction aggregates, queries, service calls, histograms |
| `AgentConfig.proto` | messages | Agent configuration blob (plugins, alerts, gauges, …) |
| `Profile.proto` | messages | Compact CPU-style profile trees |
| `Common.proto` | messages | Shared helpers (`Throwable`, `StackTraceElement`, optionals) |

## Direction of traffic

```text
                    CollectorService (unary / client-stream)
   Agent  ------------------------------------------>  Central
          collectInit, collectAggregateStream,
          collectGaugeValues, collectTraceStream, log

                    DownstreamService (bidi stream)
   Agent  <---------------------------------------->  Central
          connect(stream AgentResponse)
            returns (stream CentralRequest)
```

- **CollectorService** — agent pushes environment/config init, aggregates,
  gauges, traces, and agent logs.
- **DownstreamService** — long-lived bidi stream; central sends requests
  (thread dump, heap dump, reweave, live trace fetch, config update, …);
  agent answers with matching responses.

Central implementations on `main`: `CollectorServiceImpl`,
`DownstreamServiceImpl`, plus `Live*ServiceImpl` adapters for the UI (see
[central.md — gRPC surface](central.md#grpc-surface)).

## `CollectorService` RPCs

| RPC | Style | Notes |
|-----|-------|--------|
| `collectInit` | unary | First contact: `InitMessage` (agent id, environment, `AgentConfig`) → `InitResponse` (may return config + central version) |
| `collectAggregateStream` | client stream | Preferred aggregate ingest (`AggregateStreamMessage` oneof: header, shared query text, overall / transaction aggregates) |
| `collectAggregates` | unary | **Deprecated** since 0.9.5 — use stream |
| `collectGaugeValues` | unary | Gauge samples |
| `collectTraceStream` | client stream | Preferred trace ingest |
| `collectTrace` | unary | **Deprecated** since 0.9.5 — use stream |
| `log` | unary | Agent log lines to central |

`EmptyMessage` / response wrappers live alongside these in the same proto.

## `DownstreamService` RPCs

| RPC | Style | Notes |
|-----|-------|--------|
| `connect` | bidi stream | `AgentResponse` → `CentralRequest` paired by `request_id` |

`CentralRequest` / `AgentResponse` are large **oneof**s grouped roughly as:

| Group | Examples (request side) |
|-------|-------------------------|
| Session | `Hello` / `HelloAck` |
| Config | `AgentConfigUpdateRequest` |
| Live JVM | thread dump, jstack, heap dump / histogram, force GC, MBeans, system properties, capabilities, … |
| Live weaving | classpath/class/method matching, `ReweaveRequest`, global meta |
| Live traces | header / entries / queries / profiles / full trace |

Exact field numbers and message bodies are in `DownstreamService.proto` —
use that file as the checklist when adding a live UI action.

## Message protos (domain)

### `Trace.proto`

`Trace` holds id, `Header` (timing, transaction type/name, user, error,
timers, thread stats, limit flags), ordered `Entry` tree, queries, main/aux
`Profile`, shared query texts, and an `update` flag for partial-trace
updates.

Used on the collector stream and in downstream `FullTraceResponse`.

### `Aggregate.proto`

`Aggregate` rolls up totals, error counts, timer trees, thread stats,
duration histogram (raw values or HdrHistogram bytes), queries, service
calls, and profiles. Stream headers / per-transaction wrappers live in
`CollectorService.proto` (`AggregateStreamMessage`, …).

Several `Old*` types remain for wire compatibility with older agents.

### `AgentConfig.proto`

Top-level `AgentConfig`: transaction/JVM/UI defaults/advanced settings,
gauges, synthetic monitors, alerts, **plugin** configs, custom
**instrumentation** configs. Some fields are central-only (e.g. general
display, `config_read_only`).

Plugin UI properties ultimately serialize into the plugin config messages
here (ids align with `glowroot.plugin.json` — see
[agent-plugins.md](agent-plugins.md)).

### `Profile.proto`

String tables (`package_name`, `class_name`, …) + `ProfileNode` list
(pre-order, depth-based). Shared by traces and aggregates.

### `Common.proto`

`Throwable` / `StackTraceElement` and `OptionalInt32` / `OptionalInt64` /
`OptionalDouble` wrappers (proto3 has no real “optional primitive” in older
usage patterns Glowroot still relies on). Outer classname: `Proto`.

## Compatibility rules of thumb

1. **Add** fields with new numbers; never reuse field numbers.
2. Leave **deprecated** RPCs/messages until documented removal; agent and
   central versions skew in the wild.
3. Prefer **stream** variants (`collect*Stream`) for new ingest work.
4. Cross-check central `v09support` and DAO code if you change rollup or
   agent-id identity fields (see [central.md](central.md)).
5. After proto edits: full rebuild of `wire-api` and dependents (`agent/core`,
   `central`, `common*`, tests).

## What this page omits

- Field-by-field schema dump (read the `.proto` files).
- gRPC transport / TLS packaging (agent `shaded/central-https-*`, central
  Netty config).
- Cassandra table mapping (central `repo/` DAOs).
