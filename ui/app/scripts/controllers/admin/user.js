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

/* global glowroot, angular, $ */

glowroot.controller('AdminUserCtrl', [
  '$scope',
  '$location',
  '$http',
  '$q',
  'confirmIfHasChanges',
  'httpErrors',
  'modals',
  function ($scope, $location, $http, $q, confirmIfHasChanges, httpErrors, modals) {

    // initialize page binding object
    $scope.page = {};

    $scope.username = $location.search().username;

    function onNewData(data) {
      if (data.config.roles) {
        // need to sort roles to keep hasChanges() consistent
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
      $scope.ldapAvailable = data.ldapAvailable;
    }

    if ($scope.username) {
      $http.get('backend/admin/users?username=' + encodeURIComponent($scope.username))
          .then(function (response) {
            $scope.loaded = true;
            onNewData(response.data);
          }, function (response) {
            httpErrors.handle(response, $scope);
          });
    } else {
      $http.get('backend/admin/all-role-names')
          .then(function (response) {
            $scope.loaded = true;
            onNewData({
              config: {
                ldap: false,
                roles: []
              },
              allRoles: response.data.allRoles,
              ldapAvailable: response.data.ldapAvailable
            });
          }, function (response) {
            httpErrors.handle(response, $scope);
          });
    }

    $scope.$watch('allRoles', function (newValue, oldValue) {
      if (newValue !== oldValue) {
        $scope.config.roles = [];
        angular.forEach($scope.allRoles, function (role) {
          if (role.checked) {
            $scope.config.roles.push(role.name);
          }
        });
        // need to sort roles to preserve original (sorted) ordering and avoid hasChanges()
        $scope.config.roles.sort(function (a, b) {
          return a.name < b.name ? -1 : a.name > b.name ? 1 : 0;
        });
      }
    }, true);

    $scope.hasChanges = function () {
      return !angular.equals($scope.config, $scope.originalConfig);
    };
    var removeConfirmIfHasChangesListener = $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    function save(deferred) {
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
          .then(function (response) {
            onNewData(response.data);
            deferred.resolve($scope.username ? 'Saved' : 'Added');
            delete $scope.page.password;
            delete $scope.page.verifyPassword;
            if (!$scope.username) {
              $scope.username = response.data.config.username;
              $location.search({username: $scope.username}).replace();
            }
          }, function (response) {
            if (response.status === 409 && response.data.message === 'username') {
              deferred.reject('There is already a user with this username');
              return;
            }
            httpErrors.handle(response, $scope, deferred);
          });
    }

    var overrideSaveWithNoRoles = false;

    $scope.save = function (deferred) {
      if ($scope.page.password !== $scope.page.verifyPassword) {
        deferred.reject('Passwords do not match');
        return;
      }
      if ($scope.config.roles.length || overrideSaveWithNoRoles) {
        overrideSaveWithNoRoles = false;
        save(deferred);
      } else {
        modals.display('#saveWithNoRolesConfirmationModal', true);
        deferred.resolve();
      }
    };

    $scope.saveWithNoRoles = function () {
      $('#saveWithNoRolesConfirmationModal').modal('hide');
      // 'clicking' on the save button in order to get proper deferred passed into $scope.save()
      setTimeout(function () {
        overrideSaveWithNoRoles = true;
        $('#gtSaveChanges button').click();
      });
    };

    // delete user deserves confirmation dialog (as opposed to other deletes), since it cannot be undone without
    // re-creating user password which may be unknown to administrator
    $scope.displayDeleteConfirmationModal = function () {
      $scope.errorCannotDeleteLastUser = false;
      modals.display('#deleteConfirmationModal', true);
    };

    $scope.delete = function () {
      var postData = {
        username: $scope.username
      };
      $scope.deleting = true;
      $http.post('backend/admin/users/remove', postData)
          .then(function (response) {
            $scope.deleting = false;
            if (response.data.errorCannotDeleteLastUser) {
              $scope.errorCannotDeleteLastUser = true;
              return;
            }
            $('#deleteConfirmationModal').modal('hide');
            removeConfirmIfHasChangesListener();
            $location.url('admin/user-list').replace();
          }, function (response) {
            $scope.deleting = false;
            httpErrors.handle(response, $scope);
          });
    };
  }
]);
