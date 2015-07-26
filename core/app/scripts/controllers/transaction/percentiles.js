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

glowroot.controller('TransactionPercentilesCtrl', [
  '$scope',
  '$location',
  '$http',
  '$filter',
  '$timeout',
  'charts',
  'modals',
  function ($scope, $location, $http, $filter, $timeout, charts, modals) {

    $scope.$parent.activeTabItem = 'time';

    if ($scope.last) {
      // force the sidebar to update
      $scope.$parent.chartRefresh++;
    }

    var chartState = charts.createState();

    var appliedPercentiles;

    function refreshData() {
      charts.refreshData('backend/transaction/percentiles', chartState, $scope.$parent, addToQuery, onRefreshData);
    }

    $scope.$watchGroup(['chartFrom', 'chartTo', 'chartRefresh'], function () {
      if (angular.equals(appliedPercentiles, $scope.layout.defaultPercentiles)) {
        $location.search('percentile', null);
      } else {
        $location.search('percentile', appliedPercentiles);
      }
      refreshData();
    });

    $scope.clickTopRadioButton = function (item, event) {
      if (item === 'percentiles') {
        $scope.$parent.chartRefresh++;
      } else {
        $location.url('transaction/average' + $scope.tabQueryString());
      }
    };

    $scope.clickActiveTopLink = function (event) {
      if (!event.ctrlKey) {
        $scope.$parent.chartRefresh++;
        // suppress normal link
        event.preventDefault();
        return false;
      }
    };

    $scope.openCustomPercentilesModal = function () {
      $scope.customPercentiles = appliedPercentiles.join(', ');
      modals.display('#customPercentilesModal', true);
      $timeout(function () {
        $('#customPercentiles').focus();
      });
    };

    $scope.applyCustomPercentiles = function () {
      appliedPercentiles = [];
      angular.forEach($scope.customPercentiles.split(','), function (percentile) {
        percentile = percentile.trim();
        if (percentile.length) {
          appliedPercentiles.push(Number(percentile));
        }
      });
      sortNumbers(appliedPercentiles);
      $('#customPercentilesModal').modal('hide');
      $scope.$parent.chartRefresh++;
    };

    function onLocationChangeSuccess() {
      var priorAppliedPercentiles = appliedPercentiles;
      if ($location.search().percentile) {
        appliedPercentiles = [];
        angular.forEach($location.search().percentile, function (percentile) {
          appliedPercentiles.push(Number(percentile));
        });
        sortNumbers(appliedPercentiles);
      } else {
        appliedPercentiles = $scope.layout.defaultPercentiles;
      }

      if (priorAppliedPercentiles !== undefined && !angular.equals(appliedPercentiles, priorAppliedPercentiles)) {
        $scope.$parent.chartRefresh++;
      }
      $scope.percentiles = appliedPercentiles;
    }

    function sortNumbers(arr) {
      arr.sort(function (a, b) {
        return a - b;
      });
    }

    $scope.$on('$locationChangeSuccess', onLocationChangeSuccess);
    onLocationChangeSuccess();

    function addToQuery(query) {
      query.percentile = appliedPercentiles;
    }

    function onRefreshData(data, query) {
      $scope.transactionCounts = data.transactionCounts;
      $scope.lastDurationMillis = query.to - query.from;
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
    charts.initResize(chartState.plot, $scope);
  }
]);
