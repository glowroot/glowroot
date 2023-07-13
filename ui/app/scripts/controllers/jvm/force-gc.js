/*
 * Copyright 2016-2023 the original author or authors.
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

/* global glowroot */

glowroot.controller('JvmForceGcCtrl', [
  '$scope',
  '$http',
  'httpErrors',
  function ($scope, $http, httpErrors) {

    $scope.$parent.heading = 'Force GC';

    if ($scope.hideMainContent()) {
      return;
    }

    $scope.forceGC = function (deferred) {
      $http.post('backend/jvm/force-gc?agent-id=' + encodeURIComponent($scope.agentId))
          .then(function () {
            deferred.resolve('Success');
          }, function (response) {
            httpErrors.handle(response, deferred);
          });
    };

    $http.get('backend/jvm/explicit-gc-disabled?agent-id=' + encodeURIComponent($scope.agentId))
        .then(function (response) {
          $scope.loaded = true;
          $scope.agentNotConnected = response.data.agentNotConnected;
          $scope.explicitGcDisabled = response.data.explicitGcDisabled;
        }, function (response) {
          httpErrors.handle(response);
        });
  }
]);
