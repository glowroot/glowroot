/*
 * Copyright 2016-2017 the original author or authors.
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

/* global glowroot, $ */

glowroot.controller('JvmJstackCtrl', [
  '$scope',
  '$http',
  'httpErrors',
  function ($scope, $http, httpErrors) {

    $scope.$parent.heading = 'Thread dump: jstack';

    if ($scope.hideMainContent()) {
      return;
    }

    var jstack;

    $scope.exportAsText = function () {
      var textWindow = window.open();
      $(textWindow.document.body).html('<pre style="white-space: pre-wrap;">' + jstack + '</pre>');
    };

    $scope.refresh = function (deferred) {
      $http.get('backend/jvm/jstack?agent-id=' + encodeURIComponent($scope.agentId))
          .then(function (response) {
            $scope.loaded = true;
            var data = response.data;
            $scope.agentNotConnected = data.agentNotConnected;
            $scope.agentUnsupportedOperation = data.agentUnsupportedOperation;
            $scope.unavailableDueToRunningInJre = data.unavailableDueToRunningInJre;
            $scope.unavailableDueToRunningInIbmJvm = data.unavailableDueToRunningInIbmJvm;
            if ($scope.agentNotConnected || $scope.agentUnsupportedOperation || $scope.unavailableDueToRunningInJre
                || $scope.unavailableDueToRunningInIbmJvm) {
              return;
            }
            jstack = data.jstack;
            $('#jstack').html('<br>' + jstack);
            if (deferred) {
              deferred.resolve('Refreshed');
            }
          }, function (response) {
            httpErrors.handle(response, $scope, deferred);
          });
    };

    $scope.refresh();
  }
]);
