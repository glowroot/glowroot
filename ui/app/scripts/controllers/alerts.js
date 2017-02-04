/*
 * Copyright 2017 the original author or authors.
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

/* global glowroot */

glowroot.controller('AlertsCtrl', [
  '$scope',
  '$http',
  '$timeout',
  'httpErrors',
  function ($scope, $http, $timeout, httpErrors) {
    // \u00b7 is &middot;
    document.title = 'Alerts \u00b7 Glowroot';
    $scope.$parent.activeNavbarItem = 'alert';

    function refresh() {
      $http.get('backend/alerts')
          .then(function (response) {
            $scope.loaded = true;
            $scope.alerts = response.data;
          }, function (response) {
            httpErrors.handle(response, $scope);
          });
    }

    refresh();

    var timer;

    function onVisible() {
      $scope.$apply(function () {
        refresh();
      });
      document.removeEventListener('visibilitychange', onVisible);
    }

    function scheduleNextRefresh() {
      timer = $timeout(function () {
        // document.hidden is not supported by IE9 but that's ok, the condition will just evaluate to false
        // and auto refresh will continue even while hidden under IE9
        if (document.hidden) {
          document.addEventListener('visibilitychange', onVisible);
        } else {
          refresh();
        }
        scheduleNextRefresh();
      }, 30000);
    }

    scheduleNextRefresh();

    $scope.$on('$destroy', function () {
      $timeout.cancel(timer);
    });
  }
]);
