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

/* global informant, angular */

informant.controller('ConfigFineProfilingCtrl', [
  '$scope',
  '$http',
  'confirmIfHasChanges',
  'httpErrors',
  function ($scope, $http, confirmIfHasChanges, httpErrors) {
    // initialize page binding object
    $scope.page = {};

    $scope.hasChanges = function () {
      return $scope.originalConfig && !angular.equals($scope.config, $scope.originalConfig);
    };
    $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    $scope.$watchCollection('[page.fineStoreThresholdOverride, page.fineStoreThresholdMillis]', function (newValues) {
      if (newValues[0] === undefined) {
        // initial
        return;
      }
      if (newValues[0]) {
        $scope.config.storeThresholdMillis = newValues[1];
      } else {
        $scope.config.storeThresholdMillis = -1;
        // update disabled input text to show the overridden value from general config
        $scope.page.fineStoreThresholdMillis = $scope.generalStoreThresholdMillis;
      }
    });

    function onNewData(data) {
      $scope.loaded = true;
      $scope.config = data.config;
      $scope.originalConfig = angular.copy(data.config);

      $scope.generalStoreThresholdMillis = data.generalStoreThresholdMillis;
      if (data.config.storeThresholdMillis === -1) {
        $scope.page.fineStoreThresholdOverride = false;
      } else {
        $scope.page.fineStoreThresholdOverride = true;
        $scope.page.fineStoreThresholdMillis = $scope.config.storeThresholdMillis;
      }
    }

    $scope.save = function (deferred) {
      $http.post('backend/config/fine-profiling', $scope.config)
          .success(function (data) {
            onNewData(data);
            deferred.resolve('Saved');
          })
          .error(httpErrors.handler($scope, deferred));
    };

    $http.get('backend/config/fine-profiling')
        .success(onNewData)
        .error(httpErrors.handler($scope));
  }
]);
