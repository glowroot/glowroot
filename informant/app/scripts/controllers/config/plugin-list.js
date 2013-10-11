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

/* global informant, Informant, angular */

informant.controller('ConfigPluginListCtrl', [
  '$scope',
  '$http',
  function ($scope, $http) {
    var spinner = Informant.showSpinner('#initialLoadSpinner');
    $http.get('backend/config/plugin-section')
        .success(function (data) {
          spinner.stop();

          $scope.config = data;
          $scope.plugins = [];
          var i, j;
          for (i = 0; i < data.descriptors.length; i++) {
            var plugin = {};
            plugin.descriptor = data.descriptors[i];
            plugin.id = plugin.descriptor.groupId + ':' + plugin.descriptor.artifactId;
            plugin.config = data.configs[plugin.id];
            for (j = 0; j < plugin.descriptor.properties.length; j++) {
              var property = plugin.descriptor.properties[j];
              property.value = plugin.config.properties[property.name];
            }
            $scope.plugins.push(plugin);
          }
        })
        .error(function (error) {
          // TODO
        });
  }
]);

informant.controller('ConfigPluginCtrl', [
  '$scope',
  '$http',
  function ($scope, $http) {
    // need to track entire plugin object since properties are under plugin.descriptor.properties
    // and enabled is under plugin.config.enabled
    var originalPlugin = angular.copy($scope.plugin);

    $scope.hasChanges = function () {
      return !angular.equals($scope.plugin, originalPlugin);
    };

    $scope.save = function (deferred) {
      var properties = {};
      var i;
      for (i = 0; i < $scope.plugin.descriptor.properties.length; i++) {
        var property = $scope.plugin.descriptor.properties[i];
        if (property.type === 'double') {
          properties[property.name] = parseFloat(property.value);
        } else {
          properties[property.name] = property.value;
        }
      }
      var config = {
        enabled: $scope.plugin.config.enabled,
        properties: properties,
        version: $scope.plugin.config.version
      };
      $http.post('backend/config/plugin/' + $scope.plugin.id, config)
          .success(function (data) {
            $scope.plugin.config.version = data;
            originalPlugin = angular.copy($scope.plugin);
            deferred.resolve('Saved');
          })
          .error(function (data, status) {
            if (status === 0) {
              deferred.reject('Unable to connect to server');
            } else {
              deferred.reject('An error occurred');
            }
          });
    };
  }
]);
