/*
 * Copyright 2012-2015 the original author or authors.
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

glowroot.controller('ConfigCapturePointListCtrl', [
  '$scope',
  '$location',
  '$http',
  '$timeout',
  'httpErrors',
  function ($scope, $location, $http, $timeout, httpErrors) {

    $scope.display = function (capturePoint) {
      return capturePoint.className + '::' + capturePoint.methodName;
    };

    $scope.displayExtra = function (capturePoint) {
      var captureKind = capturePoint.captureKind;
      if (captureKind === 'metric') {
        return 'Metric';
      } else if (captureKind === 'trace-entry') {
        return 'Trace entry';
      } else if (captureKind === 'transaction') {
        return 'Transaction';
      } else {
        return 'Other';
      }
    };

    $scope.addNew = function () {
      $location.url('config/capture-point?new');
    };

    $http.get('backend/config/capture-points')
        .success(function (data) {
          $scope.loaded = true;
          $scope.capturePoints = data.configs;
          // use object so dirty flag can be updated by child controllers
          $scope.dirty = data.jvmOutOfSync;
          $scope.jvmRetransformClassesSupported = data.jvmRetransformClassesSupported;
          // preload cache for class name and method name auto completion
          $http.get('backend/config/preload-classpath-cache');
        })
        .error(httpErrors.handler($scope));

    $scope.retransformClasses = function (deferred) {
      $http.post('backend/admin/reweave-capture-points', '')
          .success(function (data) {
            $scope.dirty = false;
            if (data.classes) {
              var msg = 're-transformed ' + data.classes + ' class' + (data.classes > 1 ? 'es' : '');
              deferred.resolve('Success (' + msg + ')');
            } else {
              deferred.resolve('Success (no classes needed re-transforming)');
            }
          })
          .error(httpErrors.handler($scope, deferred));
    };
  }
]);
