/*
 * Copyright 2015 the original author or authors.
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

glowroot.controller('ConfigGeneralCtrl', [
  '$scope',
  '$http',
  'backendUrl',
  'confirmIfHasChanges',
  'httpErrors',
  function ($scope, $http, backendUrl, confirmIfHasChanges, httpErrors) {
    // initialize page binding object
    $scope.page = {};

    $scope.hasChanges = function () {
      return $scope.originalConfig && !angular.equals($scope.config, $scope.originalConfig);
    };
    $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    $scope.$watch('page.defaultDisplayedPercentiles', function (newVal) {
      if ($scope.config) {
        var percentiles = [];
        angular.forEach(newVal.split(','), function (percentile) {
          percentile = percentile.trim();
          if (percentile.length) {
            percentiles.push(Number(percentile));
          }
        });
        $scope.config.defaultDisplayedPercentiles = percentiles;
      }
    });

    function onNewData(data) {
      $scope.loaded = true;
      $scope.config = data;
      $scope.originalConfig = angular.copy($scope.config);

      $scope.page.defaultDisplayedPercentiles = $scope.config.defaultDisplayedPercentiles.join(', ');
    }

    $scope.save = function (deferred) {
      $http.post(backendUrl, $scope.config)
          .success(function (data) {
            onNewData(data);
            deferred.resolve('Saved');
          })
          .error(httpErrors.handler($scope, deferred));
    };

    $http.get(backendUrl)
        .success(onNewData)
        .error(httpErrors.handler($scope));
  }
]);
