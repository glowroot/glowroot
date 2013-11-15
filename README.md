<img src="https://secure.gravatar.com/avatar/ab0f1c8f702263d8c954314b231d91ce?s=70" alt="Informant Logo"> Informant &nbsp;&nbsp; [![Build Status](https://travis-ci.org/informant/informant.png?branch=master)](https://travis-ci.org/informant/informant)
=========

## Quick start

1. Download the latest [informant.jar](https://s3.amazonaws.com/travis-ci.informant.io/snapshots/latest/informant.jar) and place it in an empty directory of your choosing
2. Add `-javaagent:informant.jar` to your application's JVM arguments
3. Start your application
4. Connect to http://localhost:4000

## Questions, bugs, feature requests

Post 'em all to the [GitHub issue tracker](https://github.com/informant/informant/issues).

## Contributing

#### How to build it

Informant uses [Grunt](http://gruntjs.com) to build its web assets (Javascript concat/minify, SASS compile/minify, AngularJS template concat/minify, asset revving and more).

To install Grunt, first install [Node.js](http://nodejs.org), then install Grunt from the command line:

    npm install -g grunt-cli

From now on, building is easy:

    mvn clean install

Binary and source distributions are built under package/target.

#### How to hack on it

Run io.informant.testing.ui.UiTestingMain under a debugger inside your favorite IDE. It starts Informant and generates a variety of sample traces to give the UI something to display and to help with manual testing. Connect your browser to http://localhost:4001.

If you are working on the UI, you either need to run 'grunt' to re-build the web assets after each change, or (better) run:

    grunt server

and connect your browser to http://localhost:9000.  **Note:** 'grunt' and 'grunt server' should be run from the [core](core) subdirectory.

'grunt server' serves up the Informant web assets to the browser without the concat/minify/rev step, which makes testing/debugging much easier. It reverse proxies non- static resource requests to http://localhost:4001 to be handled by Informant. It also watches for changes to the files and performs live-reload of the assets inside the browser.

#### How to test it

All automated tests are run during the maven build:

    mvn test

They can also be found in the following locations and run as standard JUnit tests inside your favorite IDE:

* Unit tests are under [core/src/test/java](core/src/test/java)
* Integration tests are under [integration-tests/src/test/java](integration-tests/src/test/java)
* WebDriver tests are under [webdriver-tests/src/test/java](webdriver-tests/src/test/java)
* Servlet plugin tests are under [servlet-plugin/src/test/java](servlet-plugin/src/test/java)
* Jdbc plugin tests are under [jdbc-plugin/src/test/java](jdbc-plugin/src/test/java)

## License

Informant source code is licensed under the Apache License, Version 2.0.

Informant's binary distribution is released under the Apache License, Version 2.0. The binary distribution includes [third party web resources](https://github.com/informant/informant/wiki/Third-Party-Web-Resources) in _source form_ and [third party Java libraries](https://github.com/informant/informant/wiki/Third-Party-Java-Libraries) in _binary form_.

Informant's source distribution (which includes both [third party web resources](https://github.com/informant/informant/wiki/Third-Party-Web-Resources) and [third party Java libraries](https://github.com/informant/informant/wiki/Third-Party-Java-Libraries) in _source form_) is released under a mixed license, with each component's source governed by its respective license.
