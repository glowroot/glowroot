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

/* global glowroot, angular */

glowroot.controller('AdminWebCtrl', [
  '$scope',
  '$http',
  '$rootScope',
  '$location',
  '$timeout',
  'confirmIfHasChanges',
  'httpErrors',
  function ($scope, $http, $rootScope, $location, $timeout, confirmIfHasChanges, httpErrors) {

    $scope.hasChanges = function () {
      return $scope.originalConfig && !angular.equals($scope.config, $scope.originalConfig);
    };
    $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    function onNewData(data) {
      $scope.loaded = true;
      $scope.config = data.config;
      $scope.originalConfig = angular.copy(data.config);
      $scope.activePort = data.activePort;
      $scope.activeBindAddress = data.activeBindAddress;
    }

    $scope.save = function (deferred) {
      // another copy to modify for the http post data
      var postData = angular.copy($scope.config);
      var changingPort = false;
      var previousActivePort;
      if ($scope.originalConfig.port !== $scope.config.port) {
        changingPort = true;
        previousActivePort = $scope.activePort;
      }
      $http.post('backend/admin/web', postData)
          .success(function (data) {
            onNewData(data);
            if (!changingPort) {
              // normal path
              deferred.resolve('Saved');
              return;
            }
            if (changingPort && data.portChangeFailed) {
              deferred.reject('Save succeeded, but switching over to the new port failed');
              return;
            }
            if ($location.port() !== previousActivePort) {
              deferred.reject('The save succeeded, and switching the http listener over to the new port' +
              ' succeeded, but you are not being redirected to the new port since it seems you are using an' +
              ' intermediary proxy?');
              return;
            }
            deferred.resolve('Saved, redirecting to new port ...');
            $timeout(function () {
              var newUrl = $location.protocol() + '://' + $location.host();
              if (data.activePort !== 80) {
                newUrl += ':' + data.activePort;
              }
              newUrl += $location.path();
              document.location.href = newUrl;
            }, 500);
          })
          .error(httpErrors.handler($scope, deferred));
    };

    $http.get('backend/admin/web')
        .success(onNewData)
        .error(httpErrors.handler($scope));
  }
]);
