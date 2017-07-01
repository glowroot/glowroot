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

/* global glowroot, moment, $ */

glowroot.controller('TransactionThroughputCtrl', [
  '$scope',
  '$location',
  '$filter',
  'charts',
  function ($scope, $location, $filter, charts) {

    $scope.$parent.activeTabItem = 'time';

    if ($scope.hideMainContent()) {
      return;
    }

    var chartState = charts.createState();

    // using $watch instead of $watchGroup because $watchGroup has confusing behavior regarding oldValues
    // (see https://github.com/angular/angular.js/pull/12643)
    $scope.$watch('[range.chartFrom, range.chartTo, range.chartRefresh, range.chartAutoRefresh]',
        function (newValues, oldValues) {
          var autoRefresh = newValues[3] !== oldValues[3];
          charts.refreshData('backend/transaction/throughput', chartState, $scope, autoRefresh, undefined,
              onRefreshData);
        });

    $scope.clickTopRadioButton = function (item) {
      if (item === 'throughput') {
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

    function onRefreshData(data) {
      $scope.transactionCount = data.transactionCount;
      $scope.transactionsPerMin = data.transactionsPerMin;
    }

    var chartOptions = {
      tooltip: true,
      series: {
        stack: false
      },
      yaxis: {
        label: 'transactions per minute'
      },
      tooltipOpts: {
        content: function (label, xval, yval) {
          var transactionsPerMin = yval;
          if (transactionsPerMin === 0) {
            return 'No data';
          }
          var from = xval - chartState.dataPointIntervalMillis;
          // this math is to deal with live aggregate
          from = Math.ceil(from / chartState.dataPointIntervalMillis) * chartState.dataPointIntervalMillis;
          var to = xval;

          function smartFormat(millis) {
            if (millis % 60000 === 0) {
              return moment(millis).format('LT');
            } else {
              return moment(millis).format('LTS');
            }
          }

          var html = '<div class="gt-chart-tooltip">';
          html += '<div style="font-weight: 600;">';
          html += smartFormat(from);
          html += ' to ';
          html += smartFormat(to);
          html += '</div>';
          html += '<div>';
          html += $filter('gtMillis')(transactionsPerMin);
          html += ' transactions per minute';
          html += '</div>';
          html += '</div>';
          return html;
        }
      }
    };

    charts.init(chartState, $('#chart'), $scope);
    charts.plot([[]], chartOptions, chartState, $('#chart'), $scope);
    charts.initResize(chartState.plot, $scope);
    charts.startAutoRefresh($scope, 60000);
  }
]);
