/*
 * Copyright 2017 the original author or authors.
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

glowroot.controller('AdminHealthchecksIoCtrl', [
  '$scope',
  '$http',
  'confirmIfHasChanges',
  'httpErrors',
  function ($scope, $http, confirmIfHasChanges, httpErrors) {

    $scope.hasChanges = function () {
      return $scope.originalConfig && !angular.equals($scope.config, $scope.originalConfig);
    };
    $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    function onNewData(data) {
      $scope.loaded = true;
      $scope.config = data;
      $scope.originalConfig = angular.copy(data);
    }

    $scope.save = function (deferred) {
      $http.post('backend/admin/healthchecks-io', $scope.config)
          .then(function (response) {
            deferred.resolve('Saved');
            onNewData(response.data);
          }, function (response) {
            httpErrors.handle(response, $scope, deferred);
          });
    };

    if ($scope.layout.central) {
      $scope.loaded = true;
    } else {
      $http.get('backend/admin/healthchecks-io')
          .then(function (response) {
            onNewData(response.data);
          }, function (response) {
            httpErrors.handle(response, $scope);
          });
    }
  }
]);
