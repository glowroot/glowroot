/*
 * Copyright 2012-2015 the original author or authors.
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

glowroot.controller('ConfigCtrl', [
  '$scope',
  '$location',
  function ($scope, $location) {
    // \u00b7 is &middot;
    document.title = 'Configuration \u00b7 Glowroot';
    $scope.$parent.activeNavbarItem = 'config';

    $scope.pattern = {
      // tolerant of missing whole (.2) and missing decimal (2.)
      percentage: /^(0|[1-9][0-9]?|100)?(\.[0-9]*)?$/,
      // tolerant of commas
      integer: /^(0|[1-9][0-9]*)$/,
      // missing whole (.2) and missing decimal (2.)
      double: /^(0|[1-9][0-9]*)?(\.[0-9]*)?$/
    };

    $scope.percentileSuffix = function (percentile) {
      var text = String(percentile);
      var lastChar = text.charAt(text.length - 1);
      if (lastChar === '1') {
        return 'st';
      }
      if (lastChar === '2') {
        return 'nd';
      }
      if (lastChar === '3') {
        return 'rd';
      }
      return 'th';
    };

    $scope.isInstrumentation = function () {
      return $location.path() === '/config/instrumentation-list' || $location.path() === '/config/instrumentation';
    };

    $scope.isGauges = function () {
      return $location.path() === '/config/gauge-list' || $location.path() === '/config/gauge';
    };

    $scope.isAlerts = function () {
      return $location.path() === '/config/alert-list' || $location.path() === '/config/alert'
          || $location.path() === '/config/smtp';
    };

    $scope.isPlugins = function () {
      return $location.path() === '/config/plugin-list' || $location.path() === '/config/plugin';
    };
  }
]);
