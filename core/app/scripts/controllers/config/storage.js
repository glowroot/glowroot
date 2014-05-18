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

glowroot.controller('ConfigStorageCtrl', [
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

    $scope.$watch('page.traceExpirationDays', function (newValue) {
      if ($scope.config) {
        $scope.config.traceExpirationHours = newValue * 24;
      }
    });

    function onNewData(data) {
      $scope.loaded = true;
      $scope.config = data.config;
      $scope.originalConfig = angular.copy(data.config);

      $scope.dataDir = data.dataDir;
      $scope.page.traceExpirationDays = data.config.traceExpirationHours / 24;
    }

    $scope.save = function (deferred) {
      $http.post('backend/config/storage', $scope.config)
          .success(function (data) {
            onNewData(data);
            deferred.resolve('Saved');
          })
          .error(httpErrors.handler($scope, deferred));
    };

    $scope.deleteAll = function (deferred) {
      $http.post('backend/admin/delete-all-data')
          .success(function () {
            deferred.resolve('Deleted');
          })
          .error(httpErrors.handler($scope, deferred));
    };

    $http.get('backend/config/storage')
        .success(onNewData)
        .error(httpErrors.handler($scope));
  }
]);
