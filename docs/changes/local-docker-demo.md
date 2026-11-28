# Local Docker demo (Central + sample Spring/Rabbit app)

## What

`demo/docker-compose.yml` runs Cassandra + published Central image + RabbitMQ +
a small Spring Boot sample with `-javaagent` generating HTTP/JDBC/AMQP traffic.

## Why

`demo.glowroot.org` has been 522 for ages (#1102). Contributors need a local
try-before-download path for Central + live agent traffic.

## How

- Compose stack; agent id `demo-sample`; UI http://127.0.0.1:4000
- Healthchecks gate Central / sample app after Cassandra schema create
- Pattern aligned with nowheresly/spring-rabbitmq-glowroot

## Choices

No UiSandbox facsimile / DEMO banner. No GHCR prebuilt sample image in this PR.
Public hosted demo (#1102) stays separate.

## Verification

- [x] `docker compose -f demo/docker-compose.yml up --build`
- [x] UI :4000 after schema create; agent `demo-sample` with live traffic
