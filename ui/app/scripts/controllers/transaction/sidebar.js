/*
 * Copyright 2015-2017 the original author or authors.
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

    $scope.summaryLimit = 10;
    $scope.summariesLoadingMore = 0;

    $scope.summariesNoSearch = true;

    $scope.showSpinner = 0;

    $scope.overallSummaryValue = function () {
      if ($scope.overallSummary) {
        return summaryValueFn($scope.overallSummary, lastSortOrder, $scope.overallSummary, lastDurationMillis);
      } else {
        // hasn't loaded yet
        return '';
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

    $scope.summarySortQueryString = function (summarySortOrder) {
      var query = $scope.buildQueryObject();
      delete query.summarySortOrder;
      if (summarySortOrder !== $scope.defaultSummarySortOrder) {
        query['summary-sort-order'] = summarySortOrder;
      } else {
        delete query['summary-sort-order'];
      }
      return queryStrings.encodeObject(query);
    };

    // using $watch instead of $watchGroup because $watchGroup has confusing behavior regarding oldValues
    // (see https://github.com/angular/angular.js/pull/12643)
    $scope.$watch('[range.chartFrom, range.chartTo, range.chartRefresh, range.chartAutoRefresh, summarySortOrder]',
        function (newValues, oldValues) {
          if (newValues !== oldValues) {
            $timeout(function () {
              // slight delay to de-prioritize summaries data request
              updateSummaries(newValues[3] !== oldValues[3]);
            }, 100);
          }
        });

    var initialStateChangeSuccess = true;
    $scope.$on('gtStateChangeSuccess', function () {
      if ($scope.range.last && !initialStateChangeSuccess) {
        // refresh on tab change
        $timeout(function () {
          // slight delay to de-prioritize summaries data request
          updateSummaries();
        }, 100);
      }
      initialStateChangeSuccess = false;
    });

    function updateSummaries(autoRefresh, moreLoading) {
      if (($scope.layout.central && !$scope.agentRollupId) || !$scope.transactionType) {
        $scope.summariesNoSearch = true;
        return;
      }
      $scope.showSpinner++;
      var query = {
        agentRollupId: $scope.agentRollupId,
        transactionType: $scope.transactionType,
        // need floor/ceil when on trace point chart which allows second granularity
        from: Math.floor($scope.range.chartFrom / 60000) * 60000,
        to: Math.ceil($scope.range.chartTo / 60000) * 60000,
        sortOrder: $scope.summarySortOrder,
        limit: $scope.summaryLimit
      };
      if (autoRefresh) {
        query.autoRefresh = true;
      }
      if (moreLoading) {
        $scope.summariesLoadingMore++;
      }
      concurrentUpdateCount++;
      $http.get('backend/' + $scope.shortName + '/summaries' + queryStrings.encodeObject(query))
          .then(function (response) {
            $scope.showSpinner--;
            if (moreLoading) {
              $scope.summariesLoadingMore--;
            }
            concurrentUpdateCount--;
            if (concurrentUpdateCount) {
              return;
            }
            $scope.summariesNoSearch = false;

            lastSortOrder = query.sortOrder;
            lastDurationMillis = query.to - query.from;
            var data = response.data;
            $scope.summariesNoData = !data.transactions.length;
            $scope.overallSummary = data.overall;
            $scope.transactionSummaries = data.transactions;
            $scope.moreSummariesAvailable = data.moreAvailable;
          }, function (response) {
            $scope.showSpinner--;
            if (moreLoading) {
              $scope.summariesLoadingMore--;
            }
            concurrentUpdateCount--;
            httpErrors.handle(response, $scope);
          });
    }

    $timeout(function () {
      // slight delay to de-prioritize summaries data request
      updateSummaries();
    }, 100);
  }
]);
