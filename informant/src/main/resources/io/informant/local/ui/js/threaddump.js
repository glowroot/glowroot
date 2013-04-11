/*
 * Copyright 2012-2013 the original author or authors.
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
    'informant': 'common',
    'handlebars': '../lib/handlebars/handlebars.runtime',
    'jquery': '../lib/jquery/jquery',
    'spin': '../lib/spin/spin'
  },
  shim: {
    'handlebars': {
      exports: 'Handlebars'
    },
    'spin': {
      exports: 'Spinner'
    }
  }
});

define(function (require) {
  'use strict';
  var $ = require('jquery');
  var Handlebars = require('handlebars');
  var Informant = require('informant');

  var threadDumpTemplate = Handlebars.compile($('#threadDumpTemplate').html());

  Handlebars.registerHelper('ifBlocked', function (state, options) {
    if (state === 'BLOCKED') {
      return options.fn(this);
    } else {
      return options.inverse(this);
    }
  });

  Handlebars.registerHelper('ifWaiting', function (state, options) {
    if (state === 'WAITING' || state === 'TIMED_WAITING') {
      return options.fn(this);
    } else {
      return options.inverse(this);
    }
  });

  function refresh(scroll) {
    $.getJSON('threads/dump', function (threads) {
      // $.trim() is needed because this template is sensitive to surrounding spaces
      var html = $.trim(threadDumpTemplate(threads));
      $('#threadDump').html(html);
      if (scroll) {
        $(window).scrollTop(document.body.scrollHeight);
      }
    });
  }

  $(document).ready(function () {
    Informant.configureAjaxError();
    $('#refreshButton1').click(function () {
      refresh.bind(false);
      Informant.showAndFadeSuccessMessage('#refresh1SuccessMessage');
    });
    $('#refreshButton2').click(function () {
      refresh.bind(true);
      Informant.showAndFadeSuccessMessage('#refresh2SuccessMessage');
    });
    refresh(false);
  });
});
