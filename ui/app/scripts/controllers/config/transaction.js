/*
 * Copyright 2012-2019 the original author or authors.
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

    var defaultTransactionType;

    $scope.hasChanges = function () {
      return $scope.originalConfig && !angular.equals($scope.config, $scope.originalConfig);
    };
    $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    function onNewData(data) {
      $scope.loaded = true;
      $scope.config = data.config;
      $scope.originalConfig = angular.copy($scope.config);
      $scope.allTransactionTypes = [];
      angular.forEach(data.allTransactionTypes, function (transactionType) {
        $scope.allTransactionTypes.push({
          name: transactionType
        });
      });
      angular.forEach($scope.config.slowThresholdOverrides, function (slowThresholdOverride) {
        if (data.allTransactionTypes.indexOf(slowThresholdOverride.transactionType) === -1) {
          $scope.allTransactionTypes.push({
            name: slowThresholdOverride.transactionType,
            disabled: true
          });
        }
      });
      defaultTransactionType = data.defaultTransactionType;
    }

    $scope.supportsSlowThresholdOverrides = function () {
      // slow threshold overrides were introduced in agent version 0.10.1
      return !$scope.layout.central || ($scope.agentRollup.glowrootVersion.lastIndexOf('0.9.', 0) === -1
          && $scope.agentRollup.glowrootVersion.lastIndexOf('0.10.0,', 0) === -1);
    };

    $scope.addSlowThresholdOverride = function () {
      $scope.config.slowThresholdOverrides.push({
        transactionType: defaultTransactionType,
        transactionName: '',
        user: '',
        thresholdMillis: null
      });
    };

    $scope.removeSlowThresholdOverride = function (slowThresholdOverride) {
      var index = $scope.config.slowThresholdOverrides.indexOf(slowThresholdOverride);
      $scope.config.slowThresholdOverrides.splice(index, 1);
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
