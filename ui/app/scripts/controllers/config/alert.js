/*
 * Copyright 2015 the original author or authors.
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

glowroot.controller('ConfigAlertCtrl', [
  '$scope',
  '$location',
  '$http',
  '$timeout',
  'confirmIfHasChanges',
  'httpErrors',
  function ($scope, $location, $http, $timeout, confirmIfHasChanges, httpErrors) {

    var version = $location.search().v;

    function onNewData(data) {
      $scope.config = data;
      $scope.originalConfig = angular.copy(data);

      if (data.emailAddresses.length) {
        $scope.heading = data.transactionType + ' - ' + data.percentile + $scope.percentileSuffix(data.percentile) +
        ' percentile over a ' + data.timePeriodMinutes + ' minute period';
        $scope.emailAddresses = data.emailAddresses.join(', ');
      } else {
        $scope.heading = '<New>';
      }
    }

    if (version) {
      $http.get('backend/config/alerts?server-id=' + $scope.serverId + '&version=' + version)
          .success(function (data) {
            $scope.loaded = true;
            onNewData(data);
          })
          .error(httpErrors.handler($scope));
    } else {
      $scope.loaded = true;
      onNewData({
        transactionType: $scope.layout.defaultTransactionType,
        minTransactionCount: 1,
        emailAddresses: []
      });
    }

    $scope.$watch('emailAddresses', function (newValue) {
      if (newValue) {
        $scope.config.emailAddresses = newValue.split(/\s*,\s*/);
      } else if ($scope.config) {
        $scope.config.emailAddresses = [];
      }
    });

    $scope.hasChanges = function () {
      return !angular.equals($scope.config, $scope.originalConfig);
    };
    var removeConfirmIfHasChangesListener = $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    $scope.saveDisabled = function () {
      return !$scope.hasChanges() || $scope.formCtrl.$invalid;
    };

    $scope.save = function (deferred) {
      var postData = angular.copy($scope.config);
      postData.serverId = $scope.serverId;
      var url;
      if (version) {
        url = 'backend/config/alerts/update';
      } else {
        url = 'backend/config/alerts/add';
      }
      $http.post(url, postData)
          .success(function (data) {
            onNewData(data);
            deferred.resolve(version ? 'Saved' : 'Added');
            version = data.version;
            // fix current url (with updated version) before returning to list page in case back button is used later
            $timeout(function () {
              $location.search({v: version}).replace();
              $timeout(function () {
                $location.url('config/alert-list');
              });
            });
          })
          .error(function (data, status) {
            httpErrors.handler($scope, deferred)(data, status);
          });
    };

    $scope.delete = function (deferred) {
      var postData = {
        serverId: $scope.serverId,
        version: $scope.config.version
      };
      $http.post('backend/config/alerts/remove', postData)
          .success(function () {
            removeConfirmIfHasChangesListener();
            $location.url('config/alert-list').replace();
          })
          .error(httpErrors.handler($scope, deferred));
    };
  }
]);
