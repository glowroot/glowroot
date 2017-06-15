/* global require, module */

module.exports = function (grunt) {

  // Load grunt tasks automatically
  require('load-grunt-tasks')(grunt);

  // Time how long tasks take. Can help when optimizing build times
  require('time-grunt')(grunt);

  // Configurable paths for the application
  var appConfig = {
    app: 'app',
    dist: 'target/generated-resources/app-dist/org/glowroot/ui/app-dist',
    exportDist: 'target/generated-resources/export-dist/org/glowroot/ui/export-dist'
  };

  // Define the configuration for all the tasks
  grunt.initConfig({

    // Project settings
    yeoman: appConfig,

    // Watches files for changes and runs tasks based on the changed files
    watch: {
      less: {
        files: ['<%= yeoman.app %>/styles/*.less'],
        tasks: ['less']
      },
      handlebars: {
        files: ['<%= yeoman.app %>/hbs/*.hbs'],
        tasks: ['handlebars']
      },
      gruntfile: {
        files: ['Gruntfile.js']
      },
      livereload: {
        options: {
          livereload: '<%= connect.livereload.options.livereload %>'
        },
        files: [
          '<%= yeoman.app %>/index.html',
          '<%= yeoman.app %>/scripts/**/*.js',
          '<%= yeoman.app %>/views/**/*.html',
          '<%= yeoman.app %>/template/**/*.html',
          // watch:less output
          '.tmp/styles/main.css',
          // watch:handlebars output
          '.tmp/scripts/generated/handlebars-templates.js'
        ]
      }
    },

    // The actual grunt server settings
    connect: {
      rules: [
        {from: '^/transaction/.*$', to: '/index.html'},
        {from: '^/error/.*$', to: '/index.html'},
        {from: '^/jvm/.*$', to: '/index.html'},
        {from: '^/synthetic-monitors(|\\?.*)$', to: '/index.html'},
        {from: '^/incidents(|\\?.*)$', to: '/index.html'},
        {from: '^/report/.*$', to: '/index.html'},
        {from: '^/config/.*$', to: '/index.html'},
        {from: '^/admin/.*$', to: '/index.html'},
        {from: '^/profile/.*', to: '/index.html'},
        {from: '^/login$', to: '/index.html'}
      ],
      livereload: {
        proxies: [
          {
            context: '/backend',
            host: 'localhost',
            port: 4000
          },
          {
            context: '/export',
            host: 'localhost',
            port: 4000
          }
        ],
        options: {
          port: 9000,
          // Change this to '0.0.0.0' to access the server from outside.
          hostname: 'localhost',
          livereload: 35729,
          open: true,
          middleware: function (connect) {
            var setXUACompatibleHeader = function (req, res, next) {
              // X-UA-Compatible must be set via header (as opposed to via meta tag)
              // see https://github.com/h5bp/html5-boilerplate/blob/master/doc/html.md#x-ua-compatible
              res.setHeader('X-UA-Compatible', 'IE=edge');
              next();
            };
            var serveStatic = require('serve-static');
            return [
              setXUACompatibleHeader,
              require('grunt-connect-rewrite/lib/utils').rewriteRequest,
              require('grunt-connect-proxy/lib/utils').proxyRequest,
              serveStatic('.tmp'),
              connect().use('/bower_components', serveStatic('bower_components')),
              serveStatic(appConfig.app),
              connect().use('/fonts', serveStatic('bower_components/fontawesome/fonts')),
              connect().use('/uib/template', serveStatic('bower_components/angular-ui-bootstrap/template'))
            ];
          }
        }
      }
    },

    // Make sure code styles are up to par and there are no obvious mistakes
    jshint: {
      options: {
        jshintrc: '.jshintrc',
        reporter: require('jshint-stylish')
      },
      all: {
        src: [
          'Gruntfile.js',
          '<%= yeoman.app %>/scripts/**/*.js'
        ]
      }
    },

    // Empties folders to start fresh
    clean: {
      dist: {
        files: [
          {
            dot: true,
            src: [
              '.tmp',
              '<%= yeoman.dist %>/*',
              '<%= yeoman.exportDist %>/*'
            ]
          }
        ]
      },
      serve: '.tmp'
    },

    // Reads HTML for usemin blocks to enable smart builds that automatically
    // concat, minify and revision files. Creates configurations in memory so
    // additional tasks can operate on them
    useminPrepare: {
      dist: {
        files: {
          '<%= yeoman.dist %>/index.html': '<%= yeoman.app %>/index.html'
        },
        options: {
          dest: '<%= yeoman.dist %>'
        }
      },
      exportDist: {
        files: {
          '<%= yeoman.exportDist %>/aggregate-export.html': '<%= yeoman.app %>/aggregate-export.html',
          '<%= yeoman.exportDist %>/trace-export.html': '<%= yeoman.app %>/trace-export.html'
        },
        options: {
          dest: '<%= yeoman.exportDist %>'
        }
      }
    },

    less: {
      dist: {
        files: {
          '.tmp/styles/main.css': '<%= yeoman.app %>/styles/main.less',
          '.tmp/styles/export.css': '<%= yeoman.app %>/styles/export.less'
        }
      }
    },

    ngtemplates: {
      options: {
        htmlmin: {
          collapseWhitespace: true,
          // conservativeCollapse keeps one space, this helps prevent collapsing intentional space between
          // two inline elements, e.g. in "Transactions | Servlet" header
          // these issues don't appear when running under grunt serve (since no minification) so easy to miss
          // and better to use conservative option here
          conservativeCollapse: true,
          removeComments: true
        }
      },
      uiBootstrapTemplates: {
        options: {
          module: 'ui.bootstrap.typeahead',
          prefix: 'uib'
        },
        cwd: 'bower_components/angular-ui-bootstrap',
        src: [
          'template/typeahead/*.html',
          'template/modal/*.html',
          'template/popover/*.html'
        ],
        dest: '.tmp/scripts/generated/angular-ui-bootstrap-templates.js'
      },
      appTemplates: {
        options: {
          module: 'glowroot'
        },
        cwd: '<%= yeoman.app %>',
        src: [
          'views/**/*.html',
          'template/**/*.html'
        ],
        dest: '.tmp/scripts/generated/angular-templates.js'
      }
    },

    handlebars: {
      dist: {
        options: {
          processName: function (filename) {
            var pieces = filename.split('/');
            var simple = pieces[pieces.length - 1];
            return simple.substring(0, simple.indexOf('.'));
          },
          processContent: function (content) {
            // remove leading and trailing spaces
            content = content.replace(/^[ \t\r\n]+/mg, '').replace(/[ \t]+$/mg, '');
            // keep newlines since they can be meaningful, e.g. in thread-dump.hbs
            return content;
          }
        },
        files: {
          '.tmp/scripts/generated/handlebars-templates.js': '<%= yeoman.app %>/hbs/*.hbs'
        }
      }
    },

    // Copies remaining files to places other tasks can use
    copy: {
      dist: {
        files: [
          {
            expand: true,
            cwd: '<%= yeoman.app %>',
            dest: '<%= yeoman.dist %>',
            src: [
              'favicon.ico',
              'index.html',
              'fonts/*'
            ]
          },
          {
            expand: true,
            cwd: 'bower_components/fontawesome',
            dest: '<%= yeoman.dist %>',
            src: [
              // only supporting IE9+ so only need woff/woff2
              'fonts/*.woff{,2}'
            ]
          },
          {
            src: 'bower_components/zeroclipboard/dist/ZeroClipboard.swf',
            dest: '<%= yeoman.dist %>/scripts/ZeroClipboard.swf'
          },
          {
            expand: true,
            cwd: '<%= yeoman.app %>',
            dest: '<%= yeoman.exportDist %>',
            src: [
              'aggregate-export.html',
              'trace-export.html'
            ]
          }
        ]
      }
    },

    uglify: {
      options: {
        screwIE8: true,
        // currently report is not displayed unless without grunt -v
        // see https://github.com/gruntjs/grunt-contrib-uglify/issues/254
        report: 'gzip'
      }
    },

    // Renames files for browser caching purposes
    filerev: {
      dist: {
        src: [
          '<%= yeoman.dist %>/scripts/*.js',
          '<%= yeoman.dist %>/scripts/*.swf',
          '<%= yeoman.dist %>/styles/*.css',
          '<%= yeoman.dist %>/fonts/*',
          '<%= yeoman.dist %>/favicon.ico'
        ]
      }
    },

    // Performs rewrites based on filerev and the useminPrepare configuration
    usemin: {
      html: [
        '<%= yeoman.dist %>/index.html',
        '<%= yeoman.exportDist %>/*-export.html'
      ],
      css: ['<%= yeoman.dist %>/styles/*.css'],
      // need to rev ZeroClipboard.swf which is referenced in javascript
      js: ['<%= yeoman.dist %>/scripts/vendor.*.js'],
      options: {
        assetsDirs: ['<%= yeoman.dist %>', '<%= yeoman.dist %>/fonts', '<%= yeoman.dist %>/scripts'],
        patterns: {
          js: [
            [/(ZeroClipboard\.swf)/, 'Replacing reference to ZeroClipboard.swf']
          ]
        },
        blockReplacements: {
          // this is workaround for grunt-usemin issue #391
          js: function (block){
            if (block.dest === 'scripts/vendor-flame-graph.js') {
              return '<script async src="' + block.dest + '"><\/script>';
            } else {
              return '<script src="' + block.dest + '"><\/script>';
            }
          }
        }
      }
    },

    htmlmin: {
      dist: {
        options: {
          removeComments: true
        },
        files: [
          {
            expand: true,
            cwd: '<%= yeoman.dist %>',
            src: 'index.html',
            dest: '<%= yeoman.dist %>'
          },
          {
            expand: true,
            cwd: '<%= yeoman.exportDist %>',
            src: '*-export.html',
            dest: '<%= yeoman.exportDist %>'
          }
        ]
      }
    }
  });

  grunt.registerTask('serve', 'Compile then start a connect web server', function (target) {
    if (target === 'xyzzy') {
      return grunt.task.run([
        'configureProxies:xyzzy',
        'configureRewriteRules',
        'connect:xyzzy:keepalive'
      ]);
    }

    grunt.task.run([
      'clean:serve',
      'less',
      'handlebars',
      'configureRewriteRules',
      'configureProxies:livereload',
      'connect:livereload',
      'watch'
    ]);
  });

  grunt.registerTask('build', [
    'clean:dist',
    'useminPrepare',
    'less',
    'ngtemplates',
    'handlebars',
    'concat',
    'copy:dist',
    'cssmin',
    'uglify',
    'filerev',
    'usemin',
    'htmlmin'
  ]);

  grunt.registerTask('default', [
    'newer:jshint',
    'build'
  ]);
};
