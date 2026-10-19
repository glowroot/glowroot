# Contributing to Glowroot

Thank you for your interest in contributing. This file covers orientation,
building from source, UI development, tests, pull-request docs, and project
conventions.

## How Glowroot fits together

If you are new to the tree, start with the **in-repo maps** under [`docs/`](docs/README.md)
(readable on GitHub — Markdown + Mermaid):

| Doc | What it covers |
|-----|----------------|
| [Product map](docs/product-map.md) | Embedded vs Central, data path, entry points |
| [Repository layout](docs/repository-layout.md) | Top-level directories |
| [Agent modules](docs/agent-modules.md) | `agent/*` (APIs, core, shaded, dist) |
| [Agent plugins](docs/agent-plugins.md) | Plugin catalog and `glowroot.plugin.json` |
| [Central](docs/central.md) | gRPC, Cassandra `repo/`, util, live adapters |
| [Wire API](docs/wire-api.md) | Protobuf / gRPC contracts |
| [UI](docs/ui.md) | AngularJS SPA + `org.glowroot.ui` HTTP/JSON |

Same topics in wiki prose (modules, package layout, plugin basics):

- [For contributors](https://github.com/glowroot/glowroot/wiki/For-contributors)
- [Agent data path](https://github.com/glowroot/glowroot/wiki/Agent-data-path)
- [Embedded vs Central in code](https://github.com/glowroot/glowroot/wiki/Embedded-vs-Central-in-code)
- [Plugin and Pointcut basics](https://github.com/glowroot/glowroot/wiki/Plugin-and-Pointcut-basics)

Operator-facing UI docs (Plugins, Instrumentation, coverage gaps, transaction tabs, alerts, …) live under the wiki [Home](https://github.com/glowroot/glowroot/wiki) (UI / configuration + [Plugin coverage gaps](https://github.com/glowroot/glowroot/wiki/Plugin-coverage-gaps)).

## Building

```bash
mvn clean install
```

Binary distribution is built under `agent/dist/target`.

Building requires Java 11+ and Maven 3.8.0+. Central requires Java 17+.

## Development setup

Glowroot uses [Immutables](https://immutables.github.io) annotation processing to eliminate maintenance on lots of boilerplate code. If you are using Eclipse, this requires installing the [m2e-apt](https://github.com/jbosstools/m2e-apt) plugin and changing Window > Preferences > Maven > Annotation Processing to "Automatically configure JDT APT".

To work on the UI, run `org.glowroot.agent.ui.sandbox.UiSandboxMain` (module `agent/ui-sandbox`) under a debugger inside your favorite IDE. This starts Glowroot and generates a variety of sample traces to give the UI something to display. Connect your browser to http://localhost:4000.

Glowroot uses [Bower](http://bower.io) and [Grunt](http://gruntjs.com) to build its web assets (dependency management, Javascript concat/minify, LESS compile/uncss/minify, AngularJS template concat/minify, asset revving and more). The first time you run `mvn clean install`, Node, Bower and Grunt are installed locally under the `ui` directory (thanks to the [frontend-maven-plugin](https://github.com/eirslett/frontend-maven-plugin)).

If you are modifying web assets, either run grunt to rebuild after each change, or (better) run `./grunt serve` from the `ui` directory and connect your browser to http://localhost:9000. Do **not** use `-Dglowroot.ui.skip` when changing UI sources.

`./grunt serve` serves up the Glowroot web assets without the concat/minify/rev step (easier testing/debugging). It reverse-proxies non-static requests to http://localhost:4000 and live-reloads assets in the browser.

`./grunt serve:demo` does the same, except it reverse-proxies to `https://demo.glowroot.org` instead of http://localhost:4000.
That public demo host is currently unreachable ([#1102](https://github.com/glowroot/glowroot/issues/1102)); use `./grunt serve` against a local sandbox (`UiSandboxMain` on :4000) instead.
For a static look at the old UI, see the [Wayback snapshot](https://web.archive.org/web/20230325052222/https://demo.glowroot.org/).

## Integration tests

Integration tests run during Maven's standard `integration-test` lifecycle phase.

The Glowroot agent has an [integration test harness](agent/it-harness) which makes it easy to run sample application code and then validate the data captured by the agent. The harness can run tests with a custom weaving class loader (convenient in an IDE) or by spawning a JVM with `-javaagent` (closer to production).

Browser-based integration tests use WebDriver (Firefox by default).

## Microbenchmarks

Microbenchmarks use [JMH](http://openjdk.java.net/projects/code-tools/jmh/). From [agent/benchmarks](agent/benchmarks):

```bash
mvn clean package
java -jar target/benchmarks.jar -jvmArgs -javaagent:path/to/glowroot.jar
```

## Code quality

Pull requests run the [GitHub Actions Build workflow](https://github.com/glowroot/glowroot/actions/workflows/build.yml) — unit and integration tests across Java versions, shaded/unshaded agents, and `javaagent` vs local harness modes.

Optional local nullness check (Checker Framework), same as `.github/scripts/build.sh checker`:

```bash
.github/scripts/build.sh checker
```

(On Linux CI this is typically run under `xvfb-run`; see the script for details.)

## Dependency shading

All third party Java libraries used by the agent are shaded under the `org.glowroot.agent.shaded` package to avoid jar version conflicts with the application being monitored.

## Pull requests and change documentation

Every non-trivial fix or feature PR must include an in-repo note that explains
**what** changed, **why**, **how**, and **which choices** were made (and why),
so review and later maintenance stay cheap.

### What to add

1. Copy [docs/pr-change-template.md](docs/pr-change-template.md).
2. Save as `docs/changes/<short-slug>.md` (create `docs/changes/` if needed),
   **or** update a lasting topic page under `docs/` when the PR documents
   enduring behavior (e.g. health endpoints, upgrade notes).
3. Link that path in the PR description.

Sections to fill (see the template): What · Why · How · Choices · Verification.

### When you may skip

- Typos, comment-only, or formatting-only changes with **no** behavior, API,
  config, or operator-facing change.
- Pure refactors that do not change externally observable behavior **and** do
  not alter wire/storage contracts — still preferred to leave a short note if
  the refactor is large.

### PR description

Keep the GitHub PR body short and concrete. Point to the change note for
detail. Prefer titles like `[BUGFIX] …`, `[FEATURE] …`, `[DOCS] …` when that
matches the change.

If the change is Central-only or Embedded-only, say so (and test that mode).
If you touch `wire-api` protos, call out compatibility explicitly in the note.

## Getting help

- **In-repo maps:** [docs/README.md](docs/README.md)
- **Code / module orientation:** [For contributors](https://github.com/glowroot/glowroot/wiki/For-contributors) · [Plugin and Pointcut basics](https://github.com/glowroot/glowroot/wiki/Plugin-and-Pointcut-basics)
- **Questions about contributing or building:** [GitHub Discussions → Q&A](https://github.com/glowroot/glowroot/discussions/categories/q-a)
- **Ideas for improvements:** [GitHub Discussions → Ideas](https://github.com/glowroot/glowroot/discussions/categories/ideas)
- **Bugs with a reproducer:** [GitHub Issues](https://github.com/glowroot/glowroot/issues)
