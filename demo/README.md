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

Then open http://127.0.0.1:4000 — pick agent `demo-sample`.

**First boot is slow:** Cassandra must come up, then Central creates the schema
(many tables). The UI only answers after you see `UI listening on 0.0.0.0:4000`
in `docker compose logs -f glowroot` — often **3–5 minutes** on a laptop.
Until then `:4000` is published but returns an empty reply.

Stop with Ctrl+C.

## Notes

- Central UI on `:4000`, gRPC on `:8181`. Sample app is not published (it calls itself + Rabbit).
- Agent jar is downloaded in the sample-app image build (same version as the Central image tag).
- Compose waits for Cassandra/Rabbit health before starting Central / the sample app.
- For a fuller multi-module playground (Kafka, WebFlux, cluster compose), use the upstream sample linked above.
