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

glowroot.controller('TransactionSidebarCtrl', [
  '$scope',
  '$location',
  '$http',
  'queryStrings',
  'httpErrors',
  'summarySortOrders',
  'summaryValueFn',
  function ($scope, $location, $http, queryStrings, httpErrors, summarySortOrders, summaryValueFn) {

    var lastSortOrder;
    var lastDurationMillis;
    var concurrentUpdateCount = 0;

    $scope.summarySortOrders = summarySortOrders;
    $scope.backendSummariesUrl = 'backend/' + $scope.shortName + '/summaries';

    $scope.summaryLimit = 10;
    $scope.summariesLoadingMore = 0;
    $scope.summariesRefreshing = 0;

    $scope.summarySortOrder = $location.search()['summary-sort-order'] || $scope.defaultSummarySortOrder;

    $scope.overallSummaryValue = function () {
      if ($scope.overallSummary) {
        return summaryValueFn($scope.overallSummary, lastSortOrder, $scope.overallSummary, lastDurationMillis);
      }
    };

    $scope.transactionSummaryValue = function (transactionSummary) {
      return summaryValueFn(transactionSummary, lastSortOrder, $scope.overallSummary, lastDurationMillis);
    };

    $scope.sidebarQueryString = function (transactionName) {
      var query = $scope.buildQueryObject();
      query['transaction-name'] = transactionName;
      return queryStrings.encodeObject(query);
    };

    $scope.showMoreSummaries = function () {
      // double each time
      $scope.summaryLimit *= 2;
      updateSummaries(false, true);
    };

    $scope.$watchGroup(['chartFrom', 'chartTo', 'summarySortOrder'], function (oldValues, newValues) {
      if (newValues !== oldValues) {
        updateSummaries();
      }
    });

    $scope.currentTabUrl = function () {
      return $location.path();
    };

    function updateSummaries(initialLoading, moreLoading) {
      var query = {
        from: $scope.chartFrom,
        to: $scope.chartTo,
        transactionType: $scope.transactionType,
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
            lastDurationMillis = query.to - query.from + 1000 * $scope.layout.fixedAggregateIntervalSeconds;
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
            httpErrors.handler($scope)(data, status);
          });
    }

    updateSummaries(true);
  }
]);
