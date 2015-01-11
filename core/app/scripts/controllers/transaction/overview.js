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
  'charts',
  'keyedColorPools',
  'queryStrings',
  'httpErrors',
  function ($scope, $location, $http, charts, keyedColorPools, queryStrings, httpErrors) {

    $scope.$parent.activeTabItem = 'overview';

    // this is needed when click occurs on sidebar b/c it doesn't reload template in that case but still need
    // to update transactionName
    $scope.$parent.transactionName = $location.search()['transaction-name'];

    var chartState = {
      plot: undefined,
      currentRefreshId: 0,
      currentZoomId: 0,
      keyedColorPool: keyedColorPools.create()
    };

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
          var from = xval - 1000 * $scope.layout.fixedAggregateIntervalSeconds / 2;
          var to = xval + 1000 * $scope.layout.fixedAggregateIntervalSeconds / 2;
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
