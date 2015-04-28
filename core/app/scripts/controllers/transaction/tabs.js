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

    $scope.$watchGroup(['chartFrom', 'chartTo', 'transactionName', 'chartRefresh'], function (newValues, oldValues) {
      if (newValues !== oldValues) {
        updateTabBarData();
      }
    });

    $scope.$on('updateProfileTabCount', function (event, args) {
      $scope.activeProfileTabCount = args;
    });

    $scope.$on('updateTraceTabCount', function (event, args) {
      $scope.activeTraceTabCount = args;
    });

    $scope.$on('$stateChangeStart', function () {
      delete $scope.activeProfileTabCount;
      delete $scope.activeTraceTabCount;
    });

    $scope.profileSampleCount = function () {
      if (!$scope.tabBarData) {
        return '...';
      }
      if ($scope.tabBarData.profileExpired) {
        return '*';
      }
      if ($scope.activeProfileTabCount !== undefined) {
        return $scope.activeProfileTabCount;
      }
      return $scope.tabBarData.profileSampleCount;
    };

    $scope.traceCount = function () {
      if (!$scope.tabBarData) {
        return '...';
      }
      if ($scope.tabBarData.tracesExpired) {
        return '*';
      }
      if ($scope.activeTraceTabCount !== undefined) {
        return $scope.activeTraceTabCount;
      }
      return $scope.tabBarData.traceCount;
    };

    $scope.clickTab = function (tabItem, e) {
      if (tabItem === $scope.activeTabItem && !event.ctrlKey) {
        $scope.$parent.chartRefresh++;
        // suppress normal link
        event.preventDefault();
        return false;
      }
    };

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
            // set in parent scope so profiles tab and traces tab can access profileExpired and tracesExpired
            $scope.$parent.tabBarData = data;
          })
          .error(httpErrors.handler($scope));
    }

    $timeout(function () {
      // slight delay to de-prioritize tab bar data request
      updateTabBarData();
    }, 100);
  }
]);
