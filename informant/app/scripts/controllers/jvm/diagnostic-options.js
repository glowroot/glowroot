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

informant.controller('JvmDiagnosticOptionsCtrl', [
  '$scope',
  '$http',
  'httpErrors',
  function ($scope, $http, httpErrors) {
    var originalOptions;

    $scope.hasChanges = function () {
      return originalOptions && !angular.equals($scope.options, originalOptions);
    };

    $scope.update = function (deferred) {
      // only pass diff to limit clobbering
      // (and also because setting VMOption to same value will update the VMOption origin to MANAGEMENT)
      var originalOptionsHash = {};
      angular.forEach(originalOptions, function(option) {
        originalOptionsHash[option.name] = option.value;
      });
      var updatedOptions = {};
      angular.forEach($scope.options, function(option) {
        var originalOptionValue = originalOptionsHash[option.name];
        var updatedOptionValue = option.value;
        if (updatedOptionValue !== originalOptionValue) {
          updatedOptions[option.name] = updatedOptionValue;
        }
      });
      $http.post('backend/jvm/update-diagnostic-options', updatedOptions)
          .success(function (data) {
            $scope.options = data;
            originalOptions = angular.copy($scope.options);
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

    $http.get('backend/jvm/diagnostic-options')
        .success(function (data) {
          $scope.loaded = true;
          $scope.options = data;
          originalOptions = angular.copy($scope.options);
        })
        .error(function (data, status) {
          $scope.loadingError = httpErrors.get(data, status);
        });
  }
]);
