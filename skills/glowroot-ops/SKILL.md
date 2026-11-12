---
name: glowroot-ops
description: >-
  Portable Glowroot analysis assistant. Invoke explicitly (not auto). Fetches
  live wiki from github.com/glowroot/glowroot/wiki; then in-repo docs/ notes
  when listed in docs-index.md. Works in Cursor, Claude Code, Copilot, ChatGPT,
  or any agent that can read files and fetch URLs.
disable-model-invocation: true
---

# Glowroot Ops

Portable skill — see `INSTALL.md`. Invoke via slash command, skill name, or `PROMPT.md`.

## Limits

- Analysis only — no config edits, no issues, no deploy commands
- No @mentions · upstream links only · match the user's language

## Sources (do not guess)

1. **Wiki (primary):** https://github.com/glowroot/glowroot/wiki  
   Pick URL from `wiki-index.md` → fetch (browser / HTTP / curl) → answer from content.  
   Fetch **1–2 wiki pages max** per question. If fetch fails, say so and link the URL.
2. **In-repo docs (second):** if topic is in `docs-index.md`, read that file  
   (clone: `docs/<file>`; skill-only: raw.githubusercontent.com URL in the index).  
   Do not invent docs text.
3. **Org / patterns:** `ORG-CONTEXT.md` if present; `triage-patterns-dev.md` / `decision-tree.md` as needed.

## Organization context

If `ORG-CONTEXT.md` exists, use it for install paths, stack, and mode defaults.  
If missing, tell the user to copy `ORG-CONTEXT.md.template` and fill placeholders.

## Workflow

```
classify     → decision-tree.md (if needed)
wiki         → live fetch from github.com/glowroot/glowroot/wiki
docs         → docs-index.md → docs/<file> or raw URL (if mapped)
org          → ORG-CONTEXT.md (if present)
local        → triage-patterns-dev.md
trace export → brief orientation → dedicated trace analysis if available
verdict      → issue-gate.md → suggest only
uncertain    → GitHub Discussion Q&A
```

## Output

```
## Diagnosis
## Likely cause (+ issue # if known)
## Actions (read-only)
## Wiki (URLs fetched)
## Docs (paths/URLs read)
## Verdict (suggestion): wiki-only | expected-behavior | no-issue | discussion | needs-repro | possible-bug
```

## On-demand files

| File | When |
|------|------|
| `ORG-CONTEXT.md` | Org-specific paths and stack |
| `wiki-index.md` | Wiki URL to fetch |
| `docs-index.md` | In-repo `docs/` note (after wiki) |
| `PROMPT.md` | Copy-paste invoke |
| `INSTALL.md` | Per-tool setup |
| `dev-setup.md` | Generic install |
| `decision-tree.md` | Classify question |
| `triage-patterns-dev.md` | Dev FAQ |
| `issue-gate.md` | Pre-verdict checklist |
| `glossary.md` | UI terms |
| `comparisons.md` | vs alternatives |
| `session-feedback.md` | Team onboarding session |
