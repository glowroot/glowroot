# Embedded H2 upgrade (1.x → 2.x)

**Supported path:** historical H2 SQL data is **not** migrated across the engine file-format change. Back up, archive H2 1.x files, start with a fresh H2 2.x store.

Tracks [#1243](https://github.com/glowroot/glowroot/issues/1243) · regression [#1180](https://github.com/glowroot/glowroot/issues/1180) · design [superpowers/specs/2026-09-04-h2-upgrade-migration-policy-design.md](superpowers/specs/2026-09-04-h2-upgrade-migration-policy-design.md)

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

## Offline data port (planned, not default)

A CLI best-effort SCRIPT/RUNSCRIPT helper may appear later. It will **not** run at agent boot and is **not** the supported path. Until it exists, use Layer 1 recreate (or external H2 tooling at your own risk).

## Central

This page is **embedded-only**. Central uses Cassandra; H2 file migration does not apply.
