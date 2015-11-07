/*
 * Copyright 2013-2015 the original author or authors.
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

glowroot.controller('JvmHeapDumpCtrl', [
  '$scope',
  '$http',
  'httpErrors',
  function ($scope, $http, httpErrors) {
    $scope.checkDiskSpace = function (deferred) {
      var postData = {
        serverId: $scope.serverId,
        directory: $scope.directory
      };
      $scope.availableDiskSpace = false;
      $scope.heapDumpResponse = false;
      $http.post('backend/jvm/available-disk-space', postData)
          .success(function (data) {
            if (data.error) {
              deferred.reject(data.error);
            } else {
              $scope.availableDiskSpace = data;
              deferred.resolve('See disk space below');
            }
          })
          .error(httpErrors.handler($scope, deferred));
    };

    $scope.heapDump = function (deferred) {
      var postData = {
        serverId: $scope.serverId,
        directory: $scope.directory
      };
      $scope.availableDiskSpace = false;
      $scope.heapDumpResponse = false;
      $http.post('backend/jvm/heap-dump', postData)
          .success(function (data) {
            if (data.error) {
              deferred.reject(data.error);
            } else {
              deferred.resolve('Heap dump created');
              $scope.heapDumpResponse = data;
            }
          })
          .error(httpErrors.handler($scope, deferred));
    };

    $http.get('backend/jvm/heap-dump-default-dir?server-id=' + $scope.serverId)
        .success(function (directory) {
          $scope.loaded = true;
          $scope.directory = directory;
        })
        .error(httpErrors.handler($scope));
  }
]);
