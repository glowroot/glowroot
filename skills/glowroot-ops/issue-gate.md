# Issue gate — checklist before suggesting GitHub Issue

This skill **never opens issues**. It suggests a verdict only after the checklist is satisfied.

**Rule:** read the relevant **wiki page** before assigning any verdict.

## Mandatory fields

Collect (or ask for) before `possible-bug`:

```
□ Glowroot version     exact tag (e.g. v0.14.8-beta.4) — not "latest"
□ Mode                 embedded | central (+ Cassandra version if central)
□ JDK version          e.g. 8, 17
□ Application server   Tomcat 9 | Spring Boot embedded | other
□ OS                   Windows | Linux | Mac
□ -javaagent placement before -jar (if executable JAR)
□ Other agents on JVM  Elastic APM, other -javaagent, JRebel, etc.
□ multi.dir + agent.id (if multiple JVMs on same machine)
□ Disabled plugins     any plugin jars removed?
□ Log: "UI listening on …"  present | absent
□ Pattern match        searched TRIAGE-KNOWLEDGE / triage-patterns-dev.md
□ Wiki page read       URL of page consulted
```

## Verdicts (suggestion only)

| Verdict | When |
|---------|------|
| `wiki-only` | Answer is in wiki; user missed it |
| `expected-behavior` | Plugin gap or documented limitation |
| `no-issue` | Misconfiguration / support question |
| `discussion` | Uncertain, how-to, idea, or incomplete data → **Q&A or Ideas** |
| `needs-repro` | Looks like bug but missing repro steps |
| `possible-bug` | Checklist complete + reproducible + not in known patterns |

## Redirect rules

| Situation | Route |
|-----------|-------|
| How-to / config | Wiki + `no-issue` or `wiki-only` |
| Idea / UI wish | Discussion → **Ideas** |
| Uncertain after wiki + patterns | Discussion → **Q&A** — **not Issue** |
| Support masquerading as bug | `no-issue` + wiki link |
| Closed issue pattern reappears | Know-how only — do not suggest reopen |

## When NOT Glowroot

Ask whether symptoms exist **without** `-javaagent`. If yes → likely app/infrastructure, not agent.

Examples to probe:
- App fails to start even without javaagent
- Database down (Glowroot would still start; app may not)
- Port conflict unrelated to Glowroot UI

If unsure → `discussion`, not `possible-bug`.

## Suggested Discussion opener (template)

```markdown
**Glowroot version:** v0.14.x
**Mode:** embedded
**JDK / server:** Java 17, Tomcat 9
**Problem:** [one paragraph]
**Steps tried:** [from wiki checklist]
**Logs:** [UI listening line / stack trace snippet]
```

## Logs to request when stuck

- JVM startup through first request
- Line containing `Glowroot` or `UI listening on`
- Full `Java args:` line (redact secrets)
- If Central: agent log + Central log around connection errors
