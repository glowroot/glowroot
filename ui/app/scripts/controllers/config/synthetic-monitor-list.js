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

glowroot.controller('ConfigSyntheticMonitorListCtrl', [
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
      query.id = config.id;
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

    $http.get('backend/config/synthetic-monitors?agent-rollup-id=' + encodeURIComponent($scope.agentRollupId))
        .then(function (response) {
          $scope.loaded = true;
          $scope.configs = response.data;
        }, function (response) {
          httpErrors.handle(response, $scope);
        });
  }
]);
