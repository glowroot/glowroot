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

/* global glowroot, angular, $ */

glowroot.controller('TransactionOverviewCtrl', [
  '$scope',
  '$location',
  '$http',
  '$filter',
  '$timeout',
  'charts',
  'modals',
  function ($scope, $location, $http, $filter, $timeout, charts, modals) {

    $scope.$parent.activeTabItem = 'overview';

    var chartState = charts.createState();

    function refreshData() {
      charts.refreshData('backend/transaction/overview', chartState, $scope.$parent, addToQuery, onRefreshData);
    }

    $scope.$watchGroup(['chartFrom', 'chartTo', 'chartRefresh'], function (newValues, oldValues) {
      if (newValues !== oldValues) {
        refreshData();
      }
    });

    $scope.openCustomPercentilesModal = function () {
      $scope.customPercentiles = $scope.percentiles.join(', ');
      modals.display('#customPercentilesModal', true);
      $timeout(function () {
        $('#customPercentiles').focus();
      });
    };

    $scope.applyCustomPercentiles = function () {
      var percentiles = [];
      angular.forEach($scope.customPercentiles.split(','), function (percentile) {
        percentile = percentile.trim();
        if (percentile.length) {
          percentiles.push(Number(percentile));
        }
      });
      $scope.percentiles = sortNumbers(percentiles);
      if (angular.equals($scope.percentiles, $scope.layout.defaultPercentiles)) {
        $location.search('percentile', null);
      } else {
        $location.search('percentile', $scope.percentiles);
      }
      $('#customPercentilesModal').modal('hide');
      $scope.$parent.chartRefresh++;
    };

    function onLocationChangeSuccess() {
      if ($location.search().percentile) {
        var percentiles = [];
        angular.forEach($location.search().percentile, function (percentile) {
          percentiles.push(Number(percentile));
        });
        $scope.percentiles = sortNumbers(percentiles);
      } else {
        $scope.percentiles = $location.search().percentile || $scope.layout.defaultPercentiles;
      }
    }

    function sortNumbers(arr) {
      return arr.sort(function (a, b) {
        return a - b;
      });
    }

    $scope.$on('$locationChangeSuccess', onLocationChangeSuccess);
    onLocationChangeSuccess();

    function addToQuery(query) {
      query.percentile = $scope.percentiles;
    }

    function onRefreshData(data, query) {
      $scope.transactionCounts = data.transactionCounts;
      $scope.lastDurationMillis = query.to - query.from + 1000 * $scope.layout.fixedAggregateIntervalSeconds;
      $scope.mergedAggregate = data.mergedAggregate;
    }

    var chartOptions = {
      tooltip: true,
      series: {
        stack: false,
        lines: {
          fill: false
        }
      },
      tooltipOpts: {
        content: function (label, xval, yval, flotItem) {
          var from = xval - chartState.dataPointIntervalMillis;
          // this math is to deal with active aggregate
          from = Math.ceil(from / chartState.dataPointIntervalMillis) * chartState.dataPointIntervalMillis;
          var to = xval;
          return charts.renderTooltipHtml(from, to, $scope.transactionCounts[xval], flotItem.dataIndex,
              flotItem.seriesIndex, chartState.plot, function (value) {
                return $filter('gtMillis')(value) + ' milliseconds';
              });
        }
      }
    };

    charts.init(chartState, $('#chart'), $scope.$parent);
    charts.plot([[]], chartOptions, chartState, $('#chart'), $scope.$parent);
    refreshData();
  }
]);
