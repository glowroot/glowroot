# Cursor integration (optional)

Cursor is one supported host — not required.

## Install

```powershell
$src = "C:\path\to\doc\skills\glowroot-ops"
Copy-Item -Recurse -Force "$src\*" "$env:USERPROFILE\.cursor\skills\glowroot-ops\"
Copy-Item -Force "$src\integrations\cursor-command.md" "$env:USERPROFILE\.cursor\commands\glowroot-ops.md"
```

## Invoke

```
/glowroot-ops <your question>
```

`disable-model-invocation: true` in `SKILL.md` prevents auto-load — slash command only.

## Other tools

See [../INSTALL.md](../INSTALL.md).
