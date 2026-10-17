# Try Postgres Central (experimental)

## 1. Postgres

```powershell
cd demo\postgres
docker compose up -d
```

## 2. Build / dist

From repo root:

```powershell
mvn package -pl :glowroot-central -am "-DskipTests"
```

Dist:

`central\target\glowroot-central-0.14.8-beta.6-SNAPSHOT-dist\glowroot-central\`

## 3. Config

Copy properties **next to the jar** (not a `conf\` subdir):

```powershell
Copy-Item demo\postgres\glowroot-central.properties `
  central\target\glowroot-central-0.14.8-beta.6-SNAPSHOT-dist\glowroot-central\glowroot-central.properties -Force
```

Keep a real 32-hex `cassandra.symmetricEncryptionKey` if you already use one. Verify the file contains `storage.type=postgres` (dist ships an empty template that defaults to Cassandra).

## 4. Run

```powershell
cd central\target\glowroot-central-0.14.8-beta.6-SNAPSHOT-dist\glowroot-central
java -jar glowroot-central.jar
```

**UI:** http://127.0.0.1:4000  
**gRPC:** default `8181` (agent collector)

Expect log: `starting Glowroot Central with storage.type=postgres (experimental)` then `startup complete`.

## Notes

- Experimental — not production parity with Cassandra.
- CLI `create-schema` / tools remain Cassandra-only.
