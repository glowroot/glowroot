/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

({
  appDir: '..',
  baseUrl: '.',
  dir: '../../../../../../../../target/local-ui-build/io/informant/local/ui-build',
  modules: [
    {
      name: 'informant',
      include: [
        'bootstrap-transition',
        'bootstrap-collapse',
        'handlebars',
        'jquery',
        'spin',
        'hbs',
        'underscore',
        'json2',
        'i18nprecompile'
      ]
    },
    {
      name: 'js/home',
      exclude: ['informant']
    },
    {
      name: 'js/export'
    },
    {
      name: 'js/config',
      exclude: ['informant']
    },
    {
      name: 'js/pointcuts',
      exclude: ['informant']
    },
    {
      name: 'js/threaddump',
      exclude: ['informant']
    }
  ],
  paths: {
    // common
    'informant': 'js/common',
    'bootstrap-transition': 'lib/bootstrap/js/bootstrap-transition',
    'bootstrap-collapse': 'lib/bootstrap/js/bootstrap-collapse',
    'handlebars': 'lib/handlebars/handlebars.runtime',
    'jquery': 'lib/jquery/jquery',
    'spin': 'lib/spin/spin',
    'hbs': 'lib/hbs/hbs',
    'underscore': 'lib/hbs/underscore',
    'json2': 'lib/hbs/json2',
    'i18nprecompile': 'lib/hbs/i18nprecompile',
    // home
    'trace': 'js/common-trace',
    'bootstrap-modal': 'lib/bootstrap/js/bootstrap-modal',
    'bootstrap-datepicker': 'lib/bootstrap-datepicker/js/bootstrap-datepicker',
    'jquery.color': 'lib/jquery/jquery.color',
    'jquery.flot': 'lib/flot/jquery.flot',
    'jquery.flot.time': 'lib/flot/jquery.flot.time',
    'jquery.flot.selection': 'lib/flot/jquery.flot.selection',
    'jquery.flot.navigate': 'lib/flot/jquery.flot.navigate',
    'jquery.qtip': 'lib/qtip/jquery.qtip',
    'jquery-migrate': 'lib/jquery/jquery-migrate',
    'moment': 'lib/moment/moment',
    // pointcuts
    'bootstrap-typeahead': 'lib/bootstrap/js/bootstrap-typeahead'
  },
  shim: {
    // common
    'bootstrap-transition': ['jquery'],
    'bootstrap-collapse': ['jquery'],
    'handlebars': {
      exports: 'Handlebars'
    },
    'spin': {
      exports: 'Spinner'
    },
    // home
    'bootstrap-modal': ['jquery'],
    'bootstrap-datepicker': ['jquery'],
    'jquery.color': ['jquery'],
    'jquery.flot': ['jquery'],
    'jquery.flot.time': ['jquery', 'jquery.flot'],
    'jquery.flot.selection': ['jquery', 'jquery.flot'],
    'jquery.flot.navigate': ['jquery', 'jquery.flot'],
    // jquery-migrate is needed until qtip is jquery 1.9 compatible,
    // see https://github.com/Craga89/qTip2/issues/459 -->
    'jquery.qtip': ['jquery', 'jquery-migrate'],
    'jquery-migrate': ['jquery'],
    'moment': {
      exports: 'moment'
    },
    // pointcuts
    'bootstrap-typeahead': ['jquery']
  },
  hbs: {
    disableI18n: true,
    disableHelpers: true,
    templateExtension: 'html'
  }
})
