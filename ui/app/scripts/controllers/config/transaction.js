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

glowroot.controller('ConfigTransactionCtrl', [
  '$scope',
  '$http',
  'confirmIfHasChanges',
  'httpErrors',
  function ($scope, $http, confirmIfHasChanges, httpErrors) {

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

    $scope.addSlowThreshold = function () {
      $scope.config.slowThresholds.push({
        transactionType: '',
        transactionName: '',
        thresholdMillis: null
      });
    };

    $scope.removeSlowThreshold = function (slowThreshold) {
      var index = $scope.config.slowThresholds.indexOf(slowThreshold);
      $scope.config.slowThresholds.splice(index, 1);
    };

    $scope.save = function (deferred) {
      var postData = angular.copy($scope.config);
      $http.post('backend/config/transaction?agent-id=' + encodeURIComponent($scope.agentId), postData)
          .then(function (response) {
            onNewData(response.data);
            deferred.resolve('Saved');
          }, function (response) {
            httpErrors.handle(response, $scope, deferred);
          });
    };

    $http.get('backend/config/transaction?agent-id=' + encodeURIComponent($scope.agentId))
        .then(function (response) {
          onNewData(response.data);
        }, function (response) {
          httpErrors.handle(response, $scope);
        });
  }
]);
