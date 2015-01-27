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

/* global glowroot */

glowroot.controller('TransactionTabCtrl', [
  '$scope',
  '$location',
  '$http',
  '$timeout',
  'queryStrings',
  'httpErrors',
  'shortName',
  function ($scope, $location, $http, $timeout, queryStrings, httpErrors, shortName) {

    var concurrentUpdateCount = 0;

    $scope.$watchGroup(['chartFrom', 'chartTo', 'transactionName'], function (oldValues, newValues) {
      if (newValues !== oldValues) {
        updateTabBarData();
      }
    });

    $scope.$on('updateProfileTabCount', function (event, args) {
      if ($scope.tabBarData) {
        $scope.tabBarData.profileSampleCount = args;
      }
    });

    function updateTabBarData() {
      var query = {
        from: $scope.chartFrom,
        to: $scope.chartTo,
        transactionType: $scope.transactionType,
        transactionName: $scope.transactionName
      };
      concurrentUpdateCount++;
      $http.get('backend/' + shortName + '/tab-bar-data' + queryStrings.encodeObject(query))
          .success(function (data) {
            concurrentUpdateCount--;
            if (concurrentUpdateCount) {
              return;
            }
            $scope.tabBarData = data;
          })
          .error(httpErrors.handler($scope));
    }

    $timeout(function () {
      // slight delay to de-prioritize tab bar data request
      updateTabBarData();
    }, 100);
  }
]);
