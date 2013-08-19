/* global require, module */
/* jshint strict: false */

var LIVERELOAD_PORT = 35729;
var lrSnippet = require('connect-livereload')({port: LIVERELOAD_PORT});
var rewriteRulesSnippet = require('grunt-connect-rewrite/lib/utils').rewriteRequest;
var mountFolder = function (connect, dir) {
  return connect.static(require('path').resolve(dir));
};

var proxySnippet = require('grunt-connect-proxy/lib/utils').proxyRequest;

module.exports = function (grunt) {
  // load all grunt tasks
  require('matchdep').filterDev('grunt-*').forEach(grunt.loadNpmTasks);

  // configurable paths
  var yeomanConfig = {
    app: 'informant/src/main/resources/io/informant/local/ui/app',
    dist: 'informant/ui-resources-dist/io/informant/local/ui/app-dist',
    exportDist: 'informant/ui-resources-dist/io/informant/local/ui/export-dist'
  };

  try {
    yeomanConfig.app = require('./component.json').appPath || yeomanConfig.app;
  } catch (e) {}

  grunt.initConfig({
    yeoman: yeomanConfig,
    watch: {
      sass: {
        files: '<%= yeoman.app %>/styles/*.scss',
        tasks: 'sass:server'
      },
      handlebars: {
        files: '<%= yeoman.app %>/hbs/*.hbs',
        tasks: 'handlebars'
      },
      livereload: {
        options: {
          livereload: LIVERELOAD_PORT
        },
        files: [
          '<%= yeoman.app %>/index.html',
          '<%= yeoman.app %>/scripts/**/*.js',
          '<%= yeoman.app %>/views/*.html',
          '<%= yeoman.app %>/partials/*.html',
          '<%= yeoman.app %>/template/**/*.html',
          '.tmp/generated/handlebars-templates.js',
          '.tmp/scripts/app.js',
          '.tmp/styles/app.css'
        ]
      }
    },
    connect: {
      options: {
        port: 9000,
        hostname: '0.0.0.0'
      },
      proxies: [
        {
          context: '/backend',
          host: 'localhost',
          port: 4001
        },
        {
          context: '/export',
          host: 'localhost',
          port: 4001
        }
      ],
      rules: {
        '^/[^/]*.html$': '/index.html'
      },
      livereload: {
        options: {
          middleware: function (connect) {
            return [
              lrSnippet,
              rewriteRulesSnippet,
              proxySnippet,
              mountFolder(connect, yeomanConfig.app),
              mountFolder(connect, '.tmp'),
              // serve angular-ui-bootstrap templates
              mountFolder(connect, yeomanConfig.app + '/bower_components/angular-ui-bootstrap'),
              function(req, res, next) {
                if (req.url === '/generated/angular-ui-bootstrap-templates.js' ||
                    req.url === '/generated/templates.js') {
                  // angular html templates are retrieved directly when running connect server
                  // but index.html file still references them, so need to return dummy response
                  res.end('// dummy javascript file');
                } else {
                  next();
                }
              },
              // serve source maps
              mountFolder(connect, '.')
            ];
          }
        }
      }
    },
    bower: {
      install: {
        options: {
          copy: false
        }
      }
    },
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
      server: '.tmp'
    },
    jshint: {
      options: {
        jshintrc: '.jshintrc',
      },
      files: [
        'Gruntfile.js',
        '<%= yeoman.app %>/scripts/**/*.js'
      ]
    },
    sass: {
      dist: {
        files: {
          '<%= yeoman.dist %>/styles/app.css': '<%= yeoman.app %>/styles/app.scss',
          '<%= yeoman.exportDist %>/styles/export.css': '<%= yeoman.app %>/styles/export.scss'
        }
      },
      server: {
        files: {
          '.tmp/styles/app.css': '<%= yeoman.app %>/styles/app.scss'
        }
      }
    },
    useminPrepare: {
      dist: {
        files: {'<%= yeoman.dist %>/index.html': '<%= yeoman.app %>/index.html'},
        options: {
          dest: '<%= yeoman.dist %>'
        }
      },
      exportDist: {
        files: {'<%= yeoman.exportDist %>/export.html': '<%= yeoman.app %>/export.html'},
        options: {
          dest: '<%= yeoman.exportDist %>'
        }
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
            // keep newlines since they can be meaningful, e.g. in threaddump.hbs
            return content;
          }
        },
        files: {
          '.tmp/generated/handlebars-templates.js': '<%= yeoman.app %>/hbs/*.hbs'
        }
      }
    },
    htmlmin: {
      options: {
        removeComments: true,
        collapseWhitespace: true
      },
      ngtemplates: {
        files: [
          {
            expand: true,
            cwd: '<%= yeoman.app %>',
            src: [
              'bower_components/angular-ui-bootstrap/template/accordion/accordion.html',
              'views/*.html',
              'partials/*.html',
              'template/**/*.html'
            ],
            dest: '.tmp'
          }
        ]
      },
      pages: {
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
            src: 'export.html',
            dest: '<%= yeoman.exportDist %>'
          }
        ]
      }
    },
    ngtemplates: {
      components: {
        options: {
          base: '.tmp/bower_components/angular-ui-bootstrap',
          module: 'ui.bootstrap.accordion',
        },
        src: [
          '.tmp/bower_components/angular-ui-bootstrap/template/accordion/accordion.html'
        ],
        dest: '.tmp/generated/angular-ui-bootstrap-templates.js'
      },
      app: {
        options: {
          base: '.tmp',
          module: 'informant'
        },
        src: [
          '.tmp/views/*.html',
          '.tmp/partials/*.html',
          '.tmp/template/**/*.html'
        ],
        dest: '.tmp/generated/templates.js'
      }
    },
    ngmin: {
      dist: {
        files: {
          '.tmp/concat/scripts/app.js': '.tmp/concat/scripts/app.js'
        }
      }
    },
    uglify: {
      options: {
        preserveComments: function (node, comment) {
          // TODO moment.js license is not currently included
          // TODO find better way of excluding informant license
          return (comment.value.indexOf('!') === 0 || comment.value.indexOf('Copyright') !== -1) &&
              comment.value.indexOf('the original author or authors.') === -1;
        }
      }
    },
    copy: {
      dist: {
        files: [
          {
            expand: true,
            cwd: '<%= yeoman.app %>',
            dest: '<%= yeoman.dist %>',
            src: [
              // jquery.min.js and angular.min.js are used for cdn fallback
              'bower_components/jquery/jquery.min.js',
              'bower_components/angular-unstable/angular.min.js',
              'bower_components/flot/excanvas.min.js',
              'bower_components/sass-bootstrap/img/*.png',
              'styles/fonts/*',
              'favicon.ico',
              'index.html'
            ]
          },
          {
            expand: true,
            cwd: '<%= yeoman.app %>',
            dest: '<%= yeoman.exportDist %>',
            src: 'export.html'
          }
        ]
      }
    },
    cssmin: {
      dist: {
        files: {
          '<%= yeoman.dist %>/styles/app.css': '<%= yeoman.dist %>/styles/app.css',
          '<%= yeoman.exportDist %>/styles/export.css': '<%= yeoman.exportDist %>/styles/export.css'
        }
      }
    },
    rev: {
      dist: {
        files: {
          src: [
            // jquery.min.js and angular.min.js are used for cdn fallback
            '<%= yeoman.dist %>/bower_components/jquery/jquery.min.js',
            '<%= yeoman.dist %>/bower_components/angular-unstable/angular.min.js',
            '<%= yeoman.dist %>/bower_components/flot/excanvas.min.js',
            '<%= yeoman.dist %>/bower_components/sass-bootstrap/img/*.png',
            '<%= yeoman.dist %>/styles/fonts/*',
            '<%= yeoman.dist %>/scripts/app{,.components}.js',
            '<%= yeoman.dist %>/styles/app{,.components}.css'
          ]
        }
      }
    },
    usemin: {
      html: ['<%= yeoman.dist %>/index.html', '<%= yeoman.exportDist %>/export.html'],
      // use revved font filenames in revved app.css
      css: '<%= yeoman.dist %>/styles/*.app.css'
    },
    cdnify: {
      dist: {
        // jquery
        html: '<%= yeoman.dist %>/index.html'
      }
    },
    replace: {
      dist: {
        src: '<%= yeoman.dist %>/index.html',
        overwrite: true,
        replacements: [{
          // TODO remove once on angular stable release since then cdnify above will handle this
          from: 'bower_components/angular-unstable/angular.js',
          to: '//ajax.googleapis.com/ajax/libs/angularjs/1.1.5/angular.min.js'
        }]
      }
    }
  });

  grunt.registerTask('server', [
    'clean:server',
    'sass:server',
    'handlebars',
    'configureRewriteRules',
    'configureProxies',
    'connect:livereload',
    'watch'
  ]);

  grunt.registerTask('build', [
    'bower',
    'clean:dist',
    'jshint',
    'sass:dist',
    'useminPrepare',
    'htmlmin',
    'ngtemplates:components',
    'ngtemplates:app',
    'handlebars',
    'concat',
    'copy',
    'cssmin',
    'ngmin',
    'uglify',
    'rev',
    'usemin',
    'cdnify',
    'replace',
    'htmlmin:pages'
  ]);

  grunt.registerTask('default', 'build');
};
