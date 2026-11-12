# glowroot-ops: wiki + in-repo docs bridge

## What

Updated the portable `skills/glowroot-ops` pack so analysis answers can use
in-repo `docs/` topic notes after a live wiki fetch, and refreshed wiki /
decision / triage indexes for health, H2 upgrade, Docker Central, and related
operator pages.

## Why

Wiki remains the operator how-to source, but several facts now live under
`docs/` on `main` (health `/health` vs `/readiness`, H2 1.x→2.x recreate path,
0.14.8 upgrade notes, JVM allocated-memory column). The skill only pointed at
wiki, so globally installed assistants missed those notes.

## How

- New `skills/glowroot-ops/docs-index.md` — ops-relevant `docs/` paths + raw
  GitHub URLs for skill-only installs
- Workflow updates: `SKILL.md`, `PROMPT.md`, `AGENTS.md`, `INSTALL.md`,
  `README.md`, `issue-gate.md` (wiki first, then docs-index; output section
  `Docs`)
- Expanded `wiki-index.md`, `decision-tree.md`, `triage-patterns-dev.md`

## Choices

- **Separate `docs-index.md`** instead of stuffing paths into `wiki-index.md`
  — keeps wiki fetch list clean and documents clone vs raw-URL portability
- **Ops-only docs subset** — skipped contributor maps (`wire-api`,
  `agent-modules`, PR templates) so the skill stays analysis-focused
- **No Never-lab / feature-branch install URLs** — keep `glowroot/glowroot`
  `main` as the install source

## Verification

- Confirmed wiki pages exist via raw wiki HTTP 200: Embedded-H2-upgrade,
  Health-endpoints, How-to-read-a-Trace, Re-engineering-a-slow-call, What's-new,
  Central-Collector-with-Docker
- Confirmed listed `docs/*.md` files exist on this branch / `main`
- Inline `glowroot-code-review`: `GATE: READY` (docs-only skill pack)

## Follow-ups (optional)

- Re-install global skill after merge (`npx skills add …`) so agents pick up
  `docs-index.md`
