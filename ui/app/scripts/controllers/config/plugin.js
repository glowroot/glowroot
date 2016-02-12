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

glowroot.controller('ConfigPluginCtrl', [
  '$scope',
  '$stateParams',
  '$http',
  '$location',
  'confirmIfHasChanges',
  'httpErrors',
  function ($scope, $stateParams, $http, $location, confirmIfHasChanges, httpErrors) {

    function onNewData(data) {
      $scope.loaded = true;
      $scope.config = data;
      $scope.originalConfig = angular.copy($scope.config);
    }

    $scope.hasChanges = function () {
      return !angular.equals($scope.config, $scope.originalConfig);
    };
    $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    $scope.save = function (deferred) {
      var properties = [];
      angular.forEach($scope.config.properties, function (property) {
        properties.push({
          name: property.name,
          type: property.type,
          value: property.value
        });
      });
      var postData = {
        agentId: $scope.agentId,
        pluginId: $stateParams['plugin-id'],
        properties: properties,
        version: $scope.config.version
      };
      $http.post('backend/config/plugins', postData)
          .success(function (data) {
            onNewData(data);
            deferred.resolve('Saved');
            if (postData.agentId) {
              $location.url('config/plugin-list?agent-id=' + encodeURIComponent($scope.agentId));
            } else {
              $location.url('config/plugin-list');
            }
          })
          .error(httpErrors.handler($scope, deferred));
    };

    $http.get('backend/config/plugins?agent-id=' + encodeURIComponent($scope.agentId) + '&plugin-id=' + $stateParams['plugin-id'])
        .success(function (data) {
          onNewData(data);
        })
        .error(httpErrors.handler($scope));
  }
]);
