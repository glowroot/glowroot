# Lean Storage defaults under Maintenance

## What

Administration → Storage gains **Apply lean defaults** (embedded Maintenance;
Central Lean defaults block). Fills the Storage form only; Save still required.

## Why

Full default retention / capped sizes on a shared JVM often grow embedded H2
until it hurts. One button on the existing Storage page is enough for a safer
starting point.

## How

- UI: Storage form + help popover (`storage-lean-prod.html`)
- Embedded: shorter retention + smaller capped DB sizes
- Central: shorter Cassandra TTLs (rollup / query / profile / traces)
- Same Admin/storage POST as today after Save

## Choices

Form fill only — no new config page, no multi-POST across Transactions/Advanced.
Does not answer Central capacity sizing (#715). Closes #1243 as part of the H2
embedded workstream.

## Verification

- [x] Embedded: Apply lean defaults → form fills → Save
- [ ] Central: Apply lean defaults → TTL fields fill → Save
