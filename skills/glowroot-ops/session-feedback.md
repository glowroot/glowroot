# Team session — feedback flywheel

Goal: collect clarity and friction feedback — not a mass hands-on install lab.

## Format (2 hours)

| Block | Duration | Focus |
|-------|----------|-------|
| Demo (presenter machine) | 25 min | Real app with embedded agent |
| UI tour + questions | 30 min | Transactions, trace detail, Queries |
| Structured feedback | 40 min | Form below |
| Optional homework | 10 min | Wiki links + `PROMPT.md` + install one-liner |
| Wrap | 15 min | Live skill demo (one sample question) |

Watch for: repeated questions, where people get lost.

## Success metrics

- Few specific questions (quality over quantity)
- 3+ new lines for `triage-patterns-dev.md` or `ORG-CONTEXT.md`
- 1+ wiki/doc gap identified
- No pressure to open GitHub issues in the room

## After the session

1. Update `triage-patterns-dev.md` from feedback
2. Send team: wiki links + `INSTALL.md` + `PROMPT.md`
3. Optional: upstream wiki or Discussion if pattern is universal

## Feedback form options

| Option | Notes |
|--------|-------|
| Google Form | Anonymous, exports to Sheets |
| Microsoft Forms | M365 / Teams |
| Shared spreadsheet | Offline-friendly |
| Sticky notes | Transcribe after |

### Suggested fields

| # | Field |
|---|-------|
| 1 | Category: Install / UI / Expected-behavior / Docs-gap / Bug-suspect / Other |
| 2 | What confused you? |
| 3 | What did you expect? |
| 4 | Where did you look in the UI? |
| 5 | JDK + how you start the app locally |
| 6 | Would you use this in daily dev? why / why not |

## Live skill demo

Use whatever AI tool the room has:

```
/glowroot-ops Service Calls empty on @Service — is that normal?
```

Expected: `expected-behavior`, `#746`, wiki Plugin coverage gaps (fetched live).

## Share the skill pack

Zip `glowroot-ops/` or point to git path. See `INSTALL.md`.

Homework: `PROMPT.md` works without installing anything.
