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

/* global glowroot, $ */

glowroot.controller('TransactionOverviewCtrl', [
  '$scope',
  '$location',
  '$http',
  'charts',
  function ($scope, $location, $http, charts) {

    $scope.$parent.activeTabItem = 'overview';

    var chartState = charts.createState();

    $scope.$watchGroup(['chartFrom', 'chartTo'], function (oldValues, newValues) {
      if (newValues !== oldValues) {
        charts.refreshData('backend/transaction/overview', chartState, $scope, onRefreshData);
      }
    });

    function onRefreshData(data, query) {
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
          var fixedAggregateIntervalMillis = 1000 * $scope.layout.fixedAggregateIntervalSeconds;
          var from = xval - fixedAggregateIntervalMillis;
          // this math is to deal with active aggregate
          from = Math.ceil(from / fixedAggregateIntervalMillis) * fixedAggregateIntervalMillis;
          var to = xval;
          return charts.renderTooltipHtml(from, to, flotItem.dataIndex, flotItem.seriesIndex, chartState,
              function (value) {
                return value.toFixed(3) + ' seconds';
              });
        }
      }
    };

    charts.init(chartState, $('#chart'), $scope);
    charts.plot([[]], chartOptions, chartState, $('#chart'), $scope);
    charts.refreshData('backend/transaction/overview', chartState, $scope, onRefreshData);
  }
]);
