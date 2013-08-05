<img src="https://secure.gravatar.com/avatar/ab0f1c8f702263d8c954314b231d91ce?s=70" alt="Informant Logo"> Informant
=========

Root cause performance and error monitoring

Supports JDK5 or later (dynamic pointcut reloading in JDK6 and later).

## Quick start

1. Download informant.jar and place in an empty directory of your choosing
2. Add -javaagent:informant.jar to your application's JVM args
3. Start your application
4. Connect to http://localhost:4000

## Questions, bugs, feature requests

Post 'em all to the [GitHub issue tracker](https://github.com/informant/informant/issues).

## Contributing

#### How to build

Install [Node.js](http://nodejs.org), [Maven](http://maven.apache.org/download.cgi) and [Grunt](http://gruntjs.com).

Clone the repository and install the Node (build-time) dependencies:

    git clone https://github.com/informant/informant
    cd informant
    npm install

From inside the project's root directory:

    grunt
    mvn clean package

Binary and source distributions are built under package/target.

#### How to manually test/debug

Run `io.informant.testing.ui.UiTestingMain` under a debugger inside your favorite IDE. It starts Informant and generates a variety of sample traces to give the UI something to display and to help with manual testing. Connect your browser to `http://localhost:4001`.

If you are working on the UI, you either need to run `grunt` to re-build the UI after each change, or (better):

    grunt server

and connect your browser to `http://localhost:9000`.

`grunt server` will watch for modifications to Informant web resources and perform [SASS compilation](https://github.com/sindresorhus/grunt-sass), [AngularJS template concatenation](https://npmjs.org/package/grunt-angular-templates) and [handlebars template compilation](https://github.com/gruntjs/grunt-contrib-handlebars) automatically as needed. It reverse proxies non- static resource requests to http://localhost:4001 to be handled by Informant.

#### How to run automated tests

    grunt
    mvn clean test

## License

Informant source code is licensed under the Apache License, Version 2.0.

Informant's binary distribution is released under the Apache License, Version 2.0. The binary distribution includes [third party web resources](https://github.com/informant/informant/wiki/Third-Party-Web-Resources) in _source form_ and [third party Java libraries](https://github.com/informant/informant/wiki/Third-Party-Java-Libraries) in _binary form_.

Informant's source distribution (which includes both [third party web resources](https://github.com/informant/informant/wiki/Third-Party-Web-Resources) and [third party Java libraries](https://github.com/informant/informant/wiki/Third-Party-Java-Libraries) in _source form_) is released under a mixed license, with each component's source governed by its respective license.
