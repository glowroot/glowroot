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

/* global glowroot, angular, $ */

glowroot.controller('TransactionSidebarCtrl', [
  '$scope',
  '$location',
  '$http',
  '$timeout',
  'queryStrings',
  'httpErrors',
  'summarySortOrders',
  'summaryValueFn',
  function ($scope, $location, $http, $timeout, queryStrings, httpErrors, summarySortOrders, summaryValueFn) {

    var lastSortOrder;
    var lastDurationMillis;
    var concurrentUpdateCount = 0;

    $scope.summarySortOrders = summarySortOrders;
    $scope.backendSummariesUrl = 'backend/' + $scope.shortName + '/summaries';

    $scope.summaryLimit = 10;
    $scope.summariesLoadingMore = 0;
    $scope.summariesRefreshing = 0;

    $scope.overallSummaryValue = function () {
      if ($scope.overallSummary) {
        return summaryValueFn($scope.overallSummary, lastSortOrder, $scope.overallSummary, lastDurationMillis);
      }
    };

    $scope.transactionSummaryValue = function (transactionSummary) {
      return summaryValueFn(transactionSummary, lastSortOrder, $scope.overallSummary, lastDurationMillis);
    };

    $scope.sidebarQueryString = function (transactionName) {
      var query = angular.copy($location.search());
      query['transaction-name'] = transactionName;
      return queryStrings.encodeObject(query);
    };

    $scope.showMoreSummaries = function () {
      // double each time
      $scope.summaryLimit *= 2;
      updateSummaries(false, true);
    };

    $scope.$watchGroup(['range.chartFrom', 'range.chartTo', 'range.chartRefresh', 'summarySortOrder'],
        function (newValues, oldValues) {
          if (newValues !== oldValues) {
            $timeout(function () {
              // slight delay to de-prioritize summaries data request
              updateSummaries();
            }, 100);
          }
        });

    $scope.$on('$stateChangeSuccess', function () {
      // don't let the active sidebar selection get out of sync (which can happen after using the back button)
      var activeElement = document.activeElement;
      if (activeElement && $(activeElement).closest('.gt-sidebar').length) {
        var gtUrl = activeElement.getAttribute('gt-url');
        if (gtUrl && gtUrl !== $location.url()) {
          activeElement.blur();
        }
      }
      if ($scope.range.last) {
        $timeout(function () {
          // slight delay to de-prioritize summaries data request
          updateSummaries();
        }, 100);
      }
    });

    function updateSummaries(initialLoading, moreLoading) {
      if ((!$scope.agentRollup && !$scope.layout.fat) || !$scope.transactionType) {
        $scope.summariesNoSearch = true;
        return;
      }
      $scope.summariesNoSearch = false;
      var query = {
        agentRollup: $scope.agentRollup,
        transactionType: $scope.transactionType,
        from: $scope.range.chartFrom,
        to: $scope.range.chartTo,
        sortOrder: $scope.summarySortOrder,
        limit: $scope.summaryLimit
      };
      if (initialLoading) {
        $scope.summariesLoadingInitial = true;
      } else if (moreLoading) {
        $scope.summariesLoadingMore++;
      } else {
        $scope.summariesRefreshing++;
      }
      concurrentUpdateCount++;
      $http.get($scope.backendSummariesUrl + queryStrings.encodeObject(query))
          .success(function (data) {
            if (initialLoading) {
              $scope.summariesLoadingInitial = false;
            } else if (moreLoading) {
              $scope.summariesLoadingMore--;
            } else {
              $scope.summariesRefreshing--;
            }
            concurrentUpdateCount--;
            if (concurrentUpdateCount) {
              return;
            }
            lastSortOrder = query.sortOrder;
            lastDurationMillis = query.to - query.from;
            $scope.summariesNoData = !data.transactions.length;
            $scope.overallSummary = data.overall;
            $scope.transactionSummaries = data.transactions;
            $scope.moreSummariesAvailable = data.moreAvailable;
          })
          .error(function (data, status) {
            if (initialLoading) {
              $scope.summariesLoadingInitial = false;
            } else if (moreLoading) {
              $scope.summariesLoadingMore--;
            } else {
              $scope.summariesRefreshing--;
            }
            concurrentUpdateCount--;
            httpErrors.handler($scope)(data, status);
          });
    }

    $timeout(function () {
      // slight delay to de-prioritize summaries data request
      updateSummaries(true);
    }, 100);
  }
]);
