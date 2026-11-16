# Trace modal layout (React `/modern`) — B+C mix

Date: 2026-11-16

## Goal

Rework the Trace modal so Summary / Breakdown / Detail are clear zones,
visually closer to classic Glowroot, without changing fetch/tree/sort logic.

## Decisions

- Multi-open detail sections (classic parity) — not accordion
- Link-style toggles for Entries / Query stats / Profiles (not beige buttons)
- Status as bold text (ACTIVE / PARTIAL / ERROR / ASYNC), not colored pills
- Attribute rows as label + value (classic rhythm)
- Out of scope: backend APIs, entry-tree algorithms, global theme

## Success

Manual: open a sandbox trace under `/modern/`; header readable; breakdown
tight; multiple detail sections can stay open; behavior unchanged.
