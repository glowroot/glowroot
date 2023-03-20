/*
 * Copyright 2018-2023 the original author or authors.
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

glowroot.controller('AdminJsonCtrl', [
  '$scope',
  '$http',
  'confirmIfHasChanges',
  'httpErrors',
  function ($scope, $http, confirmIfHasChanges, httpErrors) {

    function onNewData(data) {
      $scope.loaded = true;
      // strip out version
      $scope.adminJson = data.replace(/,\s*"version":\s*\"[0-9a-f]{40}\"/, '');
      $scope.configVersion = JSON.parse(data).version;
      $scope.originalAdminJson = $scope.adminJson;
    }

    $scope.hasChanges = function () {
      return $scope.originalAdminJson !== undefined && !angular.equals($scope.adminJson, $scope.originalAdminJson);
    };
    $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    $scope.adminJsonLines = function () {
      if ($scope.adminJson === undefined) {
        return 10;
      }
      return Math.max($scope.adminJson.split(/\n/).length, 10);
    };

    $scope.save = function (deferred) {
      var postData;
      try {
        postData = JSON.parse($scope.adminJson);
      }
      catch (error) {
        deferred.reject(error);
        return;
      }
      // put back version
      postData.version = $scope.configVersion;
      $http({
        method: 'POST',
        url: 'backend/admin/json',
        data: postData,
        transformResponse: undefined // remove default response transformer so that response won't be converted to an
                                     // object, since want to display json the way server formatted it
      }).then(function (response) {
        onNewData(response.data);
        deferred.resolve('Saved');
      }, function (response) {
        httpErrors.handle(response, deferred);
      });
    };

    $http({
      method: 'GET',
      url: 'backend/admin/json',
      transformResponse: undefined // remove default response transformer so that response won't be converted to an
                                   // object, since want to display json the way server formatted it
    }).then(function (response) {
      onNewData(response.data);
    }, function (response) {
      httpErrors.handle(response);
    });
  }
]);
