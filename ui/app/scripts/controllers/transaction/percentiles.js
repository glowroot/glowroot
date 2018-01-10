/*
 * Copyright 2015-2018 the original author or authors.
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
  '$filter',
  '$timeout',
  'locationChanges',
  'charts',
  'modals',
  function ($scope, $location, $filter, $timeout, locationChanges, charts, modals) {

    $scope.$parent.activeTabItem = 'time';

    if ($scope.hideMainContent()) {
      return;
    }

    var chartState = charts.createState();

    var appliedPercentiles;

    // using $watch instead of $watchGroup because $watchGroup has confusing behavior regarding oldValues
    // (see https://github.com/angular/angular.js/pull/12643)
    $scope.$watch('[range.chartFrom, range.chartTo, range.chartRefresh, range.chartAutoRefresh]',
        function (newValues, oldValues) {
          if (angular.equals(appliedPercentiles, $scope.agentRollup.defaultPercentiles)) {
            $location.search('percentile', null);
          } else {
            $location.search('percentile', appliedPercentiles);
          }
          var autoRefresh = newValues[3] !== oldValues[3];
          charts.refreshData('backend/transaction/percentiles', chartState, $scope, autoRefresh, addToQuery,
              onRefreshData);
        });

    $scope.clickTopRadioButton = function (item) {
      if (item === 'percentiles') {
        $scope.range.chartRefresh++;
      } else {
        $location.url('transaction/' + item + $scope.tabQueryString());
      }
    };

    $scope.clickActiveTopLink = function (event) {
      if (!event.ctrlKey) {
        $scope.range.chartRefresh++;
        // suppress normal link
        event.preventDefault();
        return false;
      }
      return true;
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
      $scope.range.chartRefresh++;
    };

    locationChanges.on($scope, function () {
      var priorAppliedPercentiles = appliedPercentiles;
      if ($location.search().percentile) {
        appliedPercentiles = [];
        var percentile = $location.search().percentile;
        if (angular.isArray(percentile)) {
          angular.forEach(percentile, function (p) {
            appliedPercentiles.push(Number(p));
          });
        } else {
          appliedPercentiles.push(Number(percentile));
        }
        sortNumbers(appliedPercentiles);
      } else {
        appliedPercentiles = $scope.agentRollup.defaultPercentiles;
      }

      if (priorAppliedPercentiles !== undefined && !angular.equals(appliedPercentiles, priorAppliedPercentiles)) {
        $scope.range.chartRefresh++;
      }
      $scope.percentiles = appliedPercentiles;
    });

    function sortNumbers(arr) {
      arr.sort(function (a, b) {
        return a - b;
      });
    }

    function addToQuery(query) {
      query.percentile = appliedPercentiles;
    }

    function onRefreshData(data) {
      $scope.transactionCounts = data.transactionCounts;
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
          var transactionCount = $scope.transactionCounts[xval];
          if (transactionCount === undefined) {
            return 'No data';
          }
          var from = xval - chartState.dataPointIntervalMillis;
          // this math is to deal with live aggregate
          from = Math.ceil(from / chartState.dataPointIntervalMillis) * chartState.dataPointIntervalMillis;
          var to = xval;
          return charts.renderTooltipHtml(from, to, transactionCount, flotItem.dataIndex,
              flotItem.seriesIndex, chartState.plot, function (value) {
                return $filter('gtMillis')(value) + ' milliseconds';
              });
        }
      }
    };

    charts.init(chartState, $('#chart'), $scope);
    charts.plot([[]], chartOptions, chartState, $('#chart'), $scope);
    charts.initResize(chartState.plot, $scope);
    charts.startAutoRefresh($scope, 60000);
  }
]);
