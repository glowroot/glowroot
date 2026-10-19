# In-repo product maps and PR change notes

## What

Added Markdown documentation under `docs/` that maps Glowroot **as shipped on
`main`** (product, repository layout, agent, plugins, central, wire-api, UI),
fixed `CONTRIBUTING.md` (removed broken HTML architecture links; point at the
new maps), required a per-PR change note via `docs/pr-change-template.md` →
`docs/changes/`, and aligned `CLAUDE.md` with those docs and current facts
(27 plugins, dual data paths, SCSS, demo host caveat).

## Why

In-repo `docs/` on `main` had only a few topic notes; `CONTRIBUTING.md` linked
to interactive architecture HTML/JSON that are **not** in the tree, so new
contributors hit dead ends. We need a navigable map of the real product and a
lightweight way to record what/why/how/choices on each non-trivial PR for
review and maintenance.

## How

- New pages: `docs/README.md`, `product-map.md`, `repository-layout.md`,
  `agent-modules.md`, `agent-plugins.md`, `central.md`, `wire-api.md`, `ui.md`
- Process: `docs/pr-change-template.md`, `docs/changes/README.md`, this note
- Updated: `CONTRIBUTING.md` (orientation + PR doc policy), `CLAUDE.md`
  (pointers + factual corrections)
- Content sourced from the `main` tree only (Maven modules, protos, packages);
  wiki left for operator install how-tos

## Choices

- **Markdown + Mermaid on GitHub** instead of regenerating missing HTML
  architecture viewers — readable in PRs without local tooling; matches the
  rest of in-repo notes.
- **Hub + focused pages** rather than one giant file — easier to extend
  (central gRPC/`util`, UI backend) without drowning the product map.
- **`docs/changes/<slug>.md` for PR notes** instead of only PR body text —
  survives merge and stays searchable next to code; template keeps sections
  consistent (What / Why / How / Choices / Verification).
- **Did not document Postgres or other non-`main` storage** — maps describe
  current upstream `main` (Central = Cassandra).
- **Did not import fork-local `docs/apprendimenti`** — exnovo upstream-facing
  content only.

## Verification

- Cross-checked module lists, plugin `glowroot.plugin.json` ids, central
  packages, wire-api `.proto` RPC names, and `UiModule` HTTP/`@JsonService`
  wiring against `upstream/main`.
- Confirmed former `CONTRIBUTING.md` architecture HTML paths are absent from
  `main` (`git ls-tree` on `docs/`).
- Docs-only change; no Maven/UI build required for this PR.

## Follow-ups (optional)

- Optionally add a single row in the root `README.md` Documentation table
  pointing at `docs/product-map.md`.
- Deeper pages later if needed (`common`/`common2`, webdriver-tests matrix).
