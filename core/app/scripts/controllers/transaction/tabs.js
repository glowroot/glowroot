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

/* global glowroot, $ */

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

    $scope.$on('updateTraceTabCount', function (event, traceCount) {
      $scope.activeTraceTabCount = traceCount;
    });

    $scope.$on('$stateChangeStart', function () {
      // need to clear trace tab count right away since it is based on filter criteria
      delete $scope.activeTraceTabCount;
      // don't clear profile tab count since it is really more accurate
      // (it will be cleared on next updateTabBarData())
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

    $scope.clickTab = function (tabItem, event) {
      if (tabItem === $scope.activeTabItem && !event.ctrlKey) {
        $scope.$parent.chartRefresh++;
        // suppress normal link
        event.preventDefault();
        return false;
      }
    };

    $scope.keydownTab = function (left, right, event) {
      if (event.which === 37 && !event.altKey && !event.ctrlKey && left) {
        // prevent default so page doesn't scroll horizontally prior to switching tabs
        event.preventDefault();
        $timeout(function () {
          var $left = $('#' + left);
          $left.click();
          $left.focus();
        });
      } else if (event.which === 39 && !event.altKey && !event.ctrlKey && right) {
        // prevent default so page doesn't scroll horizontally prior to switching tabs (e.g. on the wide profile tab)
        event.preventDefault();
        $timeout(function () {
          var $right = $('#' + right);
          $right.click();
          $right.focus();
        });
      }
    };

    $scope.$on('$stateChangeStart', function () {
      // don't let the active tab selection get out of sync (which can happen after using the back button)
      var activeElement = document.activeElement;
      if (activeElement && $(activeElement).closest('.gt-transaction-tabs').length) {
        var ngHref = activeElement.getAttribute('ng-href');
        if (ngHref && ngHref !== $location.url().substring(1)) {
          activeElement.blur();
        }
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
            delete $scope.activeProfileTabCount;
            delete $scope.activeTraceTabCount;
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
