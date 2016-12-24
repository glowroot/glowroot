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

glowroot.controller('JvmEnvironmentCtrl', [
  '$scope',
  '$http',
  'httpErrors',
  function ($scope, $http, httpErrors) {

    $scope.$parent.heading = 'Environment';

    if ($scope.hideMainContent()) {
      return;
    }

    // splitting the arg is used to wrap initial two characters is non-wrapping css
    // to prevent long lines from splitting right after the initial dash

    $scope.argFirstPart = function (arg) {
      if (arg.length > 1 && arg[0] === '-') {
        return arg.substring(0, 2);
      } else {
        return '';
      }
    };

    $scope.argSecondPart = function (arg) {
      if (arg.length > 1 && arg[0] === '-') {
        return arg.substring(2);
      } else {
        return arg;
      }
    };

    $http.get('backend/jvm/environment?agent-id=' + encodeURIComponent($scope.agentId))
        .then(function (response) {
          $scope.loaded = true;
          $scope.data = response.data;
          $scope.uptime = Date.now() - response.data.process.startTime;
        }, function (response) {
          httpErrors.handle(response, $scope);
        });
  }
]);
