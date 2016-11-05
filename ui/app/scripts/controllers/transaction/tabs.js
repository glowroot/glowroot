/*
 * Copyright 2015-2016 the original author or authors.
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

    var filteredTraceTabCount;
    var concurrentUpdateCount = 0;

    $scope.$watchGroup(['range.chartFrom', 'range.chartTo', 'range.chartRefresh', 'transactionName'],
        function (newValues, oldValues) {
          if (newValues !== oldValues) {
            $timeout(function () {
              // slight delay to de-prioritize tab bar data request
              updateTabBarData();
            }, 100);
          }
        });

    $scope.$on('updateTraceTabCount', function (event, traceCount) {
      filteredTraceTabCount = traceCount;
    });

    $scope.traceCountDisplay = function () {
      if ($scope.traceCount === undefined) {
        return '...';
      }
      if (filteredTraceTabCount !== undefined) {
        return filteredTraceTabCount;
      }
      return $scope.traceCount;
    };

    $scope.clickTab = function (tabItem, event) {
      if (tabItem === $scope.activeTabItem && !event.ctrlKey) {
        $scope.range.chartRefresh++;
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

    $scope.$on('$stateChangeSuccess', function () {
      // don't let the active tab selection get out of sync (which can happen after using the back button)
      var activeElement = document.activeElement;
      if (activeElement && $(activeElement).closest('.gt-transaction-tabs').length) {
        var ngHref = activeElement.getAttribute('ng-href');
        if (ngHref && ngHref !== $location.url().substring(1)) {
          activeElement.blur();
        }
      }
      if ($scope.range.last) {
        $timeout(function () {
          // slight delay to de-prioritize summaries data request
          updateTabBarData();
        }, 100);
      }
    });

    function updateTabBarData() {
      if (!$scope.agentPermissions || !$scope.agentPermissions[shortName].traces) {
        return;
      }
      if ((!$scope.agentRollup && !$scope.layout.embedded) || !$scope.transactionType) {
        $scope.traceCount = 0;
        return;
      }
      var query = {
        agentRollup: $scope.agentRollup,
        transactionType: $scope.transactionType,
        transactionName: $scope.transactionName,
        from: $scope.range.chartFrom,
        to: $scope.range.chartTo
      };
      concurrentUpdateCount++;
      $http.get('backend/' + shortName + '/trace-count' + queryStrings.encodeObject(query))
          .success(function (data) {
            concurrentUpdateCount--;
            if (concurrentUpdateCount) {
              return;
            }
            if ($scope.activeTabItem !== 'traces') {
              filteredTraceTabCount = undefined;
            }
            $scope.traceCount = data;
          })
          .error(function (data, status) {
            concurrentUpdateCount--;
            httpErrors.handler($scope)(data, status);
          });
    }

    $timeout(function () {
      // slight delay to de-prioritize tab bar data request
      updateTabBarData();
    }, 100);
  }
]);
