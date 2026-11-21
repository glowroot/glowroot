# In-repo docs — second source (after wiki)

Wiki stays primary. Use this index only when the topic has an in-repo note.

**Base (clone):** `docs/` at Glowroot repo root  
**Base (skill-only / no clone):**  
`https://raw.githubusercontent.com/glowroot/glowroot/main/docs/<file>`

Do not invent docs content. If local read and raw fetch both fail, say so and link the path.

## Ops-relevant notes

| Topic | File | Raw URL |
|-------|------|---------|
| Deploy modes / data path / entry points | `docs/product-map.md` | https://raw.githubusercontent.com/glowroot/glowroot/main/docs/product-map.md |
| HTTP `/health` vs `/readiness` (0.14.8+) | `docs/health-endpoints.md` | https://raw.githubusercontent.com/glowroot/glowroot/main/docs/health-endpoints.md |
| 0.14.8 upgrade (breaking / notable) | `docs/0.14.8-upgrade-notes.md` | https://raw.githubusercontent.com/glowroot/glowroot/main/docs/0.14.8-upgrade-notes.md |
| Embedded H2 1.x → 2.x (recreate path) | `docs/embedded-h2-upgrade.md` | https://raw.githubusercontent.com/glowroot/glowroot/main/docs/embedded-h2-upgrade.md |
| JVM thread “Allocated memory” column | `docs/jvm-thread-stats-allocated-memory.md` | https://raw.githubusercontent.com/glowroot/glowroot/main/docs/jvm-thread-stats-allocated-memory.md |

Index of all in-repo maps: `docs/README.md` (contributor-oriented; not required for ops answers).

## Out of scope for this skill

Do **not** pull these for operator Q&A unless the user is clearly hacking on the source tree:

- `docs/pr-change-template.md`, `docs/changes/*`
- `docs/wire-api.md`, `docs/agent-modules.md`, `docs/repository-layout.md`, `docs/ui.md`, `docs/central.md`, `docs/agent-plugins.md`
