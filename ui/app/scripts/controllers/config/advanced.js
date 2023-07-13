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

glowroot.controller('ConfigAdvancedCtrl', [
  '$scope',
  '$location',
  '$http',
  'confirmIfHasChanges',
  'httpErrors',
  function ($scope, $location, $http, confirmIfHasChanges, httpErrors) {

    if ($scope.hideMainContent()) {
      return;
    }

    $scope.hasChanges = function () {
      return $scope.originalConfig && !angular.equals($scope.config, $scope.originalConfig);
    };
    $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    function onNewData(data) {
      $scope.loaded = true;
      $scope.config = data;
      $scope.originalConfig = angular.copy($scope.config);
    }

    $scope.save = function (deferred) {
      var postData = angular.copy($scope.config);
      $http.post('backend/config/advanced?agent-rollup-id=' + encodeURIComponent($scope.agentRollupId), postData)
          .then(function (response) {
            onNewData(response.data);
            deferred.resolve('Saved');
          }, function (response) {
            httpErrors.handle(response, deferred);
          });
    };

    $http.get('backend/config/advanced?agent-rollup-id=' + encodeURIComponent($scope.agentRollupId))
        .then(function (response) {
          onNewData(response.data);
        }, function (response) {
          httpErrors.handle(response);
        });
  }
]);
