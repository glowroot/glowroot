# Glowroot demo (one-shot)

Local replacement for the defunct public demo (`demo.glowroot.org`). Starts
embedded Glowroot with the existing `UiSandboxMain` load generator.

Docker only packages jars — Maven builds on the host so the image context stays small.

## Run

From the repo root (JDK 11+, Maven 3.8+, Docker Desktop running):

```powershell
.\demo\prepare.ps1
docker compose -f demo/docker-compose.yml up --build
```

Linux/macOS:

```bash
./demo/prepare.sh
docker compose -f demo/docker-compose.yml up --build
```

Then open http://localhost:4000

Stop with Ctrl+C. Data persists in the Compose volume `demo-data`.

Re-run only `docker compose ...` after code changes if you already ran `prepare` for that tree.

## Notes

- Embedded only (no Cassandra / Central).
- Runs with `-Dglowroot.demo=true`: DEMO banner in the UI, facsimile Web/SQL traffic (not Sandbox UI-stress noise).
- UI listens on `0.0.0.0:4000` inside the container so port publish works.
- `demo/runtime/` is local build output (gitignored); same layout is what CI can publish to GHCR later.
