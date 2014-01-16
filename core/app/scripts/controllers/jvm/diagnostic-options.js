/*
 * Copyright 2013-2014 the original author or authors.
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

glowroot.controller('JvmDiagnosticOptionsCtrl', [
  '$scope',
  '$http',
  'confirmIfHasChanges',
  'httpErrors',
  function ($scope, $http, confirmIfHasChanges, httpErrors) {
    $scope.hasChanges = function () {
      return $scope.originalOptions && !angular.equals($scope.options, $scope.originalOptions);
    };
    $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    function onNewData(data) {
      $scope.loaded = true;
      $scope.options = data;
      $scope.originalOptions = angular.copy(data);
    }

    $scope.update = function (deferred) {
      var postData = {};
      // only pass diff to limit clobbering
      // (and also because re-setting the option to same value will update the option origin to
      // MANAGEMENT)
      var originalOptionsHash = {};
      angular.forEach($scope.originalOptions, function (option) {
        originalOptionsHash[option.name] = option.value;
      });
      angular.forEach($scope.options, function (option) {
        var originalOptionValue = originalOptionsHash[option.name];
        var updatedOptionValue = option.value;
        if (updatedOptionValue !== originalOptionValue) {
          postData[option.name] = updatedOptionValue;
        }
      });
      $http.post('backend/jvm/update-diagnostic-options', postData)
          .success(function (data) {
            onNewData(data);
            deferred.resolve('Updated');
          })
          .error(httpErrors.handler($scope, deferred));
    };

    $http.get('backend/jvm/diagnostic-options')
        .success(onNewData)
        .error(httpErrors.handler($scope));
  }
]);
