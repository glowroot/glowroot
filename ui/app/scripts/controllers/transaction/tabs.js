/*
 * Copyright 2015-2023 the original author or authors.
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
  '$filter',
  'queryStrings',
  'httpErrors',
  'shortName',
  function ($scope, $location, $http, $timeout, $filter, queryStrings, httpErrors, shortName) {

    var concurrentUpdateCount = 0;
    var traceCountFromChart = false;

    // using $watch instead of $watchGroup because $watchGroup has confusing behavior regarding oldValues
    // (see https://github.com/angular/angular.js/pull/12643)
    $scope.$watch('[range.chartFrom, range.chartTo, range.chartRefresh, range.chartAutoRefresh, transactionName]',
        function (newValues, oldValues) {
          if (newValues !== oldValues) {
            $timeout(function () {
              // slight delay to de-prioritize tab bar data request
              updateTabBarData(newValues[3] !== oldValues[3]);
            }, 100);
          }
        });

    $scope.traceCountDisplay = function () {
      if ($scope.traceCount === undefined) {
        return '...';
      }
      return $filter('number')($scope.traceCount);
    };

    $scope.clickTab = function (tabItem, event) {
      if (tabItem === $scope.activeTabItem && !event.ctrlKey) {
        $scope.range.chartRefresh++;
        // suppress normal link
        event.preventDefault();
        return false;
      }
      return true;
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

    var initialStateChangeSuccess = true;
    $scope.$on('gtStateChangeSuccess', function () {
      if (!initialStateChangeSuccess) {
        $timeout(function () {
          // slight delay to de-prioritize summaries data request
          // Always refresh: filter-only URL changes must update the tab count (#725)
          updateTabBarData();
        }, 100);
      }
      initialStateChangeSuccess = false;
    });

    // Chart points are the source of truth when under the result limit
    $scope.$on('gtDisplayedTraceCount', function (event, count) {
      traceCountFromChart = true;
      $scope.traceCount = count;
    });

    function updateTabBarData(autoRefresh) {
      if (!$scope.agentRollup || !$scope.agentRollup.permissions[shortName].traces) {
        return;
      }
      if (($scope.layout.central && !$scope.agentRollupId) || !$scope.transactionType) {
        $scope.traceCount = 0;
        return;
      }
      traceCountFromChart = false;
      var search = $location.search();
      var query = {
        agentRollupId: $scope.agentRollupId,
        transactionType: $scope.transactionType,
        transactionName: $scope.transactionName,
        from: $scope.range.chartFrom,
        to: $scope.range.chartTo
      };
      // Forward Slow/Error Traces filters so the tab count matches filtered points (#725).
      // Location uses custom-attribute-* for bookmarks; the API expects attribute-* (see points query).
      [
        'duration-millis-low',
        'duration-millis-high',
        'headline-comparator',
        'headline',
        'error-message-comparator',
        'error-message',
        'user-comparator',
        'user'
      ].forEach(function (key) {
        if (search[key]) {
          query[key] = search[key];
        }
      });
      if (search['custom-attribute-name']) {
        query.attributeName = search['custom-attribute-name'];
      }
      if (search['custom-attribute-value-comparator']) {
        query.attributeValueComparator = search['custom-attribute-value-comparator'];
      }
      if (search['custom-attribute-value']) {
        query.attributeValue = search['custom-attribute-value'];
      }
      if (autoRefresh) {
        query.autoRefresh = true;
      }
      concurrentUpdateCount++;
      $http.get('backend/' + shortName + '/trace-count' + queryStrings.encodeObject(query))
          .then(function (response) {
            concurrentUpdateCount--;
            if (concurrentUpdateCount) {
              return;
            }
            // Chart emit wins when under limit (avoids stale/unfiltered race with this request)
            if (traceCountFromChart) {
              return;
            }
            $scope.traceCount = response.data;
          }, function (response) {
            concurrentUpdateCount--;
            httpErrors.handle(response);
          });
    }

    $timeout(function () {
      // slight delay to de-prioritize tab bar data request
      updateTabBarData();
    }, 100);
  }
]);
