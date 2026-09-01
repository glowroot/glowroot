# glowroot-ops — install

## 1. Customize

```bash
cp ORG-CONTEXT.md.template ORG-CONTEXT.md
```

Fill every `{{PLACEHOLDER}}` with your org, apps, paths, and JDK.

## 2. Install skill

**One-liner (any coding agent):**

```text
Install the /glowroot-ops skill globally from https://github.com/glowroot/glowroot/tree/main/skills/glowroot-ops
```

**CLI:**

```sh
npx skills add glowroot/glowroot --skill glowroot-ops --global --yes
```

**Local clone:**

```sh
npx skills add ./skills/glowroot-ops --skill glowroot-ops --global --yes --copy
```

**Scripts:** `./install.ps1` (Windows) or `./install.sh` (macOS/Linux)

## Any tool (no install)

Copy `PROMPT.md` into chat. Attach `SKILL.md` + `wiki-index.md` + your `ORG-CONTEXT.md` if you have one.

## Per tool

| Tool | Global path |
|------|-------------|
| Cursor | `~/.cursor/skills/glowroot-ops/` + `/glowroot-ops` command |
| Claude Code | `~/.claude/skills/glowroot-ops/` |
| Codex / Copilot | `~/.agents/skills/glowroot-ops/` |

See `integrations/cursor.md` for Cursor-only details.

## Team share

Zip the folder (include your filled `ORG-CONTEXT.md` for internal teams, or template only for public share).

## Update

Re-run install script or `npx skills add` after pulling changes. Re-merge `ORG-CONTEXT.md` if you customized it.
