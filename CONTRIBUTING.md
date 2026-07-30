# Contributing to Glowroot

Thank you for your interest in contributing. This file covers building from source, UI development, tests, and project conventions.

## Building

```bash
mvn clean install
```

Binary distribution is built under `agent/dist/target`.

Building requires Java 11+ and Maven 3.8.0+.

## Development setup

Glowroot uses [Immutables](https://immutables.github.io) annotation processing to eliminate maintenance on lots of boilerplate code. If you are using Eclipse, this requires installing the [m2e-apt](https://github.com/jbosstools/m2e-apt) plugin and changing Window > Preferences > Maven > Annotation Processing to "Automatically configure JDT APT".

To work on the UI, run `org.glowroot.ui.sandbox.UiSandboxMain` under a debugger inside your favorite IDE. This starts Glowroot and generates a variety of sample traces to give the UI something to display. Connect your browser to http://localhost:4000.

Glowroot uses [Bower](http://bower.io) and [Grunt](http://gruntjs.com) to build its web assets (dependency management, Javascript concat/minify, LESS compile/uncss/minify, AngularJS template concat/minify, asset revving and more). The first time you run `mvn clean install`, Node, Bower and Grunt are installed locally under the `ui` directory (thanks to the [frontend-maven-plugin](https://github.com/eirslett/frontend-maven-plugin)).

If you are modifying web assets, either run grunt to rebuild after each change, or (better) run `./grunt serve` from the `ui` directory and connect your browser to http://localhost:9000.

`./grunt serve` serves up the Glowroot web assets without the concat/minify/rev step (easier testing/debugging). It reverse-proxies non-static requests to http://localhost:4000 and live-reloads assets in the browser.

`./grunt serve:demo` does the same, except it reverse-proxies to [https://demo.glowroot.org](https://demo.glowroot.org) instead of http://localhost:4000.

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

[SonarQube](http://www.sonarqube.org) analysis:
[sonarcloud.io](https://sonarcloud.io/dashboard?id=org.glowroot%3Aglowroot-parent).

[Checker Framework](http://types.cs.washington.edu/checker-framework/) Nullness Checker is run in CI; violations fail the build.

## Dependency shading

All third party Java libraries used by the agent are shaded under the `org.glowroot.agent.shaded` package to avoid jar version conflicts with the application being monitored.

## Getting help

- **Questions about contributing or building:** [GitHub Discussions → Q&A](https://github.com/glowroot/glowroot/discussions/categories/q-a)
- **Ideas for improvements:** [GitHub Discussions → Ideas](https://github.com/glowroot/glowroot/discussions/categories/ideas)
- **Bugs with a reproducer:** [GitHub Issues](https://github.com/glowroot/glowroot/issues)
