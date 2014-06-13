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
    app: 'app',
    dist: 'target/ui-resources-dist/org/glowroot/local/ui/app-dist',
    exportDist: 'target/ui-resources-dist/org/glowroot/local/ui/export-dist'
  };

  grunt.initConfig({
    yeoman: yeomanConfig,
    watch: {
      less: {
        files: '<%= yeoman.app %>/styles/*.less',
        tasks: 'less:server'
      },
      handlebars: {
        files: '<%= yeoman.app %>/hbs/*.hbs',
        tasks: 'handlebars'
      },
      fontawesome: {
        files: '<%= yeoman.app %>/bower_components/fontawesome/fonts/*',
        tasks: 'copy:server'
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
          // watch:less output
          '.tmp/styles/main.css',
          // watch:handlebars output
          '.tmp/scripts/generated/handlebars-templates.js'
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
          port: 4000
        },
        {
          context: '/export',
          host: 'localhost',
          port: 4000
        }
      ],
      rules: [
        { from: '^/transactions(\\?.*)?$', to: '/index.html' },
        { from: '^/errors(\\?.*)?$', to: '/index.html' },
        { from: '^/traces(\\?.*)?$', to: '/index.html' },
        { from: '^/jvm/.*$', to: '/index.html' },
        { from: '^/config/.*$', to: '/index.html' },
        { from: '^/login$', to: '/index.html' }
      ],
      livereload: {
        options: {
          middleware: function (connect) {
            return [
              setXUACompatibleHeader,
              require('connect-livereload')({port: LIVERELOAD_PORT}),
              require('grunt-connect-rewrite/lib/utils').rewriteRequest,
              require('grunt-connect-proxy/lib/utils').proxyRequest,
              mountFolder(connect, yeomanConfig.app),
              mountFolder(connect, '.tmp'),
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
    less: {
      dist: {
        files: {
          '<%= yeoman.dist %>/styles/main.css': '<%= yeoman.app %>/styles/main.less',
          '<%= yeoman.exportDist %>/styles/export.css': '<%= yeoman.app %>/styles/export.less'
        }
      },
      server: {
        files: {
          '.tmp/styles/main.css': '<%= yeoman.app %>/styles/main.less'
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
            ]
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
          '.tmp/scripts/generated/handlebars-templates.js': '<%= yeoman.app %>/hbs/*.hbs'
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
          'template/typeahead/*.html',
          'template/modal/*.html'
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
    uglify: {
      options: {
        preserveComments: function (node, comment) {
          // TODO moment.js license is not currently included
          // TODO find better way of excluding glowroot license
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
        // drop app and .tmp prefixes in the source map file
        // it's important that these two directories have the same number of path elements
        sourceMapPrefix: 1,
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
              'styles/fonts/*',
              'favicon.ico',
              'index.html'
            ]
          },
          {
            expand: true,
            cwd: '<%= yeoman.app %>/bower_components/fontawesome/fonts',
            dest: '<%= yeoman.dist %>/styles/fonts',
            src: [
              '*'
            ]
          },
          {
            expand: true,
            cwd: '<%= yeoman.app %>',
            dest: '<%= yeoman.exportDist %>',
            src: 'export.html'
          }
        ]
      },
      server: {
        files: [
          {
            expand: true,
            cwd: '<%= yeoman.app %>/bower_components/fontawesome/fonts',
            dest: '.tmp/styles/fonts',
            src: [
              '*'
            ]
          }
        ]
      }
    },
    cssmin: {
      dist: {
        files: {
          '<%= yeoman.dist %>/styles/main.css': '<%= yeoman.dist %>/styles/main.css',
          '<%= yeoman.exportDist %>/styles/export.css': '<%= yeoman.exportDist %>/styles/export.css'
        }
      }
    },
    replace: {
      dist: {
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
    }
  });

  grunt.registerTask('serve', [
    'clean:server',
    'less:server',
    'handlebars',
    'copy:server',
    'configureRewriteRules',
    'configureProxies',
    'connect:livereload',
    'watch'
  ]);

  grunt.registerTask('build', [
    'clean:dist',
    'jshint',
    'less:dist',
    'useminPrepare',
    'ngtemplates',
    'handlebars',
    'copy:dist',
    'cssmin',
    'uglify',
    'replace',
    'rev',
    'usemin',
    'htmlmin'
  ]);

  grunt.registerTask('default', 'build');
};
