/*
 * Copyright 2012-2016 the original author or authors.
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

glowroot.controller('ConfigGaugeListCtrl', [
  '$scope',
  '$location',
  '$http',
  'queryStrings',
  'httpErrors',
  function ($scope, $location, $http, queryStrings, httpErrors) {

    if ($scope.hideMainContent()) {
      return;
    }

    $scope.display = function (gauge) {
      return gauge.config.display.replace(/\//g, '\u200b/');
    };

    $scope.gaugeQueryString = function (gauge) {
      var query = {};
      if ($scope.serverId) {
        query.serverId = $scope.serverId;
      }
      query.v = gauge.config.version;
      return queryStrings.encodeObject(query);
    };

    $scope.newQueryString = function () {
      if ($scope.serverId) {
        return '?server-id=' + encodeURIComponent($scope.serverId) + '&new';
      }
      return '?new';
    };

    $http.get('backend/config/gauges?server-id=' + encodeURIComponent($scope.serverId))
        .success(function (data) {
          $scope.loaded = true;
          $scope.gauges = data;
        })
        .error(httpErrors.handler($scope));
  }
]);
