# Central delete-agent-meta CLI and Admin Storage UI

## What

Central can delete stale **agent metadata** (config row, display name, environment)
for one agent/rollup id via CLI and Admin → Storage → Danger.

## Why

Agent config / display / environment rows never expired. Operators needed a
supported way to drop metadata for a retired agent without wiping traces or
rollups.

## How

- CLI: `delete-agent-meta <id>`
- Admin → Storage → Danger: dropdown + confirm; `POST /backend/admin/delete-agent-meta`
  (`admin:edit:storage`)
- Dropdown: agents active in last 7 days that still have metadata
- Idempotent; INFO log includes who ran it
- Embedded `RepoAdmin` stub remains no-op (Central-only)

## Choices

Deletes metadata only — not traces, gauge history, or child rollups. Replaces
DAO “never expire” TODOs with UI/CLI instead of silent TTL.

## Verification

- UI screenshots on the PR (Danger flow, confirm, success, attribution log)
- [ ] CLI against a real central jar
- [ ] Charts/traces for that id untouched after delete
