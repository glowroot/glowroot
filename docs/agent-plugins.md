# Agent plugins (`agent/plugins/`)

Instrumentation plugins on `main`. Parent map: [agent-modules.md](agent-modules.md).
Operator coverage / empty-tab FAQ:
[Plugin coverage gaps](https://github.com/glowroot/glowroot/wiki/Plugin-coverage-gaps)
· [Plugins](https://github.com/glowroot/glowroot/wiki/Plugins)
· [Plugin and Pointcut basics](https://github.com/glowroot/glowroot/wiki/Plugin-and-Pointcut-basics).

## How a plugin is packaged

Each plugin is its own Maven module under `agent/plugins/<name>-plugin/`.

| Piece | Location | Role |
|-------|----------|------|
| Descriptor | `src/main/resources/META-INF/glowroot.plugin.json` | `id`, display `name`, config `properties`, list of `aspects` |
| Aspects | `src/main/java/org/glowroot/agent/plugin…` | Classes listed in `aspects` — contain `@Pointcut` advice |
| API dependency | `glowroot-agent-plugin-api` | Compile-time API (`ThreadContext`, `TraceEntry`, timers, query entries, …) |
| Artifact id | `glowroot-agent-<id-ish>-plugin` | e.g. `jdbc-plugin` → `glowroot-agent-jdbc-plugin` |

The agent loads plugin JARs from the distribution and registers aspects from
each `glowroot.plugin.json`. **Plugin config** in the UI is driven by the
`properties` array in that JSON (types: boolean, double, list, …).

To see what a plugin actually instruments: open its
`META-INF/glowroot.plugin.json` → `aspects` → those Java classes.

Some plugins ship **no `aspects` array** (or empty) and rely on other
registration patterns in-tree — still treat `glowroot.plugin.json` as the
source of truth for `id` / `name` / properties.

## Conventions

- **One concern per module** — do not fold unrelated libraries into one plugin.
- **Servlet split:** `servlet` = `javax.servlet`; `jakartaservlet` = `jakarta.servlet` (Spring Boot 3 / Jakarta EE).
- **Executor plugin** often appears as a dependency of web plugins so async work keeps the trace context.
- Prefer existing plugins as templates over inventing new API surface in `plugin-api`.

## Catalog (as on `main`)

`id` is the value from `glowroot.plugin.json` (used in config / UI). Directory
is the Maven folder name.

### Web / HTTP servers & frameworks

| Directory | id | Name | Notes |
|-----------|-----|------|--------|
| `servlet-plugin` | `servlet` | Servlet Plugin | `javax.servlet` request traces; often depended on by other web plugins |
| `jakarta-servlet-plugin` | `jakartaservlet` | Jakarta Servlet Plugin | `jakarta.servlet` counterpart |
| `java-http-server-plugin` | `java-http-server` | Java HTTP Server Plugin | `com.sun.net.httpserver` |
| `spring-plugin` | `spring` | Spring Plugin | Controllers; depends on servlet + executor plugins |
| `jaxrs-plugin` | `jaxrs` | JAX-RS Plugin | Resource methods |
| `jaxws-plugin` | `jaxws` | JAX-WS Plugin | Web services |
| `jsf-plugin` | `jsf` | JSF Plugin | JavaServer Faces |
| `jsp-plugin` | `jsp` | JSP Plugin | JSP pages |
| `struts-plugin` | `struts` | Struts Plugin | Struts actions |
| `grails-plugin` | `grails` | Grails Plugin | Grails controllers |
| `play-plugin` | `play` | Play Plugin | Play 1.x / 2.x aspects |
| `netty-plugin` | `netty` | Netty Plugin | Netty 3 / 4-style aspects |
| `camel-plugin` | `camel` | Camel Plugin | Apache Camel |

### Data access & HTTP clients

| Directory | id | Name | Notes |
|-----------|-----|------|--------|
| `jdbc-plugin` | `jdbc` | Jdbc Plugin | Statements, ResultSet, connections, pool leak options (many UI properties) |
| `hibernate-plugin` | `hibernate` | Hibernate Plugin | ORM |
| `cassandra-plugin` | `cassandra` | Cassandra Plugin | DataStax-style session / result aspects |
| `mongodb-plugin` | `mongodb` | MongoDB Plugin | Collection / iterable aspects |
| `redis-plugin` | `redis` | Redis Plugin | Redis clients |
| `elasticsearch-plugin` | `elasticsearch` | Elasticsearch Plugin | Request / future aspects |
| `http-client-plugin` | `http-client` | HTTP Client Plugin | Outbound HTTP (Apache HttpClient 4/5 and related aspects) |

### Messaging

| Directory | id | Name | Notes |
|-----------|-----|------|--------|
| `jms-plugin` | `jms` | JMS Plugin | JMS |
| `kafka-plugin` | `kafka` | Kafka Plugin | Producer (and related) aspects |

### Concurrency, logging, other

| Directory | id | Name | Notes |
|-----------|-----|------|--------|
| `executor-plugin` | `executor` | Executor Plugin | `Executor` / thread-pool context propagation — critical for async traces |
| `logger-plugin` | `logger` | Logger Plugin | Logback / Log4j / JUL-style aspects (error → trace linkage) |
| `quartz-plugin` | `quartz` | Quartz Plugin | Schedulers |
| `ejb-plugin` | `ejb` | EJB Plugin | EJB |
| `mail-plugin` | `mail` | Mail Plugin | JavaMail-style send |

## Dependency edges (common)

These are **Maven** dependencies between plugin modules (not an exhaustive
graph). Useful when a transaction type or async hand-off seems “lost”:

```text
spring-plugin
  → servlet-plugin
  → executor-plugin

servlet-plugin
  → executor-plugin
```

If you add a new web framework plugin that starts transactions on a worker
thread, check whether you need `executor-plugin` the same way.

## Config surface

Example: JDBC exposes bind-parameter include/exclude lists, ResultSet timing
flags, connection-pool leak capture, stack-trace thresholds, etc. — all
declared in
`agent/plugins/jdbc-plugin/src/main/resources/META-INF/glowroot.plugin.json`.

When documenting or reviewing a plugin change:

1. Update **aspects** / pointcuts in Java.
2. Keep **`glowroot.plugin.json`** in sync (`aspects` list, new `properties`).
3. Add / adjust ITs under that plugin module (harness patterns match siblings).

## What this page omits

- Per-property operator prose already shown in the UI (comes from the JSON
  `label` / `description` fields).
- Custom Instrumentation (UI) — not a `agent/plugins/*` module; see wiki
  Instrumentation.
- Central-side storage of plugin config (agent config blobs in Cassandra) —
  see [central.md](central.md).
