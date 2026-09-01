---
name: glowroot-ops
description: >-
  Portable Glowroot analysis assistant. Invoke explicitly (not auto). Fetches
  live wiki from github.com/glowroot/glowroot/wiki. Works in Cursor, Claude Code,
  Copilot, ChatGPT, or any agent that can read files and fetch URLs.
disable-model-invocation: true
---

# Glowroot Ops

Portable skill — see `INSTALL.md`. Invoke via slash command, skill name, or `PROMPT.md`.

## Limits

- Analysis only — no config edits, no issues, no deploy commands
- No @mentions · upstream links only · match the user's language

## Wiki (live — do not guess)

**Source of truth:** https://github.com/glowroot/glowroot/wiki

Before any verdict:

1. Pick URL from `wiki-index.md`
2. Fetch with browser / HTTP / curl (whatever the environment provides)
3. Answer from fetched content + local patterns
4. If fetch fails, say so and link the URL

Fetch **1–2 pages max** per question.

## Organization context

If `ORG-CONTEXT.md` exists, use it for install paths, stack, and mode defaults.  
If missing, tell the user to copy `ORG-CONTEXT.md.template` and fill placeholders.

## Workflow

```
classify     → decision-tree.md (if needed)
wiki         → live fetch from github.com/glowroot/glowroot/wiki
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
## Verdict (suggestion): wiki-only | expected-behavior | no-issue | discussion | needs-repro | possible-bug
```

## On-demand files

| File | When |
|------|------|
| `ORG-CONTEXT.md` | Org-specific paths and stack |
| `wiki-index.md` | Wiki URL to fetch |
| `PROMPT.md` | Copy-paste invoke |
| `INSTALL.md` | Per-tool setup |
| `dev-setup.md` | Generic install |
| `decision-tree.md` | Classify question |
| `triage-patterns-dev.md` | Dev FAQ |
| `issue-gate.md` | Pre-verdict checklist |
| `glossary.md` | UI terms |
| `comparisons.md` | vs alternatives |
| `session-feedback.md` | Team onboarding session |
