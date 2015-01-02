/*
 * Copyright 2013-2015 the original author or authors.
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

/* global glowroot, angular, HandlebarsRendering, $ */

glowroot.controller('PerformanceCtrl', [
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
    document.title = 'Performance \u00b7 Glowroot';
    $scope.$parent.activeNavbarItem = 'performance';

    var chartState = {
      fixedAggregateIntervalMillis: $scope.layout.fixedAggregateIntervalSeconds * 1000,
      plot: undefined,
      currentRefreshId: 0,
      currentZoomId: 0,
      keyedColorPool: keyedColorPools.create(),
      chartFromToDefault: false,
      refreshData: refreshData
    };

    $scope.defaultSummarySortOrder = 'total-time';
    $scope.summaryLimit = 10;

    $scope.showMoreSummariesSpinner = 0;

    $scope.tracesQueryString = function () {
      var query = {
        transactionType: $scope.transactionType,
        transactionName: $scope.transactionName,
        transactionNameComparator: 'equals',
        from: $scope.chartFrom,
        to: $scope.chartTo
      };
      return queryStrings.encodeObject(query);
    };

    function refreshData() {
      charts.updateLocation(chartState, $scope);
      var query = {
        from: $scope.chartFrom,
        to: $scope.chartTo,
        transactionType: $scope.transactionType,
        transactionName: $scope.transactionName,
        summarySortOrder: $scope.summarySortOrder,
        summaryLimit: $scope.summaryLimit
      };
      var date = $scope.filterDate;
      if (!date) {
        // TODO display 'Missing date' message
        return;
      }
      $scope.showChartSpinner++;
      var refreshId = ++chartState.currentRefreshId;
      $http.get('backend/performance/data?' + queryStrings.encodeObject(query))
          .success(function (data) {
            $scope.showChartSpinner--;
            if (refreshId !== chartState.currentRefreshId) {
              return;
            }
            $scope.chartNoData = !data.dataSeries.length;
            // reset axis in case user changed the date and then zoomed in/out to trigger this refresh
            chartState.plot.getAxes().xaxis.options.min = query.from;
            chartState.plot.getAxes().xaxis.options.max = query.to;
            chartState.plot.getAxes().xaxis.options.zoomRange = [
              date.getTime(),
              date.getTime() + 24 * 60 * 60 * 1000
            ];
            var plotData = [];
            var labels = [];
            angular.forEach(data.dataSeries, function (dataSeries) {
              labels.push(dataSeries.name ? dataSeries.name : 'Other');
            });
            chartState.keyedColorPool.reset(labels);
            angular.forEach(data.dataSeries, function (dataSeries, index) {
              var label = labels[index];
              plotData.push({
                data: dataSeries.data,
                label: label,
                color: chartState.keyedColorPool.get(label)
              });
            });
            if (plotData.length) {
              chartState.plot.setData(plotData);
            } else {
              chartState.plot.setData([[]]);
            }
            chartState.plot.setupGrid();
            chartState.plot.draw();
            updateMergedAggregate(data.mergedAggregate);
            $scope.traceCount = data.traceCount;
            charts.onRefreshUpdateSummaries(data, query.from, query.to, query.summarySortOrder, $scope);
          })
          .error(function (data, status) {
            $scope.showChartSpinner--;
            if (refreshId !== chartState.currentRefreshId) {
              return;
            }
            httpErrors.handler($scope)(data, status);
          });
    }

    $scope.$watch('filterDate', function (newValue, oldValue) {
      if (newValue && newValue !== oldValue) {
        charts.refreshButtonClick(chartState, $scope);
      }
    });

    $scope.overallSummaryValue = function () {
      if ($scope.lastSummarySortOrder === 'total-time') {
        return '100 %';
      } else if ($scope.lastSummarySortOrder === 'average-time') {
        return ($scope.overallSummary.totalMicros / (1000 * $scope.overallSummary.transactionCount)).toFixed(1) + ' ms';
      } else if ($scope.lastSummarySortOrder === 'throughput') {
        return (60 * 1000 * $scope.overallSummary.transactionCount / $scope.lastSummaryDuration).toFixed(1) + '/min';
      } else {
        // TODO handle this better
        return '???';
      }
    };

    $scope.transactionSummaryValue = function (transactionSummary) {
      if ($scope.lastSummarySortOrder === 'total-time') {
        return (100 * transactionSummary.totalMicros / $scope.overallSummary.totalMicros).toFixed(1) + ' %';
      } else if ($scope.lastSummarySortOrder === 'average-time') {
        return (transactionSummary.totalMicros / (1000 * transactionSummary.transactionCount)).toFixed(1) + ' ms';
      } else if ($scope.lastSummarySortOrder === 'throughput') {
        return (60 * 1000 * transactionSummary.transactionCount / $scope.lastSummaryDuration).toFixed(1) + '/min';
      } else {
        // TODO handle this better
        return '???';
      }
    };

    $scope.toggleProfile = function (event) {
      var query = {
        from: $scope.chartFrom,
        to: $scope.chartTo,
        transactionType: $scope.transactionType,
        transactionName: $scope.transactionName,
        truncateLeafPercentage: 0.001
      };
      HandlebarsRendering.profileToggle($(event.target), '#profileOuter', null,
          'backend/performance/profile?' + queryStrings.encodeObject(query));
    };

    $scope.flameGraphQueryString = function () {
      var query = {
        from: $scope.chartFrom,
        to: $scope.chartTo,
        transactionType: $scope.transactionType,
        transactionName: $scope.transactionName
      };
      return queryStrings.encodeObject(query);
    };

    function updateMergedAggregate(mergedAggregate) {
        $scope.mergedAggregate = mergedAggregate;
        if ($scope.mergedAggregate.count) {
          updateTreeMetrics();
          updateFlattenedMetrics();
          var $profileOuter = $('#profileOuter');
          if (!$profileOuter.hasClass('hide')) {
            $profileOuter.addClass('hide');
            $profileOuter.data('loaded', false);
          }
          var $profileFilter = $profileOuter.find('.profile-filter');
          var $profile = $profileOuter.find('.profile');
          $profileFilter.html('');
          $profile.html('');
        } else {
          $('#detail').html('No data');
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

    charts.initScope(chartState, 'performance', $scope);
    charts.initChart($('#chart'), chartState, $scope);
  }
]);
