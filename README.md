Glowroot &nbsp;&nbsp; [![Build Status](https://travis-ci.org/glowroot/glowroot.png?branch=master)](https://travis-ci.org/glowroot/glowroot)  [![Code Coverage](https://img.shields.io/sonar/http/sonar.glowroot.org/org.glowroot:glowroot-parent/coverage.svg)](https://sonar.glowroot.org/dashboard/index?id=org.glowroot%3Aglowroot-parent)
=========

## Quick start

1. Download and unzip [glowroot-dist.zip](https://glowroot.s3.amazonaws.com/snapshots/latest/glowroot-dist.zip)
2. Add `-javaagent:path/to/glowroot.jar` to your application's JVM arguments
3. Start your application
4. Connect to http://localhost:4000

Glowroot supports Java 6+.

## Core plugins

Included in this repo and in glowroot-dist.zip:

 * Servlets
 * Jdbc
 * Loggers ([Slf4j](http://www.slf4j.org), [Log4j](http://logging.apache.org/log4j/1.2/), [Apache Commons Logging](http://commons.apache.org/proper/commons-logging/))
 * HTTP clients ([Apache HttpClient](https://hc.apache.org/httpcomponents-client-ga/), [AsyncHttpClient](https://github.com/AsyncHttpClient/async-http-client))
 * Cassandra ([DataStax CQL driver](http://datastax.github.io/java-driver/))
 * Quartz Scheduler
 * JMS

## Questions, bugs, feature requests

 * [GitHub issue tracker](https://github.com/glowroot/glowroot/issues)
 * [Google group](https://groups.google.com/forum/#!forum/glowroot)

## Building

The usual:

    mvn clean install

Binary and source distributions are built under distribution/target.

Building requires Java 7+ (in order to perform [Immutables](https://immutables.github.io) annotation processing).

## Contributing

Glowroot uses [Immutables](https://immutables.github.io) annotation processing to eliminate maintenance on lots of boilerplate code. If you are using Eclipse, this requires installing the [m2e-apt](https://github.com/jbosstools/m2e-apt) plugin and changing Window > Preferences > Maven > Annotation Processing to "Automatically configure JDT APT".

To work on the UI, run org.glowroot.sandbox.ui.UiSandboxMain under a debugger inside your favorite IDE. This starts Glowroot and generates a variety of sample traces to give the UI something to display. Connect your browser to http://localhost:4000.

Also, Glowroot uses [Bower](http://bower.io) and [Grunt](http://gruntjs.com) to build its web assets (dependency management, Javascript concat/minify, LESS compile/uncss/minify, AngularJS template concat/minify, asset revving and more). The first time you run `mvn clean install`, Node, Bower and Grunt are installed locally under `<repo>/core/node` (thanks to the [frontend-maven-plugin](https://github.com/eirslett/frontend-maven-plugin)).

If you are modifying web assets, you either need to run grunt to re-build them after each change, or (better) run `grunt serve` from the `<repo>/core` directory and connect your browser to http://localhost:9000.

`grunt serve` serves up the Glowroot web assets to the browser without the concat/minify/rev step, which makes testing/debugging much easier. It reverse proxies non- static resource requests to http://localhost:4000 to be handled by Glowroot. It also watches for changes to the files and performs live-reload of web assets inside the browser.

## Microbenchmarks

Microbenchmarks are written using the excellent [JMH](http://openjdk.java.net/projects/code-tools/jmh/) benchmark harness, and can be built and run using

    mvn clean package
    java -jar target/benchmarks.jar -jvmArgs -javaagent:path/to/glowroot.jar

from the following locations:

* [testing/microbenchmarks](testing/microbenchmarks)
* [plugins/servlet-plugin-microbenchmarks](plugins/servlet-plugin-microbenchmarks)
* [plugins/jdbc-plugin-microbenchmarks](plugins/jdbc-plugin-microbenchmarks)

## Overhead

Monitoring overhead depends on many factors, but is generally in the low microseconds per transaction. See the [glowroot-benchmark](https://github.com/glowroot/glowroot-benchmark) repository for a concrete benchmark and results.

## Code quality

[SonarQube](http://www.sonarqube.org) is used to check Java coding conventions, code coverage, duplicate code, package cycles and much more. It is run as part of every Travis CI build (see the job with TARGET=sonar) and the analysis is reported to [https://sonar.glowroot.org](https://sonar.glowroot.org).

[Checker Framework](http://types.cs.washington.edu/checker-framework/) is used to eliminate fear of *null* with its rigorous [Nullness Checker](http://types.cs.washington.edu/checker-framework/current/checker-framework-manual.html#nullness-checker). It is run as part of every Travis CI build (see the job with TARGET=checker) and any violation fails the build.

## License

Glowroot source code is licensed under the Apache License, Version 2.0.

See [Third Party Software](https://github.com/glowroot/glowroot/wiki/Third-Party-Software) for license detail of third party software included in the binary distribution.
