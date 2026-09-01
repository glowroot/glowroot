# Glowroot — local dev setup

Generic install guide. For paths and stack specifics, read **`ORG-CONTEXT.md`** (create from `ORG-CONTEXT.md.template`).

Embedded mode fits most local dev. Use Central only when you need a shared dashboard across many agents.

## Recommended version

Use the tag in `{{RECOMMENDED_VERSION}}` from `ORG-CONTEXT.md`, or latest **stable** from [Releases](https://github.com/glowroot/glowroot/releases).

Download `*-dist.zip` only — not a jar built from `agent/shaded/`.

## Directory layout

```
{{INSTALL_PATH_DEV}}/
├── glowroot.jar
├── glowroot.properties   (optional)
├── admin.json            (created on first start)
└── data/                 (embedded H2, auto-created)
```

## Attach the agent

```
{{JVM_ARG_EXAMPLE}}
```

Place in your app server config (`setenv.bat`, IDE VM options, `spring-boot.run.jvmArguments`, etc.).  
`-javaagent` must come **before** `-jar` on executable JARs.

### Spring Boot (example)

```bash
mvn spring-boot:run -Dspring-boot.run.jvmArguments="{{JVM_ARG_EXAMPLE}}"
```

### Multiple local JVMs

In `glowroot.properties`:

```properties
multi.dir=true
```

Per JVM:

```
{{JVM_ARG_EXAMPLE}} -Dglowroot.agent.id={{AGENT_ID_PREFIX}}service-name
```

Each agent needs a **unique UI port** (Configuration → Web or `admin.json`).

## Port 4000 already in use

1. Start app once (creates `admin.json`)
2. Stop app
3. Set `web.port` in `admin.json` (e.g. 4001)
4. Restart

## Docker (optional)

| Pattern | Notes |
|---------|--------|
| App in container | Mount glowroot dir; set `-javaagent` on the **container** JVM |
| Agent env var | `GLOWROOT_OPTS=-javaagent:/opt/glowroot/glowroot.jar` |

Wiki: [Central Collector with Docker](https://github.com/glowroot/glowroot/wiki/Central-Collector-with-Docker) (Central); embedded needs writable `data/` volume.

## UI not opening — checklist

| Check | Action |
|-------|--------|
| App JVM running? | Agent attaches to a live JVM |
| `UI listening on` in logs? | Search app server log |
| Port free? | Change `web.port` in `admin.json` |
| Writable install dir? | JVM user needs write access |
| Correct dist zip? | Releases `*-dist.zip` only |
| JDK vs agent version | Java 21+ needs 0.14.7+ dist |

## Embedded vs Central

| Use case | Mode |
|----------|------|
| Local dev (`{{HOW_DEVS_START_APP}}`) | **Embedded** |
| Single server prod | Usually **Embedded** |
| Many agents, one dashboard | **Central** + `collector.address` gRPC `:8181` (not UI `:4000`) |

See `ORG-CONTEXT.md` for `{{DEFAULT_MODE}}` and `{{CENTRAL_GRPC_ADDRESS}}`.

## Wiki

Fetch live: https://github.com/glowroot/glowroot/wiki — see `wiki-index.md`.
