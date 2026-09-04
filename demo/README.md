# Glowroot local demo

One-shot replacement for the defunct public demo (`demo.glowroot.org`).

Stack matches the pattern @nowheresly uses for local data generation
([spring-rabbitmq-glowroot](https://github.com/nowheresly/spring-rabbitmq-glowroot)):

- Cassandra + glowroot-central (published image)
- RabbitMQ
- Small Spring Boot app with `-javaagent` → Central, generating HTTP / JDBC / AMQP traffic

## Run

Docker Desktop (or equivalent) required. From this directory:

```bash
docker compose up --build
```

Then open http://localhost:4000 — pick agent `demo-sample`.

First Central start waits on Cassandra (often 1–2 minutes). Traffic begins once the sample app is up.

Stop with Ctrl+C.

## Notes

- Central UI on `:4000`, gRPC on `:8181`. Sample app is not published (it calls itself + Rabbit).
- Agent jar is downloaded in the sample-app image build (same version as the Central image tag).
- For a fuller multi-module playground (Kafka, WebFlux, cluster compose), use the upstream sample linked above.
