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

/* global glowroot, angular */

glowroot.controller('PluginXCtrl', [
  '$scope',
  '$stateParams',
  '$http',
  'confirmIfHasChanges',
  'httpErrors',
  function ($scope, $stateParams, $http, confirmIfHasChanges, httpErrors) {

    function onNewData(data) {
      $scope.loaded = true;
      $scope.plugin = data;
      $scope.config = {
        enabled: data.config.enabled,
        version: data.config.version
      };
      $scope.config.properties = angular.copy(data.descriptor.properties);
      for (var j = 0; j < $scope.config.properties.length; j++) {
        var property = $scope.config.properties[j];
        property.value = data.config.properties[property.name];
      }
      $scope.originalConfig = angular.copy($scope.config);
    }

    $scope.hasChanges = function () {
      return !angular.equals($scope.config, $scope.originalConfig);
    };
    $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    $scope.save = function (deferred) {
      var postData = {
        enabled: $scope.config.enabled,
        properties: {},
        version: $scope.config.version
      };
      for (var i = 0; i < $scope.config.properties.length; i++) {
        var property = $scope.config.properties[i];
        postData.properties[property.name] = property.value;
      }
      $http.post('backend/config/plugin/' + $stateParams.id, postData)
          .success(function (data) {
            onNewData(data);
            deferred.resolve('Saved');
          })
          .error(httpErrors.handler($scope, deferred));
    };

    $http.get('backend/config/plugin/' + $stateParams.id)
        .success(function (data) {
          onNewData(data);
        })
        .error(httpErrors.handler($scope));
  }
]);
