/*
 * Copyright 2012-2013 the original author or authors.
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

glowroot.controller('PointcutListCtrl', [
  '$scope',
  '$http',
  '$timeout',
  'httpErrors',
  function ($scope, $http, $timeout, httpErrors) {
    // initialize page binding object
    $scope.page = {};

    $http.get('backend/config/pointcut')
        .success(function (data) {
          $scope.loaded = true;
          $scope.pointcuts = [];
          for (var i = 0; i < data.configs.length; i++) {
            $scope.pointcuts.push({
              config: data.configs[i]
            });
          }
          // use object so dirty flag can be updated by child controllers
          $scope.page.dirty = data.jvmOutOfSync;
          $scope.jvmRetransformClassesSupported = data.jvmRetransformClassesSupported;
          // pre-load cache for class name and method name auto completion
          $http.post('backend/pointcut/pre-load-auto-complete');
        })
        .error(httpErrors.handler($scope));

    $scope.addPointcut = function () {
      $scope.pointcuts.push({
        config: {}
      });
    };

    // this is called by child controller below
    $scope.removePointcut = function (pointcut) {
      var index = $scope.pointcuts.indexOf(pointcut);
      if (index !== -1) {
        $scope.pointcuts.splice(index, 1);
      }
    };

    $scope.retransformClasses = function (deferred) {
      $http.post('backend/admin/pointcuts/reweave', '')
          .success(function (data) {
            $scope.page.dirty = false;
            deferred.resolve('Success');
          })
          .error(httpErrors.handler($scope, deferred));
    };
  }
]);
