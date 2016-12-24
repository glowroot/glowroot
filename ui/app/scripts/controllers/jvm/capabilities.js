/*
 * Copyright 2013-2016 the original author or authors.
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

glowroot.controller('JvmCapabilitiesCtrl', [
  '$scope',
  '$http',
  'httpErrors',
  function ($scope, $http, httpErrors) {

    $scope.$parent.heading = 'Capabilities';

    if ($scope.hideMainContent()) {
      return;
    }

    $http.get('backend/jvm/capabilities?agent-id=' + encodeURIComponent($scope.agentId))
        .then(function (response) {
          $scope.loaded = true;
          $scope.agentNotConnected = response.data.agentNotConnected;
          if ($scope.agentNotConnected) {
            return;
          }
          $scope.capabilities = response.data;
        }, function (response) {
          httpErrors.handle(response, $scope);
        });
  }
]);
