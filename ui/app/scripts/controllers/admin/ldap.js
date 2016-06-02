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

glowroot.controller('AdminLdapCtrl', [
  '$scope',
  '$http',
  'confirmIfHasChanges',
  'httpErrors',
  function ($scope, $http, confirmIfHasChanges, httpErrors) {

    // initialize page binding object
    $scope.page = {};

    $scope.hasChanges = function () {
      return $scope.originalConfig && !angular.equals($scope.config, $scope.originalConfig);
    };
    $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    function onNewData(data) {
      $scope.loaded = true;
      $scope.config = data;
      $scope.originalConfig = angular.copy(data);
    }

    $scope.$watch('page.activeDirectory', function (newVal) {
      if (newVal) {
        $scope.config.userDnTemplate = '';
        $scope.config.authenticationMechanism = '';
      }
    });

    $scope.save = function (deferred) {
      // another copy to modify for the http post data
      var postData = angular.copy($scope.config);
      $http.post('backend/admin/ldap', postData)
          .success(function (data) {
            deferred.resolve('Saved');
            onNewData(data);
          })
          .error(httpErrors.handler($scope, deferred));
    };

    $scope.testConnection = function (deferred) {
      // another copy to modify for the http post data
      var postData = angular.copy($scope.config);
      postData.testLdapUsername = $scope.testLdapUsername;
      postData.testLdapPassword = $scope.testLdapPassword;
      $http.post('backend/admin/test-ldap-connection', postData)
          .success(function (data) {
            if (data.error) {
              deferred.reject(data.message);
            } else {
              deferred.resolve('Success');
            }
          })
          .error(httpErrors.handler($scope, deferred));
    };

    $http.get('backend/admin/ldap')
        .success(onNewData)
        .error(httpErrors.handler($scope));
  }
]);
