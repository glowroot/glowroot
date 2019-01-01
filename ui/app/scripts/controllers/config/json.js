/*
 * Copyright 2018-2019 the original author or authors.
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

glowroot.controller('ConfigJsonCtrl', [
  '$scope',
  '$http',
  'confirmIfHasChanges',
  'httpErrors',
  function ($scope, $http, confirmIfHasChanges, httpErrors) {

    if ($scope.hideMainContent()) {
      return;
    }

    function onNewData(data) {
      $scope.loaded = true;
      // strip out version
      $scope.configJson = data.replace(/,\s*"version":\s*\"[0-9a-f]{40}\"/, '');
      $scope.configVersion = JSON.parse(data).version;
      $scope.originalConfigJson = $scope.configJson;
    }

    $scope.hasChanges = function () {
      return $scope.originalConfigJson !== undefined && !angular.equals($scope.configJson, $scope.originalConfigJson);
    };
    $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    $scope.configJsonLines = function () {
      if ($scope.configJson === undefined) {
        return 10;
      }
      return Math.max($scope.configJson.split(/\n/).length, 10);
    };

    function supportsSlowThresholdOverrides() {
      // slow threshold overrides were introduced in agent version 0.10.1
      return !$scope.layout.central || ($scope.agentRollup.glowrootVersion.lastIndexOf('0.9.', 0) === -1
          && $scope.agentRollup.glowrootVersion.lastIndexOf('0.10.0,', 0) === -1);
    }

    $scope.save = function (deferred) {
      var postData;
      try {
        postData = JSON.parse($scope.configJson);
      }
      catch (error) {
        deferred.reject(error);
        return;
      }
      if (!supportsSlowThresholdOverrides() && postData.transaction && postData.transaction.slowThresholds
          && postData.transaction.slowThresholds.length) {
        deferred.reject('Slow threshold overrides not supported in agent versions prior to 0.10.1');
        return;
      }
      // put back version
      postData.version = $scope.configVersion;
      $http({
        method: 'POST',
        url: 'backend/config/json?agent-id=' + encodeURIComponent($scope.agentId),
        data: postData,
        transformResponse: undefined // remove default response transformer so that response won't be converted to an
                                     // object, since want to display json the way server formatted it
      }).then(function (response) {
        onNewData(response.data);
        deferred.resolve('Saved');
      }, function (response) {
        httpErrors.handle(response, $scope, deferred);
      });
    };

    $http({
      method: 'GET',
      url: 'backend/config/json?agent-id=' + encodeURIComponent($scope.agentId),
      transformResponse: undefined // remove default response transformer so that response won't be converted to an
                                   // object, since want to display json the way server formatted it
    }).then(function (response) {
      onNewData(response.data);
    }, function (response) {
      httpErrors.handle(response, $scope);
    });
  }
]);
