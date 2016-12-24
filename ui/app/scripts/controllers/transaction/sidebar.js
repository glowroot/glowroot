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

    $scope.summariesNoSearch = true;

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
      updateSummaries(true);
    };

    $scope.$watchGroup(['range.chartFrom', 'range.chartTo', 'range.chartRefresh', 'summarySortOrder'],
        function (newValues, oldValues) {
          if (newValues !== oldValues) {
            if (newValues[3] !== oldValues[3]) {
              $scope.summariesNoSearch = true;
              $scope.transactionSummaries = undefined;
              $scope.moreSummariesAvailable = undefined;
            }
            $timeout(function () {
              // slight delay to de-prioritize summaries data request
              updateSummaries();
            }, 100);
          }
        });

    var initialStateChangeSuccess = true;
    $scope.$on('$stateChangeSuccess', function () {
      // don't let the active sidebar selection get out of sync (which can happen after using the back button)
      var activeElement = document.activeElement;
      if (activeElement && $(activeElement).closest('.gt-sidebar').length) {
        var gtUrl = activeElement.getAttribute('gt-url');
        if (gtUrl && gtUrl !== $location.url()) {
          activeElement.blur();
        }
      }
      if ($scope.range.last && !initialStateChangeSuccess) {
        // refresh on tab change
        $timeout(function () {
          // slight delay to de-prioritize summaries data request
          updateSummaries();
        }, 100);
      }
      initialStateChangeSuccess = false;
    });

    function updateSummaries(moreLoading) {
      if ((!$scope.agentRollupId && !$scope.layout.embedded) || !$scope.transactionType) {
        $scope.summariesNoSearch = true;
        return;
      }
      $scope.showSpinner = $scope.summariesNoSearch;
      var query = {
        agentRollupId: $scope.agentRollupId,
        transactionType: $scope.transactionType,
        // need floor/ceil when on trace point chart which allows second granularity
        from: Math.floor($scope.range.chartFrom / 60000) * 60000,
        to: Math.ceil($scope.range.chartTo / 60000) * 60000,
        sortOrder: $scope.summarySortOrder,
        limit: $scope.summaryLimit
      };
      if (moreLoading) {
        $scope.summariesLoadingMore++;
      }
      concurrentUpdateCount++;
      $http.get($scope.backendSummariesUrl + queryStrings.encodeObject(query))
          .then(function (response) {
            if (moreLoading) {
              $scope.summariesLoadingMore--;
            }
            concurrentUpdateCount--;
            if (concurrentUpdateCount) {
              return;
            }
            $scope.showSpinner = false;
            $scope.summariesNoSearch = false;

            lastSortOrder = query.sortOrder;
            lastDurationMillis = query.to - query.from;
            var data = response.data;
            $scope.summariesNoData = !data.transactions.length;
            $scope.overallSummary = data.overall;
            $scope.transactionSummaries = data.transactions;
            $scope.moreSummariesAvailable = data.moreAvailable;
          }, function (response) {
            $scope.showSpinner = false;
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
