# Triage know-how

Operational knowledge base for Glowroot backlog triage.
Use **before** re-exploring from scratch: patterns → already-seen issues → code paths.

Dashboard: [index.html](index.html) · Excluded IDs: [triaged-ids.json](triaged-ids.json)

Update this file after each triage batch when you learn a new recurring pattern.
Related: [discussion #1197](https://github.com/glowroot/glowroot/discussions/1197).

---

## Protocol (summary)

| Difficulty | Length | Redirect | Close? |
|------------|--------|----------|--------|
| **Easy** | 3–6 lines | wiki / Discussions / Google group | yes, ping `@nowheresly` |
| **Medium** | structured | if support | close or keep + label |
| **Hard** | as much as needed | no if real bug | keep / enhancement |

**Closed = know-how only:** if the issue is already **CLOSED** upstream, **do not** comment, **do not** ping, **do not** reopen. Extract only useful know-how (pattern + path) into this file. This avoids re-exploring from scratch when a related open issue reappears — zero noise on the tracker.

Always (on **open** issues only): `@nowheresly` · English · update `triaged-ids.json` + regenerate the dashboard when you finish a batch.

---

## Recurring patterns (internal FAQ)

### Install / agent path
- **Wrong jar from source build**: `agent/shaded/core` ≠ dist; use `agent/dist` / Releases zip. `#932`
- **Build protoc `.exe` on Linux**: protoc-jar platform mis-detect; often env/proxy; Releases workaround. `#928`
- **Maven can’t resolve `glowroot-agent-dist-maven-plugin:…-SNAPSHOT`**: not on Central — full reactor `mvn install` or Releases. `#880`
- **WebLogic 11g / Java 7**: current agent Java 8+; historical releases only. `#911`
- **ClassCircularityError Guava preloadSomeSuperTypes**: typical 0.13 + Guava; retry 0.14.x agent only. `#809`
- **Jakarta EE 9 / jakarta.servlet**: supported in 0.14.x servlet plugin; upgrade. `#807` · related `#948`
- **K8s Central slow + single UI entry**: unique `agent.id` per JVM; group with `App::pod`; C*/Central undersized. `#801`
- **WAS UI won’t open / no admin.json**: shareclasses, listen log, Java 8+ (not Java 6). `#783`
- **Mongo + Mongock decorator NSME**: disable mongodb plugin or migrations without agent. `#776`
- **Session attributes capture**: `*` or path/`name.*` — not `foo*`. `#770`
- **Java 6 + HdrHistogram USC**: agent is not Java 6. `#768`
- **`/health` 200 with Cassandra down**: gap enhancement. `#766`
- **CLI delete data.h2.db**: stop JVM + unlink; no official embedded CLI. `#763`
- **Dual Elastic/OTel + Glowroot JDBC mixin**: unsupported; = `#1026` `#762` `#938`
- **Breakdown count ≫ Entries rows**: Max trace entries truncates entries UI; counts are full. `#761`
- **Oracle EBS / multi WebLogic JVM**: agent per managed server + Central `collector.address`; no EBS plugin. `#760`
- **Alerts after delete**: Save + incidents + restart if stuck. `#754`
- **H2 OOM on recover after kill**: delete `data/`; no auto-heal. `#749` · related `#755` `#763`
- **Service Calls ≠ local @Service**: only outbound HTTP-ish plugins. `#746` · related `#812`
- **UI unreachable / no listen line**: bind+port, tunnel, wait for listen log. `#740` `#783`
- **Synthetic flat 60000 ms**: timeout, not real latency. `#739`
- **Custom instr. on WAS**: capture **Transaction**; not Proxy/Lambda. `#731`
- **Protobuf timer nesting too deep** (`InvalidProtocolBufferException` / Aggregate$Timer): deep timer trees; upgrade; isolate agent; related Jackson profile knob `#1122`. `#704` `#730`
- **Slow-trace count ignores filters**: UX gap/bug. `#725`
- **JS `.length` on long truncated queries** (Entries / Query Stats): fixed on main (`handlebars-rendering.js`, #1187). `#1107`
- **Session Attribute Name filter empty**: session attrs are root **detail** only; Attribute Name filters `Trace.Attribute`. `#1123`
- **URL regex grouping**: does not exist — `#665` `#723`
- **VerifyError after weave (Tomcat listener)**: no classpath ignore; try 0.14.x. `#721`
- **WL codegen FNFE `.class`**: noise on EJB generated; often harmless. `#713`
- **Alert maintenance schedule**: no cron mute; enhancement. `#708`
- **Lambda / agentless API**: not supported. `#707`
- **JavaLoggingAspect NPE** (JUL formatMessage): logger plugin bug. `#706`
- **Layer7/old Tomcat `getStatus` AbstractMethodError**: noise; mute log. `#705`
- **Breakdown timers don’t sum to root**: expected; Profile for rest. Closed know-how `#714`
- **Email CNFE shaded `javax.activation.DataHandler`**: do not force jakarta.activation on the module-path. `#1150`
- **Karaf/OSGi ClassAnalyzer CNFE Jetty super**: weaving loader hierarchy; PR `#1147`. `#1148`
- **Agent→Central `HTTP/` / SETTINGS error**: often `collector.address` points to UI `:4000` instead of gRPC `:8181`. `#1143`
- **collector.address with path `/collector`**: not supported; host without path. `#1041`
- **Log4j2 Errors tab empty (≥2.12.1)**: pointcut `log` vs `logMessage`; fix in 0.14.x `Log4j2xAspect`. `#1038`
- **Agent→Central via HTTP proxy settings**: none; need TCP/network proxy. `#1033`
- **Central multi-replica**: wiki Central Collector Cluster + sizing C*. `#1030`
- **JAR missing / corrupt** (`Error opening zip file or JAR manifest missing`): path/mount/container, not a bug. `#940`
- **Bulk install**: no installer → copy + `-javaagent` + config mgmt. `#750`
- **Embed dist in deliverable**: bundle in image/deploy + javaagent; no auto-attach Maven. `#953`
- **Shared lib multi-JVM**: one JAR ok; writable state per JVM + `multi.dir` / `agent.id`. `#1007`
- **H2 data dir location**: `data.dir` / wiki directory locations. `#751`
- **H2 Locked by another process**: embedded exclusive — `data.dir`/`multi.dir` per JVM or Central; backlog often fallout. `#1003`
- **Offline viewer**: `java -Dglowroot.data.dir=… -jar glowroot.jar` (same data). `#743`
- **H2 `data.h2.db` grows**: retention/full query text; capped ≠ H2; clean with history loss. `#755`
- **H2 user/pass**: `sa` / empty password; no UI to change it. `#890`
- **agent.id display / multi.dir**: `agent.id=NAME` in properties; on-disk folder `agent-<id>`. `#701`
- **ES permission denied**: glowroot dir writable by ES process user. `#742`
- **Executable JAR**: `-javaagent` before `-jar`. `#710`
- **Central sizing 200 agents**: Q&A; embedded JSON storage ≠ Central Cassandra. `#715`
- **Central without Cassandra**: standalone C* required; `multi.dir` ≠ Central. `#717` `#678`
- **Agent without Central → DB**: embedded = local H2; custom Collector SPI; no arbitrary JDBC. `#912` `#920`
- **collector.address with underscore in hostname**: invalid for URI/gRPC; use valid DNS name or IP. `#696`
- **Build `mvn install` shade duplicate**: `mvn clean` / MSHADE-126; NameResolver filters in shaded. `#655`

### Central / Cassandra capacity
- **Trace/slow-trace retention 14d default**: Central `traceExpirationHours` default `24*14`; UI Administration → Storage. Expiration = **Cassandra TTL on write** — raising days only applies to *new* traces; old ones keep the previous TTL (help text in `storage.html`). Not a bug. Open FAQ: `#676`. Closed know-how (reduce retention + purge rollup): `#566` — `java -jar glowroot-central.jar delete-old-data <partialTable> <rollupLevel 0..3>` then C* compaction; **does not** cover trace tables (only summary/overview/histogram/throughput/query/service_call/profile/error_summary/gauge_value). Full wipe: `truncate-all-data`. Open repeats purge: `#1058`. Code: `CentralStorageConfig`, `CentralModule` (`delete-old-data`), `TraceDaoImpl`.
- `RESOURCE_EXHAUSTED` / backlog / `BusyPoolException` / `WriteFailureException` QUORUM: back-pressure Central↔Cassandra; capacity + volume (slow threshold, # agents). `#748` `#899` `#900` `#957` `#979` `#893`
- **Rollup Cassandra stuck on old capture_time / heavy read**: rollup backlog / C* pressure. `#699` `#603`
- **tombstone_warn_threshold** on rollup tables: Cassandra ops (compaction / null bindings / retention). `#603`
- **Alert delete → Central OOM/timeout** (0.13.x cluster): needs-info stale; Cassandra load vs bug alert lifecycle. `#615`
- **Per-rollup agent cap**: does not exist; stable `agent.id` + prune. `#343`
- **Agent↔Central auth**: none; ACL/VPN/proxy/HTTPS. `#311` (enhancement)
- **Central on Windows**: Central = Java OK; Cassandra better on Docker/WSL/VM Linux. Wiki Central install. `#678`
- **Agent→Central timeout**: network/firewall/bind `0.0.0.0` on 8181; test `nc`/`curl` from agent host. `#758`
- **Cosmos DB Cassandra API**: yes with `cassandra.port=10350` + `ssl=true` (PR `#668`); Microsoft doc. `#637`
- **Trace from Cassandra manually**: prefer UI API (`/backend/trace/*`); `*_slow_point` = indices; header/entry/profile = protobuf blob (`TraceDaoImpl`). `#664`
- **Export / API monitor data**: `/backend/…` + login cookie; no SDK export; H2/Cassandra are not public APIs. `#645` `#664` `#718`

### Config defaults
- **Public demo down**: `demo.glowroot.org` timeout; infra/ops, not agent. `#1102` `#1085`
- **Central vs embedded install (“monitor server”)**: agent on app JVM + Central (+Cassandra) on monitor host. Wiki Central install + Releases. `#1079`
- **Helm / K8s agent→Central won’t connect**: `collector.address` = Central Service DNS + gRPC HTTP port; NetworkPolicy; not `localhost` in app pod. `#1064` `#978`
- **Wiki agent zip link broken / vandalized**: download from GitHub Releases (`glowroot-*-dist.zip`); wiki fix in `#1194`. `#1132`
- **Storage retention UI vs Cassandra DESCRIBE**: per-insert TTL (`USING TTL`), not table `default_time_to_live`; not retroactive; Update TWCS; no manual ALTER. `#1060` · related `#676` `#566`
- **NSME `OverallSummaryCollector.mergeSummary`**: mixed jar/agent or partial upgrade — clean 0.14.x dist. `#1075`
- **JRebel + Glowroot**: native agent before javaagent; weaving conflict (often executor plugin). Workaround: no JRebel in combo, or disable executor plugin. `#1084` · related early-load warning `#1124`
- **Alert transaction name**: exact match to Glowroot name (often `requestURI` like `/api/…`, not a short segment). `#905`
- **Errors tab vs catalina.out**: Errors = only errors tied to traced txns; container log ≠ Errors store. `#887`
- **Disable UI listen port**: does not exist; `port=0` = ephemeral. Offline viewer / localhost. `#886`
- **Slow threshold default 2000**: hardcoded `TransactionConfig`; per-agent UI/Central; no global Cassandra default. `#899`
- **100% tracing / no sampling**: no sample %; aggregates already full; lower slow threshold for more traces. `#736`
- **JMX remote authenticate=false**: not Glowroot; remove if remote JMX not needed. `#745`
- **Gauges yes, Web no (OTP/Vert.x-like)**: agent OK; HTTP not woven — Instrumentation / framework. `#744`
- **Bind 0.0.0.0**: default 127 intentional; config wiki. `#988`
- **Azure App Service + Docker UI**: `EXPOSE` + `WEBSITES_PORT` in addition to bind 0.0.0.0. `#1016`
- **Disable Errors UI/capture**: no global switch; Logger plugin + Roles. `#1009`
- **UI not listening embedded**: UI after `main`; if app crashes immediately, no bind. `#1002`
- **Upgrade Central schema / AssertionError C***: contactPoints + Cassandra 3.11+ health. `#993`
- **Kubernetes Spring Boot**: image or volume; `data.dir`; Central multi-pod. `#978`
- **API 401 with user/pass**: `POST /backend/login` JSON + session cookie; not Basic. `#718`
- **Mute send-to-collector without restart**: does not exist; network / reduce UI volume / remove javaagent. Wishlist. `#640`
- **Gauges = JMX history**: Configuration → Gauges (not just MBean tree). `#769`
- **Waited / Blocked time**: ThreadMXBean; thread profile to locate. `#631`
- **Scheduled thread dump to file**: no; `jcmd`/`jstack` + cron. `#752`
- **LogstashEncoder on Central Docker**: `logback.xml` in conf.dir yes; Logstash jar not in Hub image → custom image. `#561`
- **Central “security” via web.xml error-page**: do not apply Tomcat scanner to Central jar; UI stack on invalid input ≠ CVE. `#827`
- **Get involved / maintainer**: Discussions + PR; maintainer rights not via issue. `#633`

### Plugins / capture
- **Exclude specific SLF4J/log lines from traces**: no filter by logger name; Logger plugin weaves callAppenders. Workarounds: audit outside woven logger, or disable entire logger plugin. Enhancement gap. `#1069`
- **URL → transaction name (regex)**: does not exist; servlet uses `requestURI`; workaround Instrumentation + header `Glowroot-Transaction-Name`. Enhancement. `#665` · code: `ServletAspect`
- **Apache async HttpClient producer/consumer**: no service-call entry (aux callback only); plugin gap. JDK `HttpClient` not woven. `#968`
- **admin.json missing**: not firewall-dependent; conf dir + UI/agent init; startup backlog can block it. `#958`
- **config.json / admin.json how generated**: auto on start/Save; do not write by hand. `#954` `#958`
- **Prometheus/Grafana**: no native exporter; API `/backend` or custom Collector. `#952`
- **WebFlux txn names/timers**: raw URI + little “Spring”; gap vs MVC ControllerAspect. `#945` · related `#665` `#949`
- **Central Connection reset (Netty)**: often probe/LB; optional DEBUG log. `#942`
- **Queries tab empty (Vert.x)**: Queries = weave `java.sql.*`; custom Instrumentation ≠ Queries; Vert.x SQL Client often bypasses. `#937` · code: `jdbc-plugin` `StatementAspect`
- **Spring `@Bean` `@Scheduled`**: real enhancement — stereotype vs `ScheduledMethodRunnable`. `#955` · keep open
- **Heap % of -Xmx**: no % series; gauge used+max; wishlist derived %. `#946`
- **Dual agent Elastic + Glowroot**: no Elastic export; weaving fragile; Java 8+. `#938`
- **Spring Boot 3 / jakarta.servlet**: old agent = Startup only, no Web; use current 0.14.x. `#948`
- **Monitor Elasticsearch**: agent on ES JVM and/or client plugin on app; no dedicated ES product. `#709`
- **Dual agent Elastic APM + Glowroot JDBC mixin ICCE** (`HasStatementMirrorMixin` / Hikari): one agent only; or disable jdbc plugin. `#1026` · related `#938` `#734`
- **WAS/IBM JDBC weave AIOOBE** (`WSJdbcStatement` / `OracleClosedStatement` + other javaagent first): hard; try 0.14.x alone / jdbc off. `#892`
- **EJB remote interface `default` methods → NSME with agent**: weaving/reflection; avoid default methods. `#883` · related `#977`
- **Cassandra plugin mixin on JDK (`ResultSetMixin` / ICCE)**: weaving edge; 0.14.x or disable plugin. `#734`
- **SAXParserFactory / Xerces CNFE at agent start**: system property points to Xerces not on agent CL; use JDK factory. `#813`
- **LDAP password in admin-default.json**: wishlist/PR `#733` open; `#729`
- **Request/response body**: not in mainline; experimental Trask branch not merged. `#579` enhancement
- **RUM / browser / Angular client**: server-side JVM only. `#477`
- **Distributed tracing**: no (nor topology map); service calls per-JVM. `#290` `#1010`

### WildFly / JBoss Modules / SecurityManager / Weld
- `NoClassDefFoundError: ManagementFactory` from servlet plugin: TCCL `servlet.api` without `java.management`. Workaround: dependency in `module.xml`. Optional fix: bootstrap CL. `#1025` `#956` · code: `ContainerStartup.initPlatformMBeanServer`
- **WAS/AIX same MBean noise** (`PlatformMBeanServerBuilder` CNFE): platform / classloader; related `#753` `#1025`
- `AccessControlException: createMBeanServer`: **SecurityManager** — grant `MBeanServerPermission`. Same call site, different cause from `#1025`. `#596`
- **WildFly SECMGR + Glowroot** (MBean + FilePermission data/): grant policy on Glowroot jar. `#719` · related `#596` `#882`
- **Elasticsearch SecurityManager + Glowroot**: grant tmp/JMX/reflect; ES policy. `#882` · related `#596` `#742` `#938`
- **Weld NPE + Glowroot on EAP** (EAR): weaving generics / `overrideAndWeaveInheritedMethod` — hard; PR `#1157`. Debug: `-Dglowroot.debug.className=…`. Do not confuse with `#1025`. `#977` · related `#550`
- **OpenWebBeans/TomEE GenericsUtil NPE**: same weaving generics family; track with `#977`/`#1157`. `#967`

### Security / CVE
- **Log4Shell**: Glowroot uses logback; Log4j in plugin pom = test only. `#885`

### Product wishlist (keep / label)
| Topic | Issue |
|-------|-------|
| Agent↔Central auth | `#311` |
| Dist tracing / call topology map | `#290` `#1010` `#1140` (needs-info) |
| Heap used % | `#946` |
| Spring @Scheduled on @Bean | `#955` |
| Max agents / rollup | `#343` |
| Org-wide default slow threshold | `#899` |
| RUM | `#477` |
| Request/response body capture | `#579` |
| Glowroot UI redesign (stale @nidorx mockups) | `#166` — AngularJS stays; close wishlist |
| Alert mute on calendar / maintenance window | `#708` |
| Agentless / Lambda embed API | `#707` |
| Thread name on trace export | `#759` |
| `/health` should fail when Cassandra is down | `#766` |
| JSR-356 / WildFly WebSocket parity | `#864` (Spring WS limited only) |
| Spring WebServiceTemplate → Service Calls | `#812` |
| Errors filter full-trace text search | `#876` |
| Filter slow traces by query string / HTTP method | `#901` |
| Wildcard slow threshold override | `#756` |
| LDAP password in admin-default (Helm) | `#729` → PR `#733` |
| Pause agent→Central without restart | `#640` |
| Central filter agents by agent.id regex | `#897` |
| Min/max memory on All transactions | `#1089` |
| Apache async / JDK HttpClient service calls | `#968` |
| WebFlux route templates / Spring timers | `#945` |
| Prometheus exporter | `#952` |
| Empty aggregate flush @ 1ms rollup | `#693` → PR `#692` |
| DnsNameResolverProvider / Central connect | `#691` → PR `#690` |

---

## Useful code paths (for analysis / PR)

| Area | Path |
|------|------|
| Slow threshold default | `common/.../TransactionConfig.java` (`2000`) |
| Servlet txn name / headers | `agent/plugins/servlet-plugin/.../ServletAspect.java` |
| Servlet startup / MBean | `agent/plugins/servlet-plugin/.../ContainerStartup.java` |
| Cassandra trace schema / TTL | `central/.../TraceDaoImpl.java` · `CentralStorageConfig.traceExpirationHours` |
| Central purge old rollups | `central/.../CentralModule.java` (`delete-old-data`) · `#566` |
| Trace HTTP API | `ui/.../TraceJsonService.java` + `TraceDetailHttpService.java` |
| JDBC Queries weave | `agent/plugins/jdbc-plugin/.../StatementAspect.java` |
| Trace queue / backlog | `agent/core/.../TraceCollector.java` |
| Central logback override | `central/src/main/resources/logback.xml` + `CentralModule` conf.dir |
| Custom collector SPI | agent shaded + `trask/glowroot-example-collector` |
| Weaving inherited/generics | `agent/core/.../weaving/WeavingClassVisitor.java` (`overrideAndWeaveInheritedMethod`) · `#977` |

---

## Hard code-fix playbook (weaving / agent)

For weaving/agent bugs like `#977`: (1) confirm agent-only, (2)
`-Dglowroot.debug.className=…` + `javap`, (3) minimal generics repro,
(4) targeted fix + test, (5) do not confuse with `#1025` module.xml.
Open PR: `#1157`.

---

## Standard external links (paste in Easy triage)

- Wiki: https://github.com/glowroot/glowroot/wiki  
- Embedded install: https://github.com/glowroot/glowroot/wiki/Agent-Installation-(with-Embedded-Collector)  
- Central install: https://github.com/glowroot/glowroot/wiki/Agent-Installation-(for-Central-Collector)  
- Central Docker: https://github.com/glowroot/glowroot/wiki/Central-Collector-with-Docker  
- Discussions: https://github.com/glowroot/glowroot/discussions  
- Google group: https://groups.google.com/forum/#!forum/glowroot  

---

## Maintenance

1. New recurring pattern → add a bullet under [Recurring patterns](#recurring-patterns-internal-faq) (and a table row if useful)  
2. Add the issue id to `triaged-ids.json`  
3. Run `.\docs\issue-map\regenerate-dashboard.ps1 -AddIds …` (use `-Fetch` to refresh from GitHub)
