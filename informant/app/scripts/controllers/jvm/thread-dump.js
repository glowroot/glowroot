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

/* global informant, Informant, $, Handlebars, JST */

informant.controller('JvmThreadDumpCtrl', [
  '$scope',
  '$http',
  '$q',
  function ($scope, $http, $q) {

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

    $scope.refresh = function (scroll, deferred) {
      $http.get('backend/jvm/thread-dump')
          .success(function (data) {
            // $.trim() is needed because this template is sensitive to surrounding spaces
            var html = $.trim(JST['thread-dump'](data));
            $('#threadDump').html(html);
            if (scroll) {
              $(window).scrollTop(document.body.scrollHeight);
            }
            if (deferred) {
              deferred.resolve('Refreshed');
            }
          })
          .error(function (data, status) {
            // TODO handle for initial load (!deferred)
            if (deferred) {
              if (status === 0) {
                deferred.reject('Unable to connect to server');
              } else {
                deferred.reject('An error occurred');
              }
            }
          });
    };

    var deferred = $q.defer();
    var spinner = Informant.showSpinner('#initialLoadSpinner');
    deferred.promise.then(function () {
      $scope.loaded = true;
      spinner.stop();
    });
    $scope.refresh(false, deferred);
  }
]);
