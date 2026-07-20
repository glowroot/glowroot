# Glowroot

[![Latest release](https://img.shields.io/github/v/release/glowroot/glowroot?display_name=tag)](https://github.com/glowroot/glowroot/releases/latest)
[![Build](https://github.com/glowroot/glowroot/actions/workflows/build.yml/badge.svg)](https://github.com/glowroot/glowroot/actions/workflows/build.yml)
[![License](https://img.shields.io/github/license/glowroot/glowroot)](https://github.com/glowroot/glowroot/blob/main/LICENSE)
[![Java](https://img.shields.io/badge/Java-8%2B%20agent%20%7C%2017%2B%20central-blue)](https://github.com/glowroot/glowroot#requirements)

**Easy to use, very low overhead, open source Java APM.**

- **(Very) easy to use** — drop in a `-javaagent`, open the UI, see traces and profiles
- **Very (very) low overhead** — tuned with extensive microbenchmarking
- **Embedded or central** — local H2 collector out of the box, or scale out with a central collector

**Website:** [glowroot.org](https://glowroot.org/) ·
**Live demo:** [demo.glowroot.org](https://demo.glowroot.org/) ·
**Features:** [glowroot.org/features](https://glowroot.org/features.html) ·
**Releases:** [GitHub Releases](https://github.com/glowroot/glowroot/releases)

## Features

- Trace capture for slow requests and errors
- Continuous profiling (with filtering) and flame graphs
- Response time breakdown and percentile charts
- SQL and service-call capture with aggregation
- MBean attribute capture and charts
- Configurable alerting and historical rollups
- Full support for async requests that span multiple threads
- Responsive UI (including mobile)
- Optional central collector

See the full list and instrumentation coverage on the [features](https://glowroot.org/features.html) and [instrumentation](https://glowroot.org/instrumentation.html) pages.

## Quick start

1. Download and unzip the latest agent distribution:
   [glowroot-0.14.7-dist.zip](https://github.com/glowroot/glowroot/releases/download/v0.14.7/glowroot-0.14.7-dist.zip)
   (or pick another build from [Releases](https://github.com/glowroot/glowroot/releases))
2. Add `-javaagent:path/to/glowroot.jar` to your application's JVM args
   ([where are my application server's JVM args?](https://github.com/glowroot/glowroot/wiki/Where-are-my-application-server's-JVM-args%3F))
3. Ensure the user that runs the JVM can write to the glowroot folder
4. Start your application
5. Open http://localhost:4000

Optional post-install steps:
[Agent Installation (Embedded Collector)](https://github.com/glowroot/glowroot/wiki/Agent-Installation-(with-Embedded-Collector)).

## Support

Please use the right channel so maintainers can help efficiently:

| Need | Where |
|------|--------|
| Questions, how-to, configuration doubts, “is this expected?” | **[GitHub Discussions](https://github.com/glowroot/glowroot/discussions)** (prefer the **Q&A** category) |
| Reproducible bugs or concrete feature requests | **[GitHub Issues](https://github.com/glowroot/glowroot/issues)** |
| Release announcements / community | [Google Group](https://groups.google.com/forum/#!forum/glowroot) · [@glowroot](https://twitter.com/glowroot) |

**Please do not open Issues for general questions** (for example empty “it doesn’t work” reports without steps, version, or logs). Start a Discussion instead — Issues stay focused on actionable defects and features.

## Requirements

* Java 8+ for the agent
* Java 17+ for glowroot-central

## For contributors

### Building

```bash
mvn clean install
```

Binary distribution is built under `agent/dist/target`.

Building requires Java 11+ and Maven 3.8.0+.

### Contributing

Glowroot uses [Immutables](https://immutables.github.io) annotation processing to eliminate maintenance on lots of boilerplate code. If you are using Eclipse, this requires installing the [m2e-apt](https://github.com/jbosstools/m2e-apt) plugin and changing Window > Preferences > Maven > Annotation Processing to "Automatically configure JDT APT".

To work on the UI, run `org.glowroot.ui.sandbox.UiSandboxMain` under a debugger inside your favorite IDE. This starts Glowroot and generates a variety of sample traces to give the UI something to display. Connect your browser to http://localhost:4000.

Glowroot uses [Bower](http://bower.io) and [Grunt](http://gruntjs.com) to build its web assets (dependency management, Javascript concat/minify, LESS compile/uncss/minify, AngularJS template concat/minify, asset revving and more). The first time you run `mvn clean install`, Node, Bower and Grunt are installed locally under the `ui` directory (thanks to the [frontend-maven-plugin](https://github.com/eirslett/frontend-maven-plugin)).

If you are modifying web assets, either run grunt to rebuild after each change, or (better) run `./grunt serve` from the `ui` directory and connect your browser to http://localhost:9000.

`./grunt serve` serves up the Glowroot web assets without the concat/minify/rev step (easier testing/debugging). It reverse-proxies non-static requests to http://localhost:4000 and live-reloads assets in the browser.

`./grunt serve:demo` does the same, except it reverse-proxies to [https://demo.glowroot.org](https://demo.glowroot.org) instead of http://localhost:4000.

### Integration tests

Integration tests run during Maven's standard `integration-test` lifecycle phase.

The Glowroot agent has an [integration test harness](agent/it-harness) which makes it easy to run sample application code and then validate the data captured by the agent. The harness can run tests with a custom weaving class loader (convenient in an IDE) or by spawning a JVM with `-javaagent` (closer to production).

Browser-based integration tests use WebDriver (Firefox by default).

### Microbenchmarks

Microbenchmarks use [JMH](http://openjdk.java.net/projects/code-tools/jmh/). From [agent/benchmarks](agent/benchmarks):

```bash
mvn clean package
java -jar target/benchmarks.jar -jvmArgs -javaagent:path/to/glowroot.jar
```

### Code quality

[SonarQube](http://www.sonarqube.org) analysis:
[sonarcloud.io](https://sonarcloud.io/dashboard?id=org.glowroot%3Aglowroot-parent).

[Checker Framework](http://types.cs.washington.edu/checker-framework/) Nullness Checker is run in CI; violations fail the build.

### Dependency shading

All third party Java libraries used by the agent are shaded under the `org.glowroot.agent.shaded` package to avoid jar version conflicts with the application being monitored.

## License

Glowroot source code is licensed under the Apache License, Version 2.0.

See [Third Party Software](https://github.com/glowroot/glowroot/wiki/Third-Party-Software) for license detail of third party software included in the binary distribution.

---

[![Star History Chart](https://api.star-history.com/svg?repos=glowroot/glowroot&type=Date)](https://www.star-history.com/#glowroot/glowroot&Date)
