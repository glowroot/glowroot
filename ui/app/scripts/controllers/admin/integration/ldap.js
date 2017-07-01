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

glowroot.controller('AdminLdapCtrl', [
  '$scope',
  '$http',
  'encryptionKeyMessage',
  'confirmIfHasChanges',
  'httpErrors',
  function ($scope, $http, encryptionKeyMessage, confirmIfHasChanges, httpErrors) {

    // initialize page binding object
    $scope.page = {};

    $scope.hasChanges = function () {
      return $scope.originalConfig && !angular.equals($scope.config, $scope.originalConfig);
    };
    $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    function onNewData(data) {
      $scope.loaded = true;
      $scope.config = data.config;
      $scope.originalConfig = angular.copy(data.config);
      if (data.config.passwordExists) {
        $scope.password = '********';
      }
      $scope.allGlowrootRoles = data.allGlowrootRoles;
      $scope.page.roleMappingBlocks = [];
      angular.forEach($scope.config.roleMappings, function (value, key) {
        $scope.page.roleMappingBlocks.push({
          ldapGroupDn: key,
          glowrootRoles: angular.copy(value)
        });
      });
    }

    $scope.onPasswordChange = function () {
      $scope.config.newPassword = $scope.password;
      $scope.config.passwordExists = $scope.password !== '';
    };

    $scope.onPasswordClick = function () {
      $('#password').select();
    };

    $scope.addRoleMappingBlock = function () {
      $scope.page.roleMappingBlocks.push({
        ldapGroupDn: '',
        glowrootRoles: []
      });
    };

    $scope.$watch('page.roleMappingBlocks', function (blocks) {
      if (!$scope.config) {
        return;
      }
      $scope.config.roleMappings = {};
      angular.forEach(blocks, function (block) {
        $scope.config.roleMappings[block.ldapGroupDn] = block.glowrootRoles;
      });
    }, true);

    $scope.removeRoleMappingBlock = function (roleMappingBlock) {
      var index = $scope.page.roleMappingBlocks.indexOf(roleMappingBlock);
      $scope.page.roleMappingBlocks.splice(index, 1);
    };

    $scope.save = function (deferred) {
      $http.post('backend/admin/ldap', $scope.config)
          .then(function (response) {
            if (response.data.symmetricEncryptionKeyMissing) {
              deferred.reject('cassandra.symmetricEncryptionKey must be configured in the glowroot-central.properties'
                  + ' file before LDAP password can be saved to cassandra' + encryptionKeyMessage.extra());
              return;
            }
            deferred.resolve('Saved');
            onNewData(response.data);
          }, function (response) {
            httpErrors.handle(response, $scope, deferred);
          });
    };

    $scope.testLdap = function (deferred) {
      // copy to modify for the http post data
      var postData = angular.copy($scope.config);
      postData.authTestUsername = $scope.page.authTestUsername;
      postData.authTestPassword = $scope.page.authTestPassword;
      $http.post('backend/admin/test-ldap', postData)
          .then(function (response) {
            var data = response.data;
            if (data.error) {
              deferred.reject(data.message);
            } else if (data.glowrootRoles.length) {
              deferred.resolve('Success, assigned to Glowroot roles: ' + data.glowrootRoles.join(', '));
            } else if (data.ldapGroupDns.length) {
              deferred.reject('Authentication succeeded, but the LDAP group query did not find any groups for this user'
                  + ' that have been mapped to Glowroot roles above. The LDAP groups query found the following groups:'
                  + '\n\n' + data.ldapGroupDns.join('\n\n'));
            } else {
              deferred.reject(
                  'Authentication succeeded, but the LDAP group query did not find any groups for this user');
            }
          }, function (response) {
            httpErrors.handle(response, $scope, deferred);
          });
    };

    $http.get('backend/admin/ldap')
        .then(function (response) {
          onNewData(response.data);
        }, function (response) {
          httpErrors.handle(response, $scope);
        });
  }
]);
