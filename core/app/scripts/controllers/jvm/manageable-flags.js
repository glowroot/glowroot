/*
 * Copyright 2013 the original author or authors.
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

glowroot.controller('JvmManageableFlagsCtrl', [
  '$scope',
  '$http',
  'confirmIfHasChanges',
  'httpErrors',
  function ($scope, $http, confirmIfHasChanges, httpErrors) {
    $scope.hasChanges = function () {
      return $scope.originalFlags && !angular.equals($scope.flags, $scope.originalFlags);
    };
    $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    function onNewData(data) {
      $scope.loaded = true;
      $scope.flags = data;
      $scope.originalFlags = angular.copy(data);
    }

    $scope.update = function (deferred) {
      var postData = {};
      // only pass diff to limit clobbering
      // (and also because setting flag to same value will update the flag origin to MANAGEMENT)
      var originalFlagsHash = {};
      angular.forEach($scope.originalFlags, function (flag) {
        originalFlagsHash[flag.name] = flag.value;
      });
      angular.forEach($scope.flags, function (flag) {
        var originalFlagValue = originalFlagsHash[flag.name];
        var updatedFlagValue = flag.value;
        if (updatedFlagValue !== originalFlagValue) {
          postData[flag.name] = updatedFlagValue;
        }
      });
      $http.post('backend/jvm/update-manageable-flags', postData)
          .success(function (data) {
            onNewData(data);
            deferred.resolve('Updated');
          })
          .error(httpErrors.handler($scope, deferred));
    };

    $http.get('backend/jvm/manageable-flags')
        .success(onNewData)
        .error(httpErrors.handler($scope));
  }
]);
