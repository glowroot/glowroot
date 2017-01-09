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

/* global glowroot */

glowroot.controller('ProfileChangePasswordCtrl', [
  '$scope',
  '$http',
  '$rootScope',
  '$location',
  'confirmIfHasChanges',
  'httpErrors',
  function ($scope, $http, $rootScope, $location, confirmIfHasChanges, httpErrors) {

    $scope.page = {};

    $scope.loaded = true;

    $scope.changePassword = function (deferred) {
      if ($scope.page.newPassword !== $scope.page.verifyNewPassword) {
        deferred.reject('Passwords do not match');
        return;
      }
      var postData = {
        currentPassword: $scope.page.currentPassword,
        newPassword: $scope.page.newPassword
      };
      $http.post('backend/change-password', postData)
          .then(function (response) {
            if (response.data.currentPasswordIncorrect) {
              deferred.reject('Current password is incorrect');
              return;
            }
            $scope.page = {};
            deferred.resolve('Password has been changed');
          }, function (response) {
            httpErrors.handle(response, $scope, deferred);
          });
    };
  }
]);
