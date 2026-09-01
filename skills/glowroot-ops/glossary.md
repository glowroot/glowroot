# Glowroot UI glossary

Explain these terms when a user seems confused — they are the #1 source of dev misunderstanding.

## Transaction

An **aggregated** view of similar requests over a time window (e.g. all `GET /api/patients` in the last hour).

- Shows: count, throughput, average/total time, error rate
- **Not** a single request — that's a **trace**

## Trace

One **single execution** of a transaction (one HTTP request, one batch job run, etc.).

- Open from Transactions list by clicking a row
- Contains: Breakdown, Entries, Queries, Service Calls, Profile, …

## Entry

One **row** in the Entries tab inside a trace — typically one instrumented method call or phase.

- Entries list may be **truncated** (max trace entries config) while Breakdown counts stay full

## Breakdown

**Timer tree** at the top of a trace — where time was spent in nested operations.

- Timers here may **not sum** to root duration — remaining time is in **Profile**
- High count in Breakdown vs few Entries rows = truncation, not a bug (`#761`)

## Profile

**Flattened** list of all instrumented methods in the trace with self/total time.

- Use when Breakdown doesn't account for all time

## Queries

SQL statements captured via JDBC plugin (`java.sql.*`).

- Empty if app uses non-JDBC data access (Vert.x SQL client, custom drivers) — plugin gap

## Service Calls

**Outbound** calls woven by HTTP/RPC plugins (RestTemplate, HttpClient, gRPC client, etc.).

- **Does not** show local Spring `@Service` / `@Component` method calls (`#746`)

## Errors

Errors logged **inside traced transactions** (via Logger plugin).

- **Not** a full log viewer
- **Not** catalina.out / stderr

## Gauges / JVM

JMX-based metrics (heap, GC, thread count, etc.).

- Heap = JMX heap used/max — not OS process memory
- No built-in "heap % of -Xmx" series (`#946` enhancement)

## Synthetic monitors

Scheduled health checks — separate from real user traffic.

## Incident / Alert

Configured thresholds on transaction metrics — advanced topic; skip in first session.
