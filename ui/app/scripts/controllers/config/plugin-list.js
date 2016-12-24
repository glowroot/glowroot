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

/* global glowroot, angular */

glowroot.controller('ConfigPluginListCtrl', [
  '$scope',
  '$location',
  '$http',
  'queryStrings',
  'httpErrors',
  function ($scope, $location, $http, queryStrings, httpErrors) {

    if ($scope.hideMainContent()) {
      return;
    }

    $scope.pluginQueryString = function (plugin) {
      var query = {};
      if ($scope.agentId) {
        query.agentId = $scope.agentId;
      }
      query.pluginId = plugin.id;
      return queryStrings.encodeObject(query);
    };

    $http.get('backend/config/plugins?agent-id=' + encodeURIComponent($scope.agentId))
        .then(function (response) {
          $scope.loaded = true;
          $scope.plugins = [];
          var pluginsWithNoConfig = [];
          angular.forEach(response.data, function (plugin) {
            if (plugin.hasConfig) {
              $scope.plugins.push(plugin);
            } else {
              pluginsWithNoConfig.push(plugin.name);
            }
          });
          $scope.pluginsWithNoConfig = pluginsWithNoConfig.join(', ');
        }, function (response) {
          httpErrors.handle(response, $scope);
        });
  }
]);
