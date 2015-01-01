/*
 * Copyright 2014-2015 the original author or authors.
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

glowroot.controller('ErrorsTransactionsCtrl', [
  '$scope',
  '$location',
  '$filter',
  '$http',
  '$q',
  '$timeout',
  'charts',
  'keyedColorPools',
  'queryStrings',
  'httpErrors',
  function ($scope, $location, $filter, $http, $q, $timeout, charts, keyedColorPools, queryStrings, httpErrors) {
    // \u00b7 is &middot;
    document.title = 'Errors \u00b7 Glowroot';
    $scope.$parent.activeNavbarItem = 'errors';

    var chartState = {
      fixedAggregateIntervalMillis: $scope.layout.fixedAggregateIntervalSeconds * 1000,
      plot: undefined,
      currentRefreshId: 0,
      currentZoomId: 0,
      keyedColorPool: keyedColorPools.create(),
      chartFromToDefault: false,
      refreshData: refreshData
    };

    var summaryLimit = 100;

    // this is used to calculate bar width under transaction name
    var maxTransactionSummaryBarValue;

    $scope.showTableOverlay = 0;

    function refreshData() {
      updateLocation();
      var query = {
        from: $scope.chartFrom,
        to: $scope.chartTo,
        limit: summaryLimit
      };
      charts.refreshData('backend/error/transactions', query, chartState, $scope, updateTransactionSummaries);
    }

    function updateLocation() {
      var query = {};
      if (!chartState.chartFromToDefault) {
        query.from = $scope.chartFrom - chartState.fixedAggregateIntervalMillis;
        query.to = $scope.chartTo;
      }
      $location.search(query).replace();
    }

    $scope.refreshButtonClick = function () {
      charts.refreshButtonClick(chartState, $scope);
    };

    $scope.$watch('filterDate', function (newValue, oldValue) {
      if (newValue && newValue !== oldValue) {
        $scope.refreshButtonClick();
      }
    });

    function updateTransactionSummaries(data) {
      if (data.overallSummary) {
        $scope.overallSummary = data.overallSummary;
      }
      $scope.transactionSummaries = data.transactionSummaries;
      $scope.moreAvailable = data.moreAvailable;
      maxTransactionSummaryBarValue = 0;
      angular.forEach($scope.transactionSummaries, function (summary) {
        maxTransactionSummaryBarValue = Math.max(maxTransactionSummaryBarValue, summary.errorCount);
      });
    }

    $scope.overallErrorPercentage = function () {
      if (!$scope.overallSummary) {
        // overall hasn't loaded yet
        return '';
      } else if ($scope.overallSummary.errorCount) {
        return (($scope.overallSummary.errorCount / $scope.overallSummary.transactionCount) * 100).toFixed(1);
      } else {
        return '-';
      }
    };

    $scope.messagesQueryString = function (summary) {
      var query = {};
      if (summary) {
        query.transactionType = summary.transactionType;
        query.transactionName = summary.transactionName;
      }
      query.from = $scope.chartFrom;
      query.to = $scope.chartTo;
      return queryStrings.encodeObject(query);
    };

    $scope.showMore = function (deferred) {
      // double each time
      summaryLimit *= 2;
      var query = {
        from: $scope.chartFrom,
        to: $scope.chartTo,
        limit: summaryLimit
      };
      $scope.showTableOverlay++;
      $http.get('backend/error/transaction-summaries?' + queryStrings.encodeObject(query))
          .success(function (data) {
            $scope.showTableOverlay--;
            updateTransactionSummaries(data);
            deferred.resolve();
          })
          .error(httpErrors.handler($scope, deferred));
    };

    $scope.transactionSummaryBarWidth = function (errorCount) {
      return (errorCount / maxTransactionSummaryBarValue) * 100 + '%';
    };

    $scope.filter = {};
    charts.initFilter(chartState, $scope);

    // 100% yaxis max just for initial empty chart rendering
    charts.initChart($('#chart'), chartState, $scope, {yaxis: {max: 100, label: 'error percentage'}});
  }
]);
