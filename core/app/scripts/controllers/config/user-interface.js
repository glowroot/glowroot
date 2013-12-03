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

/* global glowroot, angular, $ */

glowroot.controller('ConfigUserInterfaceCtrl', [
  '$scope',
  '$http',
  '$rootScope',
  '$location',
  '$timeout',
  'confirmIfHasChanges',
  'httpErrors',
  function ($scope, $http, $rootScope, $location, $timeout, confirmIfHasChanges, httpErrors) {
    // set up objects for data binding
    $scope.page = {};
    $scope.passwordForm = {};

    $scope.$watch('config.passwordEnabled', function (newValue, oldValue) {
      if (oldValue === undefined) {
        // initial value is being set after $http response
        return;
      }
      if ($scope.originalConfig.passwordEnabled) {
        $('#disablePasswordDetail').collapse('toggle');
      } else {
        $('#enablePasswordDetail').collapse('toggle');
      }
    });

    $scope.hasChanges = function () {
      if (!$scope.originalConfig) {
        // hasn't loaded yet
        return false;
      }
      if (!$scope.originalConfig.passwordEnabled && $scope.config.passwordEnabled) {
        // enabling password, require initialPassword and verifyInitialPassword fields
        if (!$scope.page.initialPassword || !$scope.page.verifyInitialPassword) {
          return false;
        }
      }
      if ($scope.originalConfig.passwordEnabled && !$scope.config.passwordEnabled) {
        // disabling password, require verifyCurrentPassword field
        if (!$scope.page.verifyCurrentPassword) {
          return false;
        }
      }
      return !angular.equals($scope.config, $scope.originalConfig);
    };
    $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    function onNewData(data) {
      $scope.loaded = true;
      $scope.config = data.config;
      $scope.originalConfig = angular.copy(data.config);
      $scope.showChangePasswordSection = data.config.passwordEnabled;
      $scope.activePort = data.activePort;
      $scope.page.initialPassword = '';
      $scope.page.verifyInitialPassword = '';
      $scope.page.verifyCurrentPassword = '';
      $scope.passwordForm = {};
    }

    $scope.save = function (deferred) {
      // another copy to modify for the http post data
      var postData = angular.copy($scope.config);
      // passwordEnabled property isn't sent back to server (only currentPassword/newPassword if applicable below)
      delete postData.passwordEnabled;
      var enablingPassword = false;
      var disablingPassword = false;
      var changingPort = false;
      var previousPort;
      if (!$scope.originalConfig.passwordEnabled && $scope.config.passwordEnabled) {
        enablingPassword = true;
        if ($scope.page.verifyInitialPassword !== $scope.page.initialPassword) {
          deferred.reject('Passwords do not match');
          return;
        }
        postData.currentPassword = '';
        postData.newPassword = $scope.page.initialPassword;
      }
      if ($scope.originalConfig.passwordEnabled && !$scope.config.passwordEnabled) {
        disablingPassword = true;
        postData.currentPassword = $scope.page.verifyCurrentPassword;
        postData.newPassword = '';
      }
      if ($scope.originalConfig.port !== $scope.config.port) {
        changingPort = true;
        previousPort = $scope.originalConfig.port;
      }
      $http.post('backend/config/user-interface', postData)
          .success(function (data) {
            if (data.currentPasswordIncorrect) {
              deferred.reject('Current password is incorrect');
            } else {
              onNewData(data);
              if ($('#enablePasswordDetail').is(':visible')) {
                $('#enablePasswordDetail').collapse('hide');
              }
              if ($('#disablePasswordDetail').is(':visible')) {
                $('#disablePasswordDetail').collapse('hide');
              }
              if (enablingPassword) {
                $rootScope.showSignOutButton = true;
              } else if (disablingPassword) {
                $rootScope.showSignOutButton = false;
              }
              if (changingPort && data.portChangeFailed) {
                deferred.reject('Save succeeded, but switching over to the new port failed');
              } else if (changingPort) {
                if ($location.port() === previousPort) {
                  deferred.resolve('Saved, redirecting to new port ...');
                  $timeout(function () {
                    var newUrl = $location.protocol() + '://' + $location.host();
                    if (data.activePort !== 80) {
                      newUrl += ':' + data.activePort;
                    }
                    newUrl += $location.path();
                    document.location.href = newUrl;
                  }, 500);
                } else {
                  deferred.reject('Save succeeded, switching over the port succeeded, but not sure how to redirect' +
                      ' you since you are not connecting directly the old port is no longer available');
                }
              } else {
                deferred.resolve('Saved');
              }
            }
          })
          .error(httpErrors.handler($scope, deferred));
    };

    $scope.changePasswordButtonDisabled = function () {
      return !$scope.passwordForm.currentPassword || !$scope.passwordForm.newPassword ||
          !$scope.passwordForm.verifyPassword || $scope.passwordForm.verifyPassword !== $scope.passwordForm.newPassword;
    };

    $scope.changePassword = function (deferred) {
      if ($scope.passwordForm.verifyPassword !== $scope.passwordForm.newPassword) {
        deferred.reject('Passwords do not match');
      }
      var postData = {
        currentPassword: $scope.passwordForm.currentPassword,
        newPassword: $scope.passwordForm.newPassword,
        version: $scope.config.version
      };
      $http.post('backend/config/user-interface', postData)
          .success(function (data) {
            if (data.currentPasswordIncorrect) {
              deferred.reject('Current password is incorrect');
            } else {
              onNewData(data);
              deferred.resolve('Password changed');
            }
          })
          .error(httpErrors.handler($scope, deferred));
    };

    $http.get('backend/config/user-interface')
        .success(onNewData)
        .error(httpErrors.handler($scope));
  }
]);
