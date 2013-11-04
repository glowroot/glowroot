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

/* global informant */

informant.controller('ConfigPluginListCtrl', [
  '$scope',
  '$http',
  'httpErrors',
  function ($scope, $http, httpErrors) {
    $http.get('backend/config/plugin')
        .success(function (data) {
          $scope.loaded = true;
          $scope.plugins = [];
          for (var i = 0; i < data.descriptors.length; i++) {
            var plugin = {};
            plugin.descriptor = data.descriptors[i];
            plugin.id = plugin.descriptor.groupId + ':' + plugin.descriptor.artifactId;
            plugin.config = data.configs[plugin.id];
            $scope.plugins.push(plugin);
          }
        })
        .error(httpErrors.handler($scope));
  }
]);
