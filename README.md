<img src="https://s.gravatar.com/avatar/988492aef73921a1ddb4741059390dde?s=70" alt="Glowroot Logo"> Glowroot &nbsp;&nbsp; [![Build Status](https://travis-ci.org/glowroot/glowroot.png?branch=master)](https://travis-ci.org/glowroot/glowroot)
=========

## Quick start

1. Download and unzip the [latest snapshot](https://glowroot.s3.amazonaws.com/snapshots/latest/glowroot-dist.zip)
2. Add `-javaagent:path/to/glowroot.jar` to your application's JVM arguments
3. Start your application
4. Connect to http://localhost:4000

## Questions, bugs, feature requests

Post 'em all to the [GitHub issue tracker](https://github.com/glowroot/glowroot/issues).

## Contributing

#### How to build it

Glowroot uses [Bower](http://bower.io) and [Grunt](http://gruntjs.com) to build its web assets (dependency management, Javascript concat/minify, LESS compile/minify, AngularJS template concat/minify, asset revving and more).

To install Bower and Grunt, first install [Node.js](http://nodejs.org), then install Bower and Grunt from the command line:

    npm install -g bower grunt-cli

From now on, building is easy:

    mvn clean install

Binary and source distributions are built under package/target.

#### How to hack on it

Run org.glowroot.testing.ui.UiTestingMain under a debugger inside your favorite IDE. It starts Glowroot and generates a variety of sample traces to give the UI something to display and to help with manual testing. Connect your browser to http://localhost:4001.

If you are working on the UI, you either need to run 'grunt' to re-build the web assets after each change, or (better) run:

    grunt serve

and connect your browser to http://localhost:9000.  **Note:** 'grunt' and 'grunt serve' should be run from the [core](core) subdirectory.

'grunt serve' serves up the Glowroot web assets to the browser without the concat/minify/rev step, which makes testing/debugging much easier. It reverse proxies non- static resource requests to http://localhost:4001 to be handled by Glowroot. It also watches for changes to the files and performs live-reload of the assets inside the browser.

#### How to test it

All automated tests are run during the maven build:

    mvn test

They can also be found in the following locations and run as standard JUnit tests inside your favorite IDE:

* Unit tests are under [core/src/test/java](core/src/test/java)
* Integration tests are under [integration-tests/src/test/java](integration-tests/src/test/java)
* WebDriver tests are under [webdriver-tests/src/test/java](webdriver-tests/src/test/java)
* Servlet plugin tests are under [plugins/servlet-plugin/src/test/java](plugins/servlet-plugin/src/test/java)
* Jdbc plugin tests are under [plugins/jdbc-plugin/src/test/java](plugins/jdbc-plugin/src/test/java)

## Code quality

[SonarQube](http://www.sonarqube.org) is used to check Java coding conventions, code coverage, duplicate code, package cycles and much more. It is run as part of every Travis CI build (see the job with TARGET=sonarqube) and the analysis is reported to [http://sonarqube.glowroot.org](http://sonarqube.glowroot.org).

[Checker Framework](http://types.cs.washington.edu/checker-framework/) is used to completely eradicate null pointer exceptions. It is run as part of every Travis CI build (see the job with TARGET=checker) and any violation fails the overall Travis CI build.

[JSHint](http://www.jshint.com) is used for basic Javascript coding conventions. It is fast, so it runs on every maven build (both local and Travis CI builds) and any violation fails the build.

## License

Glowroot source code is licensed under the Apache License, Version 2.0.

See [Third Party Software](https://github.com/glowroot/glowroot/wiki/Third-Party-Software) for license detail of third party software included in the binary distribution.
