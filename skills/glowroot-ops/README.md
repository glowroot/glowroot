# Glowroot Ops

Portable AI assistant for [Glowroot](https://github.com/glowroot/glowroot) — install, UI, traces, JVM metrics. Fetches live wiki from GitHub.

## Customize first

```bash
cp ORG-CONTEXT.md.template ORG-CONTEXT.md
# edit placeholders for your org, paths, and stack
```

## Install

Paste into any coding agent:

```text
Install the /glowroot-ops skill globally from https://github.com/glowroot/glowroot/tree/main/skills/glowroot-ops
```

```sh
npx skills add glowroot/glowroot --skill glowroot-ops --global --yes
```

From a local clone:

```sh
npx skills add ./skills/glowroot-ops --skill glowroot-ops --global --yes --copy
```

```powershell
.\skills\glowroot-ops\install.ps1
```

## Use

| Tool | Invoke |
|------|--------|
| Cursor | `/glowroot-ops <question>` |
| Claude Code | skill `glowroot-ops` |
| Any chat | paste `PROMPT.md` |

## Wiki

https://github.com/glowroot/glowroot/wiki — fetched live, not embedded.

## Files

| File | Purpose |
|------|---------|
| `ORG-CONTEXT.md.template` | Copy → `ORG-CONTEXT.md` with your env |
| `SKILL.md` | Core workflow |
| `PROMPT.md` | Copy-paste invoke |
| `INSTALL.md` | Per-tool setup |

Apache 2.0 (same as Glowroot).
