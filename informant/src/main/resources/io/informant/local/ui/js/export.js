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

require.config({
  paths: {
    'handlebars': 'lib/handlebars/handlebars.runtime',
    'jquery': 'lib/jquery/jquery',
    'hbs': 'lib/hbs/hbs',
    'underscore': 'lib/hbs/underscore',
    'json2': 'lib/hbs/json2',
    'i18nprecompile': 'lib/hbs/i18nprecompile',
    'trace': 'js/common-trace',
    'moment': 'lib/moment/moment',
    'export': 'js/export'
  },
  shim: {
    'handlebars': {
      exports: 'Handlebars'
    },
    'moment': {
      exports: 'moment'
    }
  },
  hbs: {
    disableI18n: true,
    disableHelpers: true,
    templateExtension: 'html'
  }
});

// cannot be anonymous (unnamed) module since it is injected into a <script> tag inside export.html
define('js/export', ['jquery', 'trace'], function () {});
