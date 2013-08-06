/* global require, module */
/* jshint strict: false */

var lrSnippet = require('grunt-contrib-livereload/lib/utils').livereloadSnippet;
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
    export_dist: 'informant/ui-resources-dist/io/informant/local/ui/export-dist'
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
      ngtemplates: {
        files: [
          '<%= yeoman.app %>/views/*.html',
          '<%= yeoman.app %>/template/**/*.html'
        ],
        tasks: 'ngtemplates:server'
      },
      handlebars: {
        files: '<%= yeoman.app %>/hbs/*.hbs',
        tasks: 'handlebars'
      },
      livereload: {
        files: [
          '<%= yeoman.app %>/index.html',
          '<%= yeoman.app %>/scripts/**/*.js',
          '.tmp/scripts/app.js',
          '.tmp/scripts/angular-templates.js',
          '.tmp/scripts/handlebars-templates.js',
          '.tmp/styles/app.css'
        ],
        tasks: 'livereload'
      }
    },
    connect: {
      options: {
        port: 9000,
        // change hostname to 0.0.0.0 to access it from another machine
        hostname: 'localhost'
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
        '/.*\\.html': '/index.html',
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
              // for source maps
              mountFolder(connect, '.')
            ];
          }
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
              '<%= yeoman.export_dist %>/*'
            ]
          }
        ]
      },
      server: '.tmp'
    },
    jshint: {
      options: {
        force: true
      },
      all: [
        'Gruntfile.js',
        '<%= yeoman.app %>/scripts/**/*.js'
      ]
    },
    sass: {
      dist: {
        files: {
          '<%= yeoman.dist %>/styles/app.css': '<%= yeoman.app %>/styles/app.scss',
          '<%= yeoman.export_dist %>/styles/export.css': '<%= yeoman.app %>/styles/export.scss'
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
      export_dist: {
        files: {'<%= yeoman.export_dist %>/export.html': '<%= yeoman.app %>/export.html'},
        options: {
          dest: '<%= yeoman.export_dist %>'
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
          '.tmp/scripts/handlebars-templates.js': '<%= yeoman.app %>/hbs/*.hbs'
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
            cwd: '<%= yeoman.export_dist %>',
            src: 'export.html',
            dest: '<%= yeoman.export_dist %>'
          }
        ]
      }
    },
    ngtemplates: {
      dist: {
        options: {
          base: '.tmp',
          module: 'informant'
        },
        src: [
          '.tmp/views/*.html',
          '.tmp/partials/*.html',
          '.tmp/template/**/*.html'
        ],
        dest: '.tmp/scripts/angular-templates.js'
      },
      server: {
        options: {
          base: '<%= yeoman.app %>',
          module: 'informant'
        },
        src: [
          '<%= yeoman.app %>/views/*.html',
          '<%= yeoman.app %>/partials/*.html',
          '<%= yeoman.app %>/template/**/*.html'
        ],
        dest: '.tmp/scripts/angular-templates.js'
      }
    },
    ngmin: {
      dist: {
        src: '.tmp/concat/scripts/app.js',
        dest: '.tmp/concat/scripts/app.js'
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
              'components/jquery/jquery.min.js',
              'components/angular/angular.min.js',
              'components/swfobject/swfobject.js',
              'components/flashcanvas/flashcanvas.*',
              'components/flot/excanvas.js',
              'components/sass-bootstrap/img/*.png',
              'components/specialelite/*',
              'images/favicon.ico',
              'index.html'
            ]
          },
          {
            expand: true,
            cwd: '<%= yeoman.app %>',
            dest: '<%= yeoman.export_dist %>',
            src: 'export.html'
          }
        ]
      }
    },
    cssmin: {
      dist: {
        files: {
          '<%= yeoman.dist %>/styles/app.css': '<%= yeoman.dist %>/styles/app.css',
          '<%= yeoman.export_dist %>/styles/export.css': '<%= yeoman.export_dist %>/styles/export.css'
        }
      }
    },
    rev: {
      dist: {
        files: {
          src: [
            // jquery.min.js and angular.min.js are used for cdn fallback
            '<%= yeoman.dist %>/components/jquery/jquery.min.js',
            '<%= yeoman.dist %>/components/angular/angular.min.js',
            '<%= yeoman.dist %>/components/swfobject/swfobject.js',
            '<%= yeoman.dist %>/components/flashcanvas/flashcanvas.js',
            // flashcanvas.swf is not revved at this time as it would
            // require updating its url inside of flashcanvas.js
            '<%= yeoman.dist %>/components/flot/excanvas.js',
            '<%= yeoman.dist %>/components/sass-bootstrap/img/*.png',
            '<%= yeoman.dist %>/components/specialelite/*',
            '<%= yeoman.dist %>/images/favicon.ico',
            '<%= yeoman.dist %>/scripts/app{,.components}.js',
            '<%= yeoman.dist %>/styles/app{,.components}.css'
          ]
        }
      }
    },
    usemin: {
      html: ['<%= yeoman.dist %>/index.html', '<%= yeoman.export_dist %>/export.html'],
      // use revved font filenames in revved app.css
      css: '<%= yeoman.dist %>/styles/*.app.css'
    },
    // TODO replace with grunt-google-cdn after moving to bower
    replace: {
      dist: {
        src: '<%= yeoman.dist %>/index.html',
        overwrite: true,
        replacements: [{
          from: 'components/jquery/jquery.js',
          to: '//ajax.googleapis.com/ajax/libs/jquery/1.10.1/jquery.min.js'
        },
        {
          from: 'components/angular/angular.js',
          to: '//ajax.googleapis.com/ajax/libs/angularjs/1.1.5/angular.min.js'
        }]
      }
    }
  });

  grunt.renameTask('regarde', 'watch');

  grunt.registerTask('server', [
    'clean:server',
    'sass:server',
    'ngtemplates:server',
    'handlebars',
    'configureRewriteRules',
    'configureProxies',
    'livereload-start',
    'connect:livereload',
    'watch'
  ]);

  grunt.registerTask('build', [
    'clean:dist',
    'jshint',
    'sass:dist',
    'useminPrepare',
    'htmlmin:ngtemplates',
    'ngtemplates:dist',
    'handlebars',
    'concat',
    'copy',
    'cssmin',
    'ngmin',
    'uglify',
    'rev',
    'usemin',
    'replace',
    'htmlmin:pages'
  ]);

  grunt.registerTask('default', 'build');
};
