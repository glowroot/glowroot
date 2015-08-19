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

glowroot.controller('TransactionAverageCtrl', [
  '$scope',
  '$location',
  'charts',
  function ($scope, $location, charts) {

    $scope.$parent.activeTabItem = 'time';

    if ($scope.last) {
      // force the sidebar to update
      $scope.$parent.chartRefresh++;
    }

    var chartState = charts.createState();

    function refreshData() {
      charts.refreshData('backend/transaction/average', chartState, $scope, undefined, onRefreshData);
    }

    $scope.$watchGroup(['chartFrom', 'chartTo', 'chartRefresh'], function () {
      refreshData();
    });

    $scope.clickTopRadioButton = function (item, event) {
      if (item === 'average') {
        $scope.$parent.chartRefresh++;
      } else {
        $location.url('transaction/percentiles' + $scope.tabQueryString());
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

    function onRefreshData(data, query) {
      // mergedAggregate.timers is always synthetic root timer
      var syntheticRootTimer = data.mergedAggregate.syntheticRootTimer;
      if (syntheticRootTimer.childNodes.length === 1) {
        // strip off synthetic root node
        data.mergedAggregate.rootTimer = syntheticRootTimer.childNodes[0];
      } else {
        syntheticRootTimer.name = '<multiple root nodes>';
      }
      $scope.transactionCounts = data.transactionCounts;
      $scope.mergedAggregate = data.mergedAggregate;
      $scope.threadInfoAggregate = data.threadInfoAggregate;
      $scope.lastDurationMillis = query.to - query.from;
      if ($scope.mergedAggregate.transactionCount) {
        updateTreeTimers();
        updateFlattenedTimers();
      }
    }

    function updateTreeTimers() {
      var treeTimers = [];

      function traverse(timer, nestingLevel) {
        timer.nestingLevel = nestingLevel;
        treeTimers.push(timer);
        if (timer.childNodes) {
          timer.childNodes.sort(function (a, b) {
            return b.totalMicros - a.totalMicros;
          });
          $.each(timer.childNodes, function (index, nestedTimer) {
            traverse(nestedTimer, nestingLevel + 1);
          });
        }
      }

      traverse($scope.mergedAggregate.rootTimer, 0);

      $scope.treeTimers = treeTimers;
    }

    function updateFlattenedTimers() {
      var flattenedTimerMap = {};
      var flattenedTimers = [];

      function traverse(timer, parentTimerNames) {
        var flattenedTimer = flattenedTimerMap[timer.name];
        if (!flattenedTimer) {
          flattenedTimer = {
            name: timer.name,
            totalMicros: timer.totalMicros,
            count: timer.count
          };
          flattenedTimerMap[timer.name] = flattenedTimer;
          flattenedTimers.push(flattenedTimer);
        } else if (parentTimerNames.indexOf(timer.name) === -1) {
          // only add to existing flattened timer if the aggregate timer isn't appearing under itself
          // (this is possible when they are separated by another aggregate timer)
          flattenedTimer.totalMicros += timer.totalMicros;
          flattenedTimer.count += timer.count;
        }
        if (timer.childNodes) {
          $.each(timer.childNodes, function (index, nestedTimer) {
            traverse(nestedTimer, parentTimerNames.concat(timer));
          });
        }
      }

      traverse($scope.mergedAggregate.rootTimer, []);

      flattenedTimers.sort(function (a, b) {
        return b.totalMicros - a.totalMicros;
      });

      $scope.flattenedTimers = flattenedTimers;
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
              flotItem.seriesIndex, chartState.plot, function (value) {
                return (100 * value / total).toFixed(1) + ' %';
              });
        }
      }
    };

    charts.init(chartState, $('#chart'), $scope.$parent);
    charts.plot([[]], chartOptions, chartState, $('#chart'), $scope.$parent);
    charts.initResize(chartState.plot, $scope);
  }
]);
