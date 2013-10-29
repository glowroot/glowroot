/* global require, module */

var LIVERELOAD_PORT = 35729;
var mountFolder = function (connect, dir) {
  return connect.static(require('path').resolve(dir));
};
var setXUACompatibleHeader = function (req, res, next) {
  // X-UA-Compatible must be set via header (as opposed to via meta tag)
  // see https://github.com/h5bp/html5-boilerplate/blob/master/doc/html.md#x-ua-compatible
  res.setHeader('X-UA-Compatible', 'IE=edge');
  next();
};

module.exports = function (grunt) {

  // load all grunt tasks
  require('matchdep').filterDev('grunt-*').forEach(grunt.loadNpmTasks);

  // configurable paths
  var yeomanConfig = {
    app: 'informant/app',
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
          '<%= yeoman.app %>/views/**/*.html',
          '<%= yeoman.app %>/template/**/*.html',
          // watch:sass output
          '.tmp/app/styles/main.css',
          // watch:handlebars output
          '.tmp/app/scripts/generated/handlebars-templates.js'
        ]
      },
      gruntfile: {
        files: 'Gruntfile.js'
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
        '^/traces$': '/index.html',
        '^/aggregates$': '/index.html',
        '^/jvm/.*$': '/index.html',
        '^/config/.*$': '/index.html',
        '^/login$': '/index.html'
      },
      livereload: {
        options: {
          middleware: function (connect) {
            return [
              setXUACompatibleHeader,
              require('connect-livereload')({port: LIVERELOAD_PORT}),
              require('grunt-connect-rewrite/lib/utils').rewriteRequest,
              require('grunt-connect-proxy/lib/utils').proxyRequest,
              mountFolder(connect, yeomanConfig.app),
              mountFolder(connect, '.tmp/app'),
              // serve angular-ui-bootstrap templates
              mountFolder(connect, yeomanConfig.app + '/bower_components/angular-ui-bootstrap'),
              // serve source maps
              mountFolder(connect, '.')
            ];
          }
        }
      },
      dist: {
        options: {
          middleware: function (connect) {
            return [
              setXUACompatibleHeader,
              mountFolder(connect, yeomanConfig.dist)
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
        jshintrc: '.jshintrc'
      },
      files: [
        'Gruntfile.js',
        '<%= yeoman.app %>/scripts/**/*.js'
      ]
    },
    sass: {
      dist: {
        files: {
          // need to output main.css to .tmp so it can still be concatenated with qtip and datepicker css files
          // once sass supports inlining css files, this can output directly to destination
          // see https://github.com/nex3/sass/issues/556
          '.tmp/app/styles/main.css': '<%= yeoman.app %>/styles/main.scss',
          '<%= yeoman.exportDist %>/styles/export.css': '<%= yeoman.app %>/styles/export.scss'
        }
      },
      server: {
        files: {
          '.tmp/app/styles/main.css': '<%= yeoman.app %>/styles/main.scss'
        }
      }
    },
    useminPrepare: {
      options: {
        flow: {
          steps: {
            js: [
              {
                name: 'copy',
                createConfig: function (context, block) {
                  var cfg = {
                    files: []
                  };
                  context.inFiles.forEach(function (file) {
                    cfg.files.push({
                      src: [
                        context.inDir + '/' + file
                      ],
                      dest: '<%= yeoman.dist %>/sources/' + file
                    });
                  });
                  context.outFiles = context.inFiles;
                  context.outDir = context.inDir;
                  return cfg;
                }
              },
              'uglifyjs'
            ],
            css: ['concat', 'cssmin']
          },
          post: []
        }
      },
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
          '<%= yeoman.exportDist %>/export.html': '<%= yeoman.app %>/export.html'
        },
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
            // keep newlines since they can be meaningful, e.g. in thread-dump.hbs
            return content;
          }
        },
        files: {
          '.tmp/app/scripts/generated/handlebars-templates.js': '<%= yeoman.app %>/hbs/*.hbs'
        }
      }
    },
    htmlmin: {
      options: {
        removeComments: true,
        collapseWhitespace: true
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
      options: {
        htmlmin: {
          collapseBooleanAttributes: true,
          collapseWhitespace: true,
          removeAttributeQuotes: true,
          removeComments: true,
          removeEmptyAttributes: true,
          removeRedundantAttributes: true,
          removeScriptTypeAttributes: true,
          removeStyleLinkTypeAttributes: true
        }
      },
      uiBootstrapTemplates: {
        options: {
          module: 'ui.bootstrap.typeahead'
        },
        cwd: '<%= yeoman.app %>/bower_components/angular-ui-bootstrap',
        src: [
          'template/typeahead/*.html'
        ],
        dest: '.tmp/app/scripts/generated/angular-ui-bootstrap-templates.js'
      },
      appTemplates: {
        options: {
          module: 'informant'
        },
        cwd: '<%= yeoman.app %>',
        src: [
          'views/**/*.html',
          'template/**/*.html'
        ],
        dest: '.tmp/app/scripts/generated/angular-templates.js'
      }
    },
    uglify: {
      options: {
        preserveComments: function (node, comment) {
          // TODO moment.js license is not currently included
          // TODO find better way of excluding informant license
          return (comment.value.indexOf('!') === 0 || comment.value.indexOf('Copyright') !== -1) &&
              comment.value.indexOf('the original author or authors.') === -1;
        },
        sourceMap: function (file) {
          if (file.indexOf('export.js') !== -1 || file.indexOf('export.components.js') !== -1) {
            // no use in generating source maps for js that is inlined into export files
            return undefined;
          }
          return file.replace(/(.*)[/\\]scripts[/\\]([^/\\]+)$/, '$1/sources/$2') + '.map';
        },
        sourceMapRoot: '/sources',
        // drop informant/app and .tmp/app prefixes in the source map file
        // NOTE: this is why .tmp/app is used instead of just .tmp, since then it wouldn't have the same number of
        // path elements that need to be stripped
        sourceMapPrefix: 2,
        sourceMappingURL: function (file) {
          if (file.indexOf('export.js') !== -1 || file.indexOf('export.components.js') !== -1) {
            // don't add sourceMappingURL to js that is inlined into export files
            return undefined;
          }
          // strip path and add .map
          // use relative url so it will work using different <base href=""> urls
          return file.replace(/.*[/\\]scripts[/\\]([^/\\]+)$/, '../sources/$1') + '.map';
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
              'bower_components/angular/angular.min.js',
              'bower_components/angular/angular.min.js.map',
              'bower_components/flot/excanvas.min.js',
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
          '<%= yeoman.exportDist %>/styles/export.css': '<%= yeoman.exportDist %>/styles/export.css'
        }
      }
    },
    replace: {
      index: {
        src: '<%= yeoman.dist %>/index.html',
        overwrite: true,
        replacements: [
          {
            // not using angular cdn at this time since custom angular build is required due to
            // https://github.com/angular/angular.js/pull/3135
            from: 'bower_components/angular/angular.js',
            to: 'bower_components/angular/angular.min.js'
          }
        ]
      },
      sourceMaps: {
        src: [
          '<%= yeoman.dist %>/sources/*.js.map'
        ],
        overwrite: true,
        replacements: [
          {
            // strip out the file attribute
            // if the file attribute does not matches the rev'd filename then browser won't be happy
            // TODO make it match the rev'd filename
            from: /"file":[^,]+,/,
            to: ''
          }
        ]
      }
    },
    rev: {
      dist: {
        files: {
          src: [
            // jquery.min.js and angular.min.js are used for cdn fallback
            '<%= yeoman.dist %>/bower_components/jquery/jquery.min.js',
            '<%= yeoman.dist %>/bower_components/angular/angular.min.js',
            '<%= yeoman.dist %>/bower_components/flot/excanvas.min.js',
            '<%= yeoman.dist %>/styles/fonts/*',
            '<%= yeoman.dist %>/scripts/*.js',
            '<%= yeoman.dist %>/styles/main.css'
          ]
        }
      }
    },
    usemin: {
      html: [
        '<%= yeoman.dist %>/index.html',
        '<%= yeoman.exportDist %>/export.html'
      ],
      // use revved font filenames in revved main.css
      css: '<%= yeoman.dist %>/styles/*.main.css'
    },
    cdnify: {
      dist: {
        // jquery
        // cdnify won't replace angular since using '+patch.X' version of angular
        html: '<%= yeoman.dist %>/index.html'
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
    'ngtemplates',
    'handlebars',
    'concat',
    'copy',
    'replace:index',
    'cssmin',
    'uglify',
    'replace:sourceMaps',
    'rev',
    'usemin',
    'cdnify',
    'htmlmin'
  ]);

  grunt.registerTask('default', 'build');
};
