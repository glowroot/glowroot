/*
 * Copyright 2012-2014 the original author or authors.
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

glowroot.controller('ConfigProfilingCtrl', [
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

    $scope.$watchCollection('[page.traceStoreThresholdOverride, page.traceStoreThresholdOverrideMillis]',
        function (newValues) {
          if (newValues[0] === undefined) {
            // initial
            return;
          }
          if (newValues[0]) {
            $scope.config.traceStoreThresholdOverrideMillis = newValues[1];
          } else {
            $scope.config.traceStoreThresholdOverrideMillis = -1;
            // update disabled input text to show the overridden value from general config
            $scope.page.traceStoreThresholdOverrideMillis = $scope.defaultTraceStoreThresholdMillis;
          }
        });

    function onNewData(data) {
      $scope.loaded = true;
      $scope.config = data.config;
      $scope.originalConfig = angular.copy(data.config);

      $scope.defaultTraceStoreThresholdMillis = data.defaultTraceStoreThresholdMillis;
      if (data.config.traceStoreThresholdOverrideMillis === -1) {
        $scope.page.traceStoreThresholdOverride = false;
      } else {
        $scope.page.traceStoreThresholdOverride = true;
        $scope.page.traceStoreThresholdOverrideMillis = $scope.config.traceStoreThresholdOverrideMillis;
      }
    }

    $scope.save = function (deferred) {
      $http.post('backend/config/profiling', $scope.config)
          .success(function (data) {
            onNewData(data);
            deferred.resolve('Saved');
          })
          .error(httpErrors.handler($scope, deferred));
    };

    $http.get('backend/config/profiling')
        .success(onNewData)
        .error(httpErrors.handler($scope));
  }
]);
