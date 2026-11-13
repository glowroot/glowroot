# Embedded H2 upgrade (1.x → 2.x)

**Supported path:** historical H2 SQL data is **not** migrated across the engine file-format change. Back up, archive H2 1.x files, start with a fresh H2 2.x store.

Tracks [#1243](https://github.com/glowroot/glowroot/issues/1243) · regression [#1180](https://github.com/glowroot/glowroot/issues/1180).

## Two layers (do not mix them)

| | Layer 1 — engine file format | Layer 2 — Glowroot SQL schema |
|--|------------------------------|-------------------------------|
| When | Glowroot **≤0.14.4** (H2 1.3.x, `data.h2.db`) → **≥0.14.5** (H2 2.x, `data.mv.db`) | Already on H2 2.x; new Glowroot release adds columns/indexes |
| Product behavior today | **No** auto-migrate | `Schemas.sync*` on boot (can take hours on multi‑GB `data.mv.db`) |
| Operator action | Recreate H2 store (steps below) | Plan a maintenance window |

`*.capped.db` files are a separate ring buffer, not the H2 SQL schema.

## Layer 1 — supported upgrade steps

1. Stop the monitored application (embedded agent shares the JVM).
2. Back up the whole Glowroot `data/` directory (and `conf/` if you customize JSON).
3. Archive or remove H2 **1.x** artifacts (at least `data.h2.db`; keep the backup).
4. Start the application with the new agent. Glowroot creates an empty `data.mv.db`.
5. Confirm UI comes up (`UI listening on …`). Config under `conf/` is unchanged; **trace/rollup history in old H2 is gone** on this path.

Agent JSON config is unrelated to the H2 file format and remains.

## Layer 2 — same H2 major

After upgrades that only change Glowroot schema/indexes (e.g. covering indexes), first boot may run a long sync. That is expected; it is not Layer 1 migration. See also [#1220](https://github.com/glowroot/glowroot/pull/1220).

## Scale (embedded)

| Zone | Guidance |
|------|----------|
| 0–5 GB | Sweet spot with default capped sizes |
| 5–20 GB | Active ops: shorter retention, compact, H2 cache |
| 20–80 GB | Limbo — expect CPU/GC pain (#1180-class) |
| 80 GB+ | Out of design for embedded H2 |

Prefer lean retention/capped sizes in production (Deployment profile **Prod** when [#1188](https://github.com/glowroot/glowroot/pull/1188) lands). Admin → Storage → Compact reclaims `.mv.db` space.

## #1180

CPU/GC rising with `data.mv.db` size after H2 2.x, resetting when data is cleared, points at **storage pressure** (MVStore write/compact), not a single UI bug. Clearing data is a temporary relief, not a fix.

## Offline data port (best-effort, not default)

Supported path remains Layer 1 recreate. Optional CLI (does **not** run at agent boot):

```text
java -jar glowroot.jar h2 upgrade-check
java -jar glowroot.jar h2 import-script <export.sql>
```

`upgrade-check` reports whether `data.h2.db` / `data.mv.db` are present. `import-script` loads an **external** H2 1.x `SCRIPT` dump into a new `data.mv.db` via the bundled H2 2.x driver (export the dump yourself with H2 1.3.x first). Verify the UI before deleting backups. Large scripts (≥ 1 GiB) may OOM or run for hours.

## Corrupt H2 / retention reaper stall (#1284)

Symptoms: repeating ERROR from the embedded retention reaper (`ReaperRunnable` /
`delete from aggregate_*_rollup_* …`), often `JdbcSQLException: General error` with
`ArrayIndexOutOfBoundsException` inside H2 `PageDataLeaf` / index code. The UI may still
listen; `/readiness` fails if the probed rollup tables error.

What it means: **page/index corruption** (or a store already outside the scale table above),
not a bad Glowroot SQL shape. Retention for the failing table stops advancing → `data/` can
grow without bound and pressure the **shared** application JVM.

Product behavior (current tree):

- Reaper continues **other** tables/levels after a per-step failure (one corrupt table no
  longer aborts the whole cycle).
- Admin → Storage shows live **H2 data file size** and a warning when the reaper has a
  consecutive failure streak; JMX `org.glowroot:type=H2Database` exposes `FileSize` and
  `ReaperConsecutiveFailedCycles`.
- `/readiness` probes both `aggregate_tt_rollup_0` and `aggregate_tn_rollup_0`.

Operator recovery:

1. Prefer **Central** (or much shorter retention) for high-volume hosts — embedded is not a
   warehouse.
2. If indexes are already broken: stop the app, **wipe** embedded H2 under `data/` (keep
   `conf/`), restart — history in H2 is gone. A full multi‑10 GB backup is often impractical.
3. Upgrading Glowroot / H2 major **does not repair** a corrupt `data.h2.db` / `data.mv.db`;
   Layer 1 recreate still applies (see above).
4. Alert on H2 file size growth and on repeating reaper errors (logs, Admin Storage, or JMX).

## Central

This page is **embedded-only**. Central uses Cassandra; H2 file migration does not apply.
