/*
 * Copyright 2017 the original author or authors.
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

glowroot.controller('AdminHttpProxyCtrl', [
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

    function onNewData(config) {
      $scope.loaded = true;
      $scope.config = config;
      $scope.originalConfig = angular.copy(config);
      if (config.passwordExists) {
        $scope.password = '********';
      }
    }

    $scope.onPasswordChange = function () {
      $scope.config.newPassword = $scope.password;
      $scope.config.passwordExists = $scope.password !== '';
    };

    $scope.onPasswordClick = function () {
      $('#password').select();
    };

    $scope.save = function (deferred) {
      delete $scope.testHttpProxyResponse;
      $http.post('backend/admin/http-proxy', $scope.config)
          .then(function (response) {
            if (response.data.symmetricEncryptionKeyMissing) {
              deferred.reject('cassandra.symmetricEncryptionKey must be configured in the glowroot-central.properties'
                  + ' file before HTTP proxy password can be saved to cassandra' + encryptionKeyMessage.extra());
              return;
            }
            onNewData(response.data);
            deferred.resolve('Saved');
          }, function (response) {
            httpErrors.handle(response, $scope, deferred);
          });
    };

    $scope.testHttpProxy = function (deferred) {
      delete $scope.testHttpProxyResponse;
      var postData = angular.copy($scope.config);
      postData.testUrl = $scope.page.testUrl;
      $http.post('backend/admin/test-http-proxy', postData)
          .then(function (response) {
            if (response.data.error) {
              deferred.reject(response.data.message);
            } else {
              deferred.resolve('Success');
              $scope.testHttpProxyResponse = response.data.content;
            }
          }, function (response) {
            httpErrors.handle(response, $scope, deferred);
          });
    };

    $http.get('backend/admin/http-proxy')
        .then(function (response) {
          onNewData(response.data);
        }, function (response) {
          httpErrors.handle(response, $scope);
        });
  }
]);
