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

/* global informant, angular, $ */

informant.controller('ConfigUserInterfaceCtrl', [
  '$scope',
  '$http',
  '$rootScope',
  'httpErrors',
  function ($scope, $http, $rootScope, httpErrors) {
    var originalConfig;
    // set up objects for data binding
    $scope.passwordData = {};
    $scope.passwordForm = {};

    $scope.$watch('config.passwordEnabled', function (newValue, oldValue) {
      if (oldValue === undefined) {
        // initial value is being set after $http response
        return;
      }
      if (originalConfig.passwordEnabled) {
        $('#disablePasswordDetail').collapse('toggle');
      } else {
        $('#enablePasswordDetail').collapse('toggle');
      }
    });

    $scope.hasChanges = function () {
      if (!originalConfig) {
        // hasn't loaded yet
        return false;
      }
      if (!originalConfig.passwordEnabled && $scope.config.passwordEnabled) {
        // enabling password, require initialPassword and verifyInitialPassword fields
        if (!$scope.passwordData.initialPassword || !$scope.passwordData.verifyInitialPassword) {
          return false;
        }
      }
      if (originalConfig.passwordEnabled && !$scope.config.passwordEnabled) {
        // disabling password, require verifyCurrentPassword field
        if (!$scope.passwordData.verifyCurrentPassword) {
          return false;
        }
      }
      return !angular.equals($scope.config, originalConfig);
    };

    $scope.save = function (deferred) {
      var config = angular.copy($scope.config);
      var enablingPassword = false;
      var disablingPassword = false;
      if (!originalConfig.passwordEnabled && config.passwordEnabled) {
        enablingPassword = true;
        if ($scope.passwordData.verifyInitialPassword !== $scope.passwordData.initialPassword) {
          deferred.reject('Passwords do not match');
          return;
        }
        config.currentPassword = '';
        config.newPassword = $scope.passwordData.initialPassword;
      }
      if (originalConfig.passwordEnabled && !config.passwordEnabled) {
        disablingPassword = true;
        config.currentPassword = $scope.passwordData.verifyCurrentPassword;
        config.newPassword = '';
      }
      $http.post('backend/config/user-interface', config)
          .success(function (data) {
            if (data.currentPasswordIncorrect) {
              deferred.reject('Current password is incorrect');
            } else {
              $scope.config.version = data;
              originalConfig = angular.copy($scope.config);
              $scope.showChangePasswordSection = originalConfig.passwordEnabled;
              deferred.resolve('Saved');
              $scope.passwordData = {};
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
            }
          })
          .error(function (data, status) {
            if (status === 412) {
              // HTTP Precondition Failed
              deferred.reject('Someone else has updated this configuration, please reload and try again');
            } else {
              $scope.httpError = httpErrors.get(data, status);
              deferred.reject($scope.httpError.headline);
            }
          });
    };

    $scope.changePasswordButtonDisabled = function () {
      return !$scope.passwordForm.currentPassword || !$scope.passwordForm.newPassword ||
          !$scope.passwordForm.verifyPassword || $scope.passwordForm.verifyPassword !== $scope.passwordForm.newPassword;
    };

    $scope.changePassword = function (deferred) {
      if ($scope.passwordForm.verifyPassword !== $scope.passwordForm.newPassword) {
        deferred.reject('Passwords do not match');
      }
      var config = {
        currentPassword: $scope.passwordForm.currentPassword,
        newPassword: $scope.passwordForm.newPassword,
        version: $scope.config.version
      };
      $http.post('backend/config/user-interface', config)
          .success(function (data) {
            if (data.currentPasswordIncorrect) {
              deferred.reject('Current password is incorrect');
            } else {
              $scope.config.version = data;
              // need to update originalConfig also
              originalConfig.version = data;
              $scope.passwordForm = {};
              deferred.resolve('Password changed');
            }
          })
          .error(function (data, status) {
            if (status === 412) {
              // HTTP Precondition Failed
              deferred.reject('Someone else has updated this configuration, please reload and try again');
            } else {
              $scope.httpError = httpErrors.get(data, status);
              deferred.reject($scope.httpError.headline);
            }
          });
    };

    $http.get('backend/config/user-interface')
        .success(function (data) {
          $scope.loaded = true;
          $scope.config = data;
          originalConfig = angular.copy($scope.config);
          $scope.showChangePasswordSection = originalConfig.passwordEnabled;
        })
        .error(function (data, status) {
          $scope.httpError = httpErrors.get(data, status);
        });
  }
]);
