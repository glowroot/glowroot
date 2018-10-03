/*
 * Copyright 2012-2018 the original author or authors.
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
      var config = angular.copy($scope.config);
      angular.forEach(config.properties, function (property) {
        if (property.type === 'list') {
          var value = [];
          angular.forEach(property.value, function (v) {
            if (v.trim() !== '') {
              value.push(v);
            }
          });
          property.value = value;
        }
      });
      return !angular.equals(config, $scope.originalConfig);
    };
    $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    $scope.$watch('config.properties', function (newValue, oldValue) {
      if (newValue === undefined) {
        return;
      }
      angular.forEach(newValue, function (property) {
        if (property.type === 'list'
            && (!property.value.length || property.value[property.value.length - 1].trim() !== '')) {
          property.value.push('');
        }
      });
    }, true);

    $scope.save = function (deferred) {
      var properties = [];
      angular.forEach($scope.config.properties, function (property) {
        var value;
        if (property.type === 'list') {
          value = [];
          angular.forEach(property.value, function (v) {
            if (v.trim() !== '') {
              value.push(v);
            }
          });
        } else {
          value = property.value;
        }
        properties.push({
          name: property.name,
          type: property.type,
          value: value
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
