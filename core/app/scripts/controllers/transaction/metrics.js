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

glowroot.controller('TransactionMetricsCtrl', [
  '$scope',
  '$location',
  '$http',
  'charts',
  function ($scope, $location, $http, charts) {

    $scope.$parent.activeTabItem = 'metrics';

    var chartState = charts.createState();

    $scope.$watchGroup(['chartFrom', 'chartTo'], function (oldValues, newValues) {
      if (newValues !== oldValues) {
        charts.refreshData('backend/transaction/metrics', chartState, $scope, onRefreshData);
      }
    });

    function onRefreshData(data) {
      $scope.transactionCounts = data.transactionCounts;
      $scope.mergedAggregate = data.mergedAggregate;
      if ($scope.mergedAggregate.transactionCount) {
        updateTreeMetrics();
        updateFlattenedMetrics();
      }
    }

    function updateTreeMetrics() {
      var treeMetrics = [];

      function traverse(metric, nestingLevel) {
        metric.nestingLevel = nestingLevel;
        treeMetrics.push(metric);
        if (metric.nestedMetrics) {
          metric.nestedMetrics.sort(function (a, b) {
            return b.totalMicros - a.totalMicros;
          });
          $.each(metric.nestedMetrics, function (index, nestedMetric) {
            traverse(nestedMetric, nestingLevel + 1);
          });
        }
      }

      traverse($scope.mergedAggregate.metrics, 0);

      $scope.treeMetrics = treeMetrics;
    }

    function updateFlattenedMetrics() {
      var flattenedMetricMap = {};
      var flattenedMetrics = [];

      function traverse(metric, parentMetricNames) {
        var flattenedMetric = flattenedMetricMap[metric.name];
        if (!flattenedMetric) {
          flattenedMetric = {
            name: metric.name,
            totalMicros: metric.totalMicros,
            count: metric.count
          };
          flattenedMetricMap[metric.name] = flattenedMetric;
          flattenedMetrics.push(flattenedMetric);
        } else if (parentMetricNames.indexOf(metric.name) === -1) {
          // only add to existing flattened metric if the aggregate metric isn't appearing under itself
          // (this is possible when they are separated by another aggregate metric)
          flattenedMetric.totalMicros += metric.totalMicros;
          flattenedMetric.count += metric.count;
        }
        if (metric.nestedMetrics) {
          $.each(metric.nestedMetrics, function (index, nestedMetric) {
            traverse(nestedMetric, parentMetricNames.concat(metric));
          });
        }
      }

      traverse($scope.mergedAggregate.metrics, []);

      flattenedMetrics.sort(function (a, b) {
        return b.totalMicros - a.totalMicros;
      });

      $scope.flattenedMetrics = flattenedMetrics;
    }

    var chartOptions = {
      tooltip: true,
      tooltipOpts: {
        content: function (label, xval, yval, flotItem) {
          var total = 0;
          var seriesIndex;
          var dataSeries;
          var value;
          var plotData = chartState.plot.getData();
          for (seriesIndex = 0; seriesIndex < plotData.length; seriesIndex++) {
            dataSeries = plotData[seriesIndex];
            value = dataSeries.data[flotItem.dataIndex][1];
            total += value;
          }
          if (total === 0) {
            return 'No data';
          }
          var from = xval - chartState.dataPointIntervalMillis;
          // this math is to deal with active aggregate
          from = Math.ceil(from / chartState.dataPointIntervalMillis) * chartState.dataPointIntervalMillis;
          var to = xval;
          return charts.renderTooltipHtml(from, to, $scope.transactionCounts[xval], flotItem.dataIndex,
              flotItem.seriesIndex, chartState, function (value) {
                return (100 * value / total).toFixed(1) + ' %';
              });
        }
      }
    };

    charts.init(chartState, $('#chart'), $scope);
    charts.plot([[]], chartOptions, chartState, $('#chart'), $scope);
    charts.refreshData('backend/transaction/metrics', chartState, $scope, onRefreshData);
  }
]);
