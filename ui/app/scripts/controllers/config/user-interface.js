/*
 * Copyright 2013-2016 the original author or authors.
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

/* global glowroot, angular */

glowroot.controller('ConfigUserInterfaceCtrl', [
  '$scope',
  '$http',
  '$rootScope',
  '$location',
  '$timeout',
  'confirmIfHasChanges',
  'httpErrors',
  function ($scope, $http, $rootScope, $location, $timeout, confirmIfHasChanges, httpErrors) {
    $scope.page = {};

    $scope.$watch('page.defaultDisplayedPercentiles', function (newVal) {
      if ($scope.config) {
        var percentiles = [];
        if (newVal) {
          angular.forEach(newVal.split(','), function (percentile) {
            percentile = percentile.trim();
            if (percentile.length) {
              percentiles.push(Number(percentile));
            }
          });
        }
        $scope.config.defaultDisplayedPercentiles = percentiles;
      }
    });

    $scope.showChangeAdminPassword = function () {
      return $scope.config && $scope.config.adminPasswordEnabled && $scope.originalConfig.adminPasswordEnabled;
    };

    $scope.showChangeReadOnlyPassword = function () {
      return $scope.config && $scope.config.readOnlyPasswordEnabled && $scope.originalConfig.readOnlyPasswordEnabled
          && $scope.config.adminPasswordEnabled;
    };

    $scope.$watch('config.anonymousAccess', function (newValue, oldValue) {
      if (newValue !== oldValue) {
        $scope.config.adminPasswordEnabled = newValue !== 'admin';
        if (newValue === 'read-only') {
          $scope.config.readOnlyPasswordEnabled = false;
        }
      }
    });

    $scope.$watch('config.adminPasswordEnabled', function (newValue, oldValue) {
      if (newValue !== oldValue) {
        if ($scope.config.adminPasswordEnabled && $scope.config.anonymousAccess === 'admin') {
          $scope.config.anonymousAccess = 'none';
        } else if (!$scope.config.adminPasswordEnabled) {
          $scope.config.readOnlyPasswordEnabled = false;
          $scope.config.anonymousAccess = 'admin';
        }
      }
    });

    $scope.$watch('config.readOnlyPasswordEnabled', function (newValue) {
      if (newValue && $scope.config.anonymousAccess === 'read-only') {
        $scope.config.anonymousAccess = 'none';
      }
    });

    $scope.hasChanges = function () {
      if (!$scope.originalConfig) {
        // hasn't loaded yet
        return false;
      }
      if (!$scope.originalConfig.adminPasswordEnabled && $scope.config.adminPasswordEnabled
          && (!$scope.page.initialAdminPassword || !$scope.page.verifyInitialAdminPassword)) {
        // enabling admin password, require initialAdminPassword and verifyInitialAdminPassword fields
        return false;
      }
      if ($scope.originalConfig.adminPasswordEnabled && !$scope.config.adminPasswordEnabled
          && !$scope.page.verifyCurrentAdminPassword) {
        // disabling admin password, require verifyCurrentAdminPassword field
        return false;
      }
      if ($scope.originalConfig.adminPasswordEnabled && $scope.config.adminPasswordEnabled) {
        // check if changing admin password, then require all three fields
        var cap = $scope.page.currentAdminPassword;
        var nap = $scope.page.newAdminPassword;
        var vnap = $scope.page.verifyNewAdminPassword;
        if ((cap || nap || vnap) && !(cap && nap && vnap)) {
          return false;
        }
      }
      if (!$scope.originalConfig.readOnlyPasswordEnabled && $scope.config.readOnlyPasswordEnabled
          && (!$scope.page.initialReadOnlyPassword || !$scope.page.verifyInitialReadOnlyPassword)) {
        // enabling read only password, require initialReadOnlyPassword and verifyInitialReadOnlyPassword fields
        return false;
      }
      if ($scope.originalConfig.readOnlyPasswordEnabled && $scope.config.readOnlyPasswordEnabled) {
        // check if changing read only password, then require both fields
        var nrop = $scope.page.newReadOnlyPassword;
        var vnrop = $scope.page.verifyNewReadOnlyPassword;
        if ((nrop || vnrop) && !(nrop && vnrop)) {
          return false;
        }
      }
      return !angular.equals($scope.config, $scope.originalConfig) || $scope.page.newAdminPassword
          || $scope.page.newReadOnlyPassword;
    };
    $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    function onNewData(data) {
      $scope.loaded = true;
      $scope.config = data.config;
      $scope.originalConfig = angular.copy(data.config);
      $scope.activePort = data.activePort;
      $scope.page = {};

      $scope.page.defaultDisplayedPercentiles = $scope.config.defaultDisplayedPercentiles.join(', ');
    }

    $scope.save = function (deferred) {
      // another copy to modify for the http post data
      var postData = angular.copy($scope.config);
      var enablingAdminPassword = false;
      var disablingAdminPassword = false;
      var changingPort = false;
      var previousActivePort;
      if (!$scope.originalConfig.adminPasswordEnabled && $scope.config.adminPasswordEnabled) {
        // enabling admin password
        if ($scope.page.initialAdminPassword !== $scope.page.verifyInitialAdminPassword) {
          deferred.reject('Passwords do not match');
          return;
        }
        postData.currentAdminPassword = '';
        postData.newAdminPassword = $scope.page.initialAdminPassword;
        enablingAdminPassword = true;
      } else if ($scope.originalConfig.adminPasswordEnabled && !$scope.config.adminPasswordEnabled) {
        // disabling admin password
        postData.currentAdminPassword = $scope.page.verifyCurrentAdminPassword;
        postData.newAdminPassword = '';
        disablingAdminPassword = true;
      } else if ($scope.originalConfig.adminPasswordEnabled && $scope.config.adminPasswordEnabled
          && $scope.page.currentAdminPassword) {
        // changing admin password
        if ($scope.page.newAdminPassword !== $scope.page.verifyNewAdminPassword) {
          deferred.reject('Passwords do not match');
          return;
        }
        postData.currentAdminPassword = $scope.page.currentAdminPassword;
        postData.newAdminPassword = $scope.page.newAdminPassword;
      }
      if (!$scope.originalConfig.readOnlyPasswordEnabled && $scope.config.readOnlyPasswordEnabled) {
        // enabling read only password
        if ($scope.page.initialReadOnlyPassword !== $scope.page.verifyInitialReadOnlyPassword) {
          deferred.reject('Passwords do not match');
          return;
        }
        postData.newReadOnlyPassword = $scope.page.initialReadOnlyPassword;
      } else if ($scope.originalConfig.readOnlyPasswordEnabled && !$scope.config.readOnlyPasswordEnabled) {
        // disabling read only password
        postData.newReadOnlyPassword = '';
      } else if ($scope.originalConfig.readOnlyPasswordEnabled && $scope.config.readOnlyPasswordEnabled
          && $scope.page.newReadOnlyPassword) {
        // changing read only password
        if ($scope.page.newReadOnlyPassword !== $scope.page.verifyNewReadOnlyPassword) {
          deferred.reject('Passwords do not match');
          return;
        }
        postData.newReadOnlyPassword = $scope.page.newReadOnlyPassword;
      }
      if ($scope.originalConfig.port !== $scope.config.port) {
        changingPort = true;
        previousActivePort = $scope.activePort;
      }
      $http.post('backend/config/ui', postData)
          .success(function (data) {
            if (data.currentPasswordIncorrect) {
              deferred.reject('Current password is incorrect');
              return;
            }
            onNewData(data);
            if (enablingAdminPassword) {
              $rootScope.authenticatedUser = 'admin';
            }
            if (disablingAdminPassword) {
              $rootScope.authenticatedUser = undefined;
            }
            if (!changingPort) {
              // normal path
              deferred.resolve('Saved');
              return;
            }
            if (changingPort && data.portChangeFailed) {
              deferred.reject('Save succeeded, but switching over to the new port failed');
              return;
            }
            if ($location.port() !== previousActivePort) {
              deferred.reject('The save succeeded, and switching the http listener over to the new port' +
              ' succeeded, but you are not being redirected to the new port since it seems you are using an' +
              ' intermediary proxy?');
              return;
            }
            deferred.resolve('Saved, redirecting to new port ...');
            $timeout(function () {
              var newUrl = $location.protocol() + '://' + $location.host();
              if (data.activePort !== 80) {
                newUrl += ':' + data.activePort;
              }
              newUrl += $location.path();
              document.location.href = newUrl;
            }, 500);
          })
          .error(httpErrors.handler($scope, deferred));
    };

    $http.get('backend/config/ui')
        .success(onNewData)
        .error(httpErrors.handler($scope));
  }
]);
