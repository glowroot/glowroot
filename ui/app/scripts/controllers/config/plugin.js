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
  'queryStrings',
  function ($scope, $stateParams, $http, $location, confirmIfHasChanges, httpErrors, queryStrings) {

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
        pluginId: $stateParams['plugin-id'],
        properties: properties,
        version: $scope.config.version
      };
      $http.post('backend/config/plugins?agent-id=' + encodeURIComponent($scope.agentId), postData)
          .then(function (response) {
            onNewData(response.data);
            deferred.resolve('Saved');
          }, function (response) {
            httpErrors.handle(response, $scope, deferred);
          });
    };

    var queryData = {
      agentId: $scope.agentId,
      pluginId: $stateParams['plugin-id']
    };
    $http.get('backend/config/plugins' + queryStrings.encodeObject(queryData))
        .then(function (response) {
          onNewData(response.data);
        }, function (response) {
          httpErrors.handle(response, $scope);
        });
  }
]);
