/*
 * Copyright 2015-2019 the original author or authors.
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

glowroot.controller('ConfigAlertListCtrl', [
  '$scope',
  '$location',
  '$http',
  '$filter',
  'queryStrings',
  'httpErrors',
  function ($scope, $location, $http, $filter, queryStrings, httpErrors) {

    if ($scope.hideMainContent()) {
      return;
    }

    $scope.configQueryString = function (config) {
      var query = {};
      if ($scope.agentId) {
        query.agentId = $scope.agentId;
      } else if ($scope.agentRollupId) {
        query.agentRollupId = $scope.agentRollupId;
      }
      query.v = config.version;
      return queryStrings.encodeObject(query);
    };

    $scope.newQueryString = function () {
      var queryString = $scope.agentQueryString();
      if (queryString === '') {
        return '?new';
      } else {
        return queryString + '&new';
      }
    };

    $scope.someAlertsDisabled = function () {
      for (var i = 0; i < $scope.configs.length; i++) {
        if ($scope.configs[i].disabled) {
          return true;
        }
      }
      return false;
    };

    $scope.someAlertsEnabled = function () {
      for (var i = 0; i < $scope.configs.length; i++) {
        if (!$scope.configs[i].disabled) {
          return true;
        }
      }
      return false;
    };

    $scope.disableAllAlerts = function (deferred) {
      $http.post('backend/config/alerts/disable-all?agent-rollup-id=' + encodeURIComponent($scope.agentRollupId))
          .then(function (response) {
            $scope.loaded = true;
            $scope.configs = response.data;
            deferred.resolve('All disabled');
          }, function (response) {
            httpErrors.handle(response, $scope, deferred);
          });
    };

    $scope.enableAllAlerts = function (deferred) {
      $http.post('backend/config/alerts/enable-all?agent-rollup-id=' + encodeURIComponent($scope.agentRollupId))
          .then(function (response) {
            $scope.loaded = true;
            $scope.configs = response.data;
            deferred.resolve('All enabled');
          }, function (response) {
            httpErrors.handle(response, $scope, deferred);
          });
    };

    $http.get('backend/config/alerts?agent-rollup-id=' + encodeURIComponent($scope.agentRollupId))
        .then(function (response) {
          $scope.loaded = true;
          $scope.configs = response.data;
        }, function (response) {
          httpErrors.handle(response, $scope);
        });
  }
]);
