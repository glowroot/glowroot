/*
 * Copyright 2013-2017 the original author or authors.
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

glowroot.controller('LoginCtrl', [
  '$scope',
  '$http',
  '$rootScope',
  '$location',
  '$timeout',
  'login',
  'httpErrors',
  function ($scope, $http, $rootScope, $location, $timeout, login, httpErrors) {
    // \u00b7 is &middot;
    document.title = 'Login \u00b7 Glowroot';
    $scope.$parent.activeNavbarItem = 'login';

    // initialize page binding object
    $scope.page = {};

    $scope.message = login.getMessage();
    $scope.login = function (deferred) {
      delete $scope.message;
      var postData = {
        username: $scope.page.username,
        password: $scope.page.password
      };
      $http.post('backend/login', postData)
          .then(function (response) {
            if (response.data.incorrectLogin) {
              $('#loginPassword').select();
              deferred.reject('Password incorrect');
            } else {
              $rootScope.layout = response.data;
              $rootScope.initLayout();
              deferred.resolve('Success');
              login.returnToOriginalPath();
            }
          }, function (response) {
            httpErrors.handle(response, $scope, deferred);
          });
    };
  }
]);
