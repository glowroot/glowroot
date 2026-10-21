# Spring Boot starter marker + attach docs

## What

Publishes empty marker JAR `org.glowroot:glowroot-spring-boot-starter` with
in-JAR README (Maven/Gradle copy-paste for `-javaagent` attach). Root README
links the guide.

## Why

People search Maven for `glowroot-spring-boot-starter` and find nothing. A
marker artifact plus honest attach docs closes that discovery gap without
pretending bytecode weaving is a classpath starter.

## How

- New module `glowroot-spring-boot-starter` (marker JAR + `META-INF/README.md`)
- Snippets: copy agent from Maven Central, pass `-javaagent`; optional Central
  `glowroot.collector.address`
- Root README link

## Choices

No classpath auto-attach / no weave-on-dependency. Weaving stays agent +
`spring-plugin`. Does **not** fix Maven Central agent publish (#1238).

## Verification

- [x] `mvn -pl :glowroot-spring-boot-starter package` — jar contains README
- [ ] Snippet typos / paths OK for reviewer setup
