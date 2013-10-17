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

/* global informant, angular */

informant.controller('JvmManageableFlagsCtrl', [
  '$scope',
  '$http',
  'httpErrors',
  function ($scope, $http, httpErrors) {
    var originalFlags;

    $scope.hasChanges = function () {
      return originalFlags && !angular.equals($scope.flags, originalFlags);
    };

    $scope.update = function (deferred) {
      // only pass diff to limit clobbering
      // (and also because setting flag to same value will update the flag origin to MANAGEMENT)
      var originalFlagsHash = {};
      angular.forEach(originalFlags, function(flag) {
        originalFlagsHash[flag.name] = flag.value;
      });
      var updatedFlags = {};
      angular.forEach($scope.flags, function(flag) {
        var originalFlagValue = originalFlagsHash[flag.name];
        var updatedFlagValue = flag.value;
        if (updatedFlagValue !== originalFlagValue) {
          updatedFlags[flag.name] = updatedFlagValue;
        }
      });
      $http.post('backend/jvm/update-manageable-flags', updatedFlags)
          .success(function (data) {
            $scope.flags = data;
            originalFlags = angular.copy($scope.flags);
            deferred.resolve('Updated');
          })
          .error(function (data, status) {
            if (status === 0) {
              deferred.reject('Unable to connect to server');
            } else {
              deferred.reject('An error occurred');
            }
          });
    };

    $http.get('backend/jvm/manageable-flags')
        .success(function (data) {
          $scope.loaded = true;
          $scope.flags = data;
          originalFlags = angular.copy($scope.flags);
        })
        .error(function (data, status) {
          $scope.loadingError = httpErrors.get(data, status);
        });
  }
]);
