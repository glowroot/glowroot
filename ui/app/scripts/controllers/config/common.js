/*
 * Copyright 2012-2016 the original author or authors.
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

glowroot.controller('ConfigCommonCtrl', [
  '$scope',
  '$http',
  'backendUrl',
  'confirmIfHasChanges',
  'httpErrors',
  function ($scope, $http, backendUrl, confirmIfHasChanges, httpErrors) {

    $scope.hasChanges = function () {
      return $scope.originalConfig && !angular.equals($scope.config, $scope.originalConfig);
    };
    $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    function onNewData(data) {
      $scope.loaded = true;
      if (data.config) {
        // originalData is used by advanced config for timerWrapperMethodsActive
        $scope.originalData = angular.copy(data);
        delete $scope.originalData.config;
        $scope.config = data.config;
      } else {
        $scope.config = data;
      }
      $scope.originalConfig = angular.copy($scope.config);
    }

    $scope.save = function (deferred) {
      var postData = angular.copy($scope.config);
      postData.serverId = $scope.serverId;
      $http.post(backendUrl, postData)
          .success(function (data) {
            onNewData(data);
            deferred.resolve('Saved');
          })
          .error(httpErrors.handler($scope, deferred));
    };

    $http.get(backendUrl + '?server-id=' + $scope.serverId)
        .success(onNewData)
        .error(httpErrors.handler($scope));
  }
]);
