/*
 * Copyright 2013 the original author or authors.
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

/* global informant, $, alert */

informant.controller('LoginCtrl', [
  '$scope',
  '$http',
  '$rootScope',
  '$location',
  '$timeout',
  'login',
  function ($scope, $http, $rootScope, $location, $timeout, login) {
    // \u00b7 is &middot;
    document.title = 'Login \u00b7 Informant';
    $scope.$parent.title = 'Login';
    $scope.$parent.activeNavbarItem = 'login';

    // init for data binding
    $scope.data = {};
    $scope.message = login.getMessage();
    $scope.login = function (deferred) {
      $scope.message = undefined;
      $http.post('/backend/login', $scope.data.password)
          .success(function (data) {
            if (data.incorrectPassword) {
              $('#loginPassword').select();
              deferred.reject('Password incorrect');
            } else {
              $rootScope.layout = data;
              $rootScope.showSignOutButton = true;
              deferred.resolve('Success');
              login.returnToOriginalPath();
            }
          })
          .error(function (data) {
            // TODO handle this better
            alert('An error occurred');
          });
    };

    $timeout(function () {
      $('#loginPassword').focus();
      // TODO this is needed only for IE
      // TODO why do config pages auto-submit on enter in any input, even in IE?
      // TODO is refresh-data-on-enter-key handler still needed (see app.js)?
      // need to wait a small bit for input to be visible
      $('#loginPassword').keypress(function (event) {
        if (event.which === 13) {
          // trigger button so it will active spinner and success message
          $(event.target).closest('form').find('button').first().click();
          // without preventDefault, enter triggers 'more filters' button
          event.preventDefault();
        }
      });
    }, 100);
  }
]);
