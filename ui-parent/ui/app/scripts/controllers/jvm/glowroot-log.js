/*
 * Copyright 2015 the original author or authors.
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

glowroot.controller('JvmGlowrootLogCtrl', [
  '$scope',
  '$http',
  'httpErrors',
  function ($scope, $http, httpErrors) {

    $scope.exportAsText = function () {
      var textWindow = window.open();
      $(textWindow.document.body).html($scope.glowrootLog.replace(/\n/g, '<br>').replace(/ /g, '&nbsp;'));
    };

    $scope.refresh = function (deferred) {
      $http.get('backend/jvm/glowroot-log')
          .success(function (data) {
            $scope.loaded = true;
            $scope.glowrootLog = data;
            if (deferred) {
              deferred.resolve('Refreshed');
            }
          })
          .error(httpErrors.handler($scope, deferred));
    };

    $scope.refresh();
  }
]);
