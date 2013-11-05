<img src="https://secure.gravatar.com/avatar/ab0f1c8f702263d8c954314b231d91ce?s=70" alt="Informant Logo"> Informant
=========

Root cause performance and error monitoring

Supports JDK5 and later (dynamic pointcut reloading in JDK6 and later).

## Quick start

1. Download [informant.jar](https://oss.sonatype.org/content/repositories/snapshots/io/informant/informant-package/0.5-SNAPSHOT/informant-package-0.5-20131020.191732-2.jar) and place in an empty directory of your choosing
2. Add `-javaagent:informant.jar` to your application's JVM arguments
3. Start your application
4. Connect to `http://localhost:4000`

## Questions, bugs, feature requests

Post 'em all to the [GitHub issue tracker](https://github.com/informant/informant/issues).

## Contributing

#### How to build

Informant uses [Grunt](http://gruntjs.com) to build its web assets (Javascript concat/minify, SASS compile/minify, AngularJS template concat/minify, asset revving and more).

To install Grunt, first install [Node.js](http://nodejs.org).

Then install Grunt from the command line using the Node.js package manager:

    npm install -g grunt-cli

Now building is easy:

    mvn clean install

Binary and source distributions are built under package/target.

#### How to run automated tests

    mvn clean test

#### How to manually test/debug

Run `io.informant.testing.ui.UiTestingMain` under a debugger inside your favorite IDE. It starts Informant and generates a variety of sample traces to give the UI something to display and to help with manual testing. Connect your browser to `http://localhost:4001`.

If you are working on the UI, you either need to run `grunt` to re-build the web assets after each change, or (better) run:

    grunt server

and connect your browser to `http://localhost:9000`.  Note: `grunt` and `grunt server` should be run from the informant subdirectory.

`grunt server` serves up the Informant web assets to the browser without the concat/minify/rev step, which makes testing/debugging much easier. It reverse proxies non- static resource requests to http://localhost:4001 to be handled by Informant. It also watches for changes to the files and performs live-reload of the assets inside the browser.

## License

Informant source code is licensed under the Apache License, Version 2.0.

Informant's binary distribution is released under the Apache License, Version 2.0. The binary distribution includes [third party web resources](https://github.com/informant/informant/wiki/Third-Party-Web-Resources) in _source form_ and [third party Java libraries](https://github.com/informant/informant/wiki/Third-Party-Java-Libraries) in _binary form_.

Informant's source distribution (which includes both [third party web resources](https://github.com/informant/informant/wiki/Third-Party-Web-Resources) and [third party Java libraries](https://github.com/informant/informant/wiki/Third-Party-Java-Libraries) in _source form_) is released under a mixed license, with each component's source governed by its respective license.
