/*
 * Copyright 2012-2014 the original author or authors.
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

glowroot.controller('AdhocPointcutListCtrl', [
  '$scope',
  '$http',
  '$timeout',
  'httpErrors',
  function ($scope, $http, $timeout, httpErrors) {
    // initialize page binding object
    $scope.page = {};

    $http.get('backend/config/adhoc-pointcut')
        .success(function (data) {
          $scope.loaded = true;
          $scope.adhocPointcuts = [];
          for (var i = 0; i < data.configs.length; i++) {
            $scope.adhocPointcuts.push({
              config: data.configs[i]
            });
          }
          // use object so dirty flag can be updated by child controllers
          $scope.page.dirty = data.jvmOutOfSync;
          $scope.jvmRetransformClassesSupported = data.jvmRetransformClassesSupported;
          // pre-load cache for class name and method name auto completion
          $http.get('backend/adhoc-pointcut/pre-load-auto-complete');
        })
        .error(httpErrors.handler($scope));

    $scope.addAdhocPointcut = function () {
      $scope.adhocPointcuts.push({
        config: {}
      });
    };

    // this is called by child controller
    $scope.removeAdhocPointcut = function (adhocPointcut) {
      // indexOf polyfill for IE8 is provided by es5-shim
      var index = $scope.adhocPointcuts.indexOf(adhocPointcut);
      if (index !== -1) {
        $scope.adhocPointcuts.splice(index, 1);
      }
    };

    $scope.retransformClasses = function (deferred) {
      $http.post('backend/admin/reweave-adhoc-pointcuts', '')
          .success(function (data) {
            $scope.page.dirty = false;
            if (data.classes) {
              deferred.resolve('Success (re-transformed ' + data.classes + ' class' + (data.classes > 1 ? 'es' : '') +
                  ')');
            } else {
              deferred.resolve('Success (no classes needed re-transforming)');
            }
          })
          .error(httpErrors.handler($scope, deferred));
    };
  }
]);
