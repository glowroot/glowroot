# Regenerate issue-map dashboard

Typical use after a triage batch (requires [`gh`](https://cli.github.com/) for `-Fetch`):

```powershell
# First time / full refresh from GitHub (~40s) + rebuild
.\docs\issue-map\regenerate-dashboard.ps1 -Fetch

# Rebuild only from issues-ndjson.txt + triaged-ids.json
.\docs\issue-map\regenerate-dashboard.ps1

# Add recently commented IDs and rebuild
.\docs\issue-map\regenerate-dashboard.ps1 -AddIds 631,633

# Open in the browser
.\docs\issue-map\regenerate-dashboard.ps1 -OpenBrowser
```

Parameters:
- `-AddIds` / `-AddIdsCsv '631,633'` — merge into `triaged-ids.json`
- `-Fetch` — `gh issue list --state all` → rewrites `issues-ndjson.txt` (gitignored locally if you prefer; not required in the repo)
- `-OpenBrowser` — opens `index.html`
- `-SkipCsv` — do not rewrite `open-issues.csv`

Notes:
- Do not use a variable `$open` in the script — in PowerShell it collides with `-Open` (case-insensitive).
- `gh --json comments` returns comment objects: the script stores/uses only the **count**.
- `issues-ndjson.txt` is produced by `-Fetch` and is intentionally not committed (large).
