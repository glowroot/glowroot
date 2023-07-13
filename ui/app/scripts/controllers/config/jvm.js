/*
 * Copyright 2017-2023 the original author or authors.
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

glowroot.controller('ConfigJvmCtrl', [
  '$scope',
  '$http',
  'confirmIfHasChanges',
  'httpErrors',
  function ($scope, $http, confirmIfHasChanges, httpErrors) {

    // initialize page binding object
    $scope.page = {};

    if ($scope.hideMainContent()) {
      return;
    }

    function splitItems(val) {
      var items = [];
      angular.forEach(val.split(','), function (part) {
        part = part.trim();
        if (part.length) {
          items.push(part);
        }
      });
      return items;
    }

    $scope.$watch('page.maskSystemProperties', function (newVal) {
      if ($scope.config) {
        $scope.config.maskSystemProperties = splitItems(newVal);
      }
    });

    $scope.$watch('page.maskMBeanAttributes', function (newVal) {
      if ($scope.config) {
        $scope.config.maskMBeanAttributes = splitItems(newVal);
      }
    });

    function onNewData(data) {
      $scope.loaded = true;
      $scope.config = data;
      $scope.originalConfig = angular.copy(data);
      $scope.page.maskSystemProperties = $scope.config.maskSystemProperties.join(', ');
      $scope.page.maskMBeanAttributes = $scope.config.maskMBeanAttributes.join(', ');
    }

    $scope.hasChanges = function () {
      return $scope.originalConfig && !angular.equals($scope.config, $scope.originalConfig);
    };
    $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    $scope.save = function (deferred) {
      var postData = angular.copy($scope.config);
      $http.post('backend/config/jvm?agent-id=' + encodeURIComponent($scope.agentId), postData)
          .then(function (response) {
            onNewData(response.data);
            deferred.resolve('Saved');
          }, function (response) {
            httpErrors.handle(response, deferred);
          });
    };

    $http.get('backend/config/jvm?agent-id=' + encodeURIComponent($scope.agentId))
        .then(function (response) {
          onNewData(response.data);
        }, function (response) {
          httpErrors.handle(response);
        });
  }
]);
