/*
 * Copyright 2016 the original author or authors.
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

glowroot.controller('AdminUserCtrl', [
  '$scope',
  '$location',
  '$http',
  '$timeout',
  'confirmIfHasChanges',
  'httpErrors',
  'queryStrings',
  function ($scope, $location, $http, $timeout, confirmIfHasChanges, httpErrors, queryStrings) {

    // initialize page binding object
    $scope.page = {};

    $scope.username = $location.search().username;

    function onNewData(data) {
      // need to sort role names to keep hasChanges() consistent
      if (data.config.roles) {
        data.config.roles.sort(function (a, b) {
          return a.name < b.name ? -1 : a.name > b.name ? 1 : 0;
        });
      }
      $scope.config = data.config;
      $scope.originalConfig = angular.copy(data.config);

      if (data.config.username) {
        if (data.config.username.toLowerCase() === 'anonymous') {
          $scope.heading = '<anonymous>';
        } else {
          $scope.heading = data.config.username;
        }
      } else {
        $scope.heading = '<New>';
      }
      var allRoles = {};
      angular.forEach(data.allRoles, function (roleName) {
        allRoles[roleName] = {
          checked: false,
          available: true
        };
      });
      angular.forEach(data.config.roles, function (roleName) {
        var role = allRoles[roleName];
        if (role) {
          role.checked = true;
        } else {
          allRoles[roleName] = {
            checked: true,
            available: false
          };
        }
      });
      // need to put attributes in an array to loop in ng-repeat with orderBy
      $scope.allRoles = [];
      angular.forEach(allRoles, function (value, key) {
        $scope.allRoles.push({
          name: key,
          checked: value.checked,
          available: value.available
        });
      });
    }

    if ($scope.username) {
      $http.get('backend/admin/users?username=' + encodeURIComponent($scope.username))
          .success(function (data) {
            $scope.loaded = true;
            onNewData(data);
          })
          .error(httpErrors.handler($scope));
    } else {
      $http.get('backend/admin/all-role-names')
          .success(function (data) {
            $scope.loaded = true;
            onNewData({
              config: {
                roles: []
              },
              allRoles: data
            });
          })
          .error(httpErrors.handler($scope));
    }

    $scope.$watch('allRoles', function (newValue, oldValue) {
      if (newValue !== oldValue) {
        $scope.config.roles = [];
        angular.forEach($scope.allRoles, function (role) {
          if (role.checked) {
            $scope.config.roles.push(role.name);
          }
        });
        // need to sort role names to keep hasChanges() consistent
        $scope.config.roles.sort(function (a, b) {
          return a.name < b.name ? -1 : a.name > b.name ? 1 : 0;
        });
      }
    }, true);

    $scope.hasChanges = function () {
      return !angular.equals($scope.config, $scope.originalConfig);
    };
    var removeConfirmIfHasChangesListener = $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    $scope.save = function (deferred) {
      if ($scope.page.password !== $scope.page.verifyPassword) {
        deferred.reject('Passwords do not match');
        return;
      }
      var postData = angular.copy($scope.config);
      if ($scope.page.password) {
        postData.newPassword = $scope.page.password;
      }
      var url;
      if ($scope.username) {
        url = 'backend/admin/users/update';
      } else {
        url = 'backend/admin/users/add';
      }
      $http.post(url, postData)
          .success(function (data) {
            onNewData(data);
            deferred.resolve($scope.username ? 'Saved' : 'Added');
            delete $scope.page.password;
            delete $scope.page.verifyPassword;
            if (!$scope.username) {
              $scope.username = data.config.username;
              $location.search({username: $scope.username}).replace();
            }
          })
          .error(function (data, status) {
            if (status === 409 && data.message === 'username') {
              $scope.duplicateUsername = true;
              deferred.reject('There is already a user with this username');
              return;
            }
            httpErrors.handler($scope, deferred)(data, status);
          });
    };

    $scope.delete = function (deferred) {
      var postData = {
        username: $scope.username
      };
      $http.post('backend/admin/users/remove', postData)
          .success(function () {
            removeConfirmIfHasChangesListener();
            $location.url('admin/user-list').replace();
          })
          .error(httpErrors.handler($scope, deferred));
    };
  }
]);
