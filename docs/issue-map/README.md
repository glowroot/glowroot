# Issue backlog knowledge map

Shared FAQ patterns and a small HTML dashboard for Glowroot’s open Issues backlog.
See discussion [#1197](https://github.com/glowroot/glowroot/discussions/1197).

| File | Purpose |
|------|---------|
| **[TRIAGE-KNOWLEDGE.md](TRIAGE-KNOWLEDGE.md)** | Recurring support/FAQ patterns, related issue numbers, useful code paths |
| **[index.html](index.html)** | Offline dashboard (open in a browser) — Easy / work queues + already-answered IDs |
| [data.json](data.json) | Snapshot embedded into `index.html` |
| [triaged-ids.json](triaged-ids.json) | Issue numbers already triaged (excluded from work queues) |
| [open-issues.csv](open-issues.csv) | Open issues still in the work queues |
| [regenerate-dashboard.ps1](regenerate-dashboard.ps1) | Refresh snapshot from GitHub (`gh`) |
| [README-regen.md](README-regen.md) | Regen flags |

## Quick start

1. Read **TRIAGE-KNOWLEDGE.md** before re-exploring a familiar how-to / install / Cassandra question.
2. Open **index.html** locally (double-click / `file://…`) to browse queues.
3. To refresh from GitHub (needs [`gh`](https://cli.github.com/) auth):

```powershell
.\docs\issue-map\regenerate-dashboard.ps1 -Fetch
```

## Triage protocol (short)

| Difficulty | Comment length | Redirect support? | Suggest close? |
|------------|----------------|-------------------|----------------|
| **Easy** (how-to / FAQ / install) | 3–6 lines | wiki / [Discussions](https://github.com/glowroot/glowroot/discussions) / [Google group](https://groups.google.com/forum/#!forum/glowroot) | yes — ping `@nowheresly` |
| **Medium** | one structured paragraph | if support | close or keep + label |
| **Hard** | as needed | no if real bug | keep / `enhancement` |

Upstream comments in English. Tag `@nowheresly` with the asked action (close / label / needs-info / keep open).
