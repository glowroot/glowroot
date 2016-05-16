/*
 * Copyright 2012-2016 the original author or authors.
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

glowroot.controller('AdminSmtpCtrl', [
  '$scope',
  '$http',
  'modals',
  'confirmIfHasChanges',
  'httpErrors',
  function ($scope, $http, modals, confirmIfHasChanges, httpErrors) {

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
      $scope.localServerName = data.localServerName;
    }

    $scope.onPasswordChange = function () {
      $scope.config.newPassword = $scope.password;
      $scope.config.passwordExists = $scope.password !== '';
    };

    $scope.onPasswordClick = function () {
      $('#password').select();
    };

    $scope.openTestEmailModal = function () {
      modals.display('#sendTestEmailModal', true);
    };

    $scope.sendTestEmail = function (deferred) {
      var postData = angular.copy($scope.config);
      postData.testEmailRecipient = $scope.testEmailRecipient;
      $http.post('backend/admin/send-test-email', postData)
          .success(function () {
            deferred.resolve('Sent');
          })
          .error(httpErrors.handler($scope, deferred));
    };

    $scope.save = function (deferred) {
      $http.post('backend/admin/smtp', $scope.config)
          .success(function (data) {
            onNewData(data);
            deferred.resolve('Saved');
          })
          .error(httpErrors.handler($scope, deferred));
    };

    $http.get('backend/admin/smtp')
        .success(onNewData)
        .error(httpErrors.handler($scope));
  }
]);
