/*
 * Copyright 2017-2018 the original author or authors.
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

/* global glowroot, angular, $, moment */

glowroot.controller('SyntheticMonitorsCtrl', [
  '$scope',
  '$location',
  '$filter',
  '$http',
  '$timeout',
  'locationChanges',
  'charts',
  'queryStrings',
  function ($scope, $location, $filter, $http, $timeout, locationChanges, charts, queryStrings) {

    // \u00b7 is &middot;
    document.title = 'Synthetic \u00b7 Glowroot';
    $scope.$parent.activeNavbarItem = 'syntheticMonitor';

    var chartState = charts.createState();

    var yvalMaps = {};

    var executionCounts = {};

    var showingAll;

    var displayMap = {};

    $scope.chartNoData = true;

    // this is needed by nested controller chart-range.js
    $scope.currentTabUrl = function () {
      return 'synthetic-monitors';
    };

    $scope.range = {};

    $scope.agentRollupUrl = function (agentRollupId) {
      var query = $scope.agentRollupQuery(agentRollupId);
      delete query['synthetic-monitor-id'];
      return $location.path().substring(1) + queryStrings.encodeObject(query);
    };

    $scope.$watch('seriesLabels', function (newValues, oldValues) {
      if (newValues !== oldValues) {
        var i;
        for (i = 0; i < newValues.length; i++) {
          var shortDisplay = displayMap[newValues[i].text];
          if (shortDisplay) {
            newValues[i].text = shortDisplay;
          }
        }
      }
    });

    $scope.buildQueryObjectForChartRange = function (last) {
      return buildQueryObject(last);
    };

    function buildQueryObject(last) {
      var query = {};
      var agentId = $location.search()['agent-id'];
      if (agentId) {
        query['agent-id'] = agentId;
      } else {
        query['agent-rollup-id'] = $location.search()['agent-rollup-id'];
      }
      if (showingAll) {
        delete query['synthetic-monitor-id'];
      } else if ($scope.syntheticMonitorIds && $scope.syntheticMonitorIds.length === 0) {
        // special case to differentiate between no synthetic monitors selected and default "all"
        query['synthetic-monitor-id'] = '';
      } else {
        query['synthetic-monitor-id'] = $scope.syntheticMonitorIds;
      }
      if (!last) {
        query.from = $scope.range.chartFrom;
        query.to = $scope.range.chartTo;
      } else if (last !== 4 * 60 * 60 * 1000) {
        query.last = last;
      }
      return query;
    }

    $scope.hideMainContent = function () {
      return !$scope.agentRollupId && !$scope.agentId;
    };

    function refreshData(autoRefresh) {
      charts.refreshData('backend/synthetic-monitor/results', chartState, $scope, autoRefresh, addToQuery,
          onRefreshData);
    }

    function watchListener(autoRefresh) {
      $location.search(buildQueryObject($scope.range.last));
      if ($scope.syntheticMonitorIds.length) {
        refreshData(autoRefresh);
      } else {
        // ideally wouldn't need to refreshData here, but this seems a rare condition (to de-select all synthetic
        // monitors) and need some way to clear the last synthetic monitor from the chart, and this is easy
        refreshData(autoRefresh);
        $scope.chartNoData = true;
      }
    }

    function onRefreshData(data) {
      $scope.loaded = true;
      $scope.displayNoSyntheticMonitorsConfigured = data.displayNoSyntheticMonitorsConfigured;
      $scope.allSyntheticMonitors = data.allSyntheticMonitors;

      displayMap = {};
      angular.forEach($scope.allSyntheticMonitors, function (syntheticMonitor) {
        displayMap[syntheticMonitor.id] = syntheticMonitor.display;
      });

      if (showingAll) {
        $scope.syntheticMonitorIds = [];
        angular.forEach($scope.allSyntheticMonitors, function (syntheticMonitor) {
          $scope.syntheticMonitorIds.push(syntheticMonitor.id);
        });
      }
      executionCounts = data.executionCounts;
      yvalMaps = {};
      var i;
      var dataSeries;
      for (i = 0; i < data.dataSeries.length; i++) {
        dataSeries = data.dataSeries[i];
        updateYvalMap(dataSeries.name, dataSeries.data);
        dataSeries.shortLabel = displayMap[dataSeries.name];
      }
    }

    function updateYvalMap(label, points) {
      var map = {};
      var i;
      var point;
      for (i = 0; i < points.length; i++) {
        point = points[i];
        if (point) {
          map[point[0]] = point[1];
        }
      }
      yvalMaps[label] = map;
    }

    function watchExpression(scope) {
      return {
        chartFrom: scope.range.chartFrom,
        chartTo: scope.range.chartTo,
        chartRefresh: scope.range.chartRefresh,
        chartAutoRefresh: scope.range.chartAutoRefresh,
        all: showingAll,
        syntheticMonitorIds: showingAll ? [] : scope.syntheticMonitorIds
      };
    }

    if (!$scope.hideMainContent()) {
      $scope.$watch(watchExpression, function (newValue, oldValue) {
        watchListener(newValue.chartAutoRefresh !== oldValue.chartAutoRefresh);
      }, true);
    }

    var location;

    function addToQuery(query) {
      if (showingAll) {
        query.all = true;
      } else {
        // singular name is used since it is query string
        query.syntheticMonitorId = $scope.syntheticMonitorIds;
      }
    }

    locationChanges.on($scope, function () {
      var priorLocation = location;
      location = {};
      location.last = Number($location.search().last);
      location.chartFrom = Number($location.search().from);
      location.chartTo = Number($location.search().to);
      // both from and to must be supplied or neither will take effect
      if (location.chartFrom && location.chartTo) {
        location.last = 0;
      } else if (!location.last) {
        location.last = 4 * 60 * 60 * 1000;
      }
      location.syntheticMonitorIds = $location.search()['synthetic-monitor-id'];
      showingAll = location.syntheticMonitorIds === undefined;
      if (showingAll) {
        location.syntheticMonitorIds = [];
        angular.forEach($scope.allSyntheticMonitors, function (syntheticMonitor) {
          location.syntheticMonitorIds.push(syntheticMonitor.id);
        });
      } else if (!angular.isArray(location.syntheticMonitorIds)) {
        if (location.syntheticMonitorIds === '') {
          // special case to differentiate between no synthetic monitors selected and default "all"
          location.syntheticMonitorIds = [];
        } else {
          location.syntheticMonitorIds = [location.syntheticMonitorIds];
        }
      }
      if (!angular.equals(location, priorLocation)) {
        // only update scope if relevant change
        $scope.syntheticMonitorIds = angular.copy(location.syntheticMonitorIds);
        $scope.range.last = location.last;
        $scope.range.chartFrom = location.chartFrom;
        $scope.range.chartTo = location.chartTo;
        charts.applyLast($scope);
      }
    });

    $scope.clickSyntheticMonitor = function (syntheticMonitorId) {
      var index = $scope.syntheticMonitorIds.indexOf(syntheticMonitorId);
      if (index === -1) {
        $scope.syntheticMonitorIds.push(syntheticMonitorId);
        // maintain selected synthetic monitor ordering to match ordering of allSyntheticMonitors
        // (which are ordered server-side by case insensitive synthetic monitor display)
        var ordering = {};
        angular.forEach($scope.allSyntheticMonitors, function (syntheticMonitor, index) {
          ordering[syntheticMonitor.id] = index;
        });
        $scope.syntheticMonitorIds.sort(function (a, b) {
          return ordering[a] - ordering[b];
        });
        showingAll = $scope.allSyntheticMonitors.length === $scope.syntheticMonitorIds.length;
      } else {
        $scope.syntheticMonitorIds.splice(index, 1);
        showingAll = false;
      }
    };

    var chartOptions = {
      tooltip: true,
      yaxis: {
        label: 'milliseconds'
      },
      series: {
        stack: false,
        lines: {
          fill: false
        }
      },
      tooltipOpts: {
        content: function (label, xval, yval, flotItem) {
          var rollupConfig0 = $scope.layout.rollupConfigs[0];
          if (chartState.dataPointIntervalMillis === rollupConfig0.intervalMillis) {
            var tooltip = '<table class="gt-chart-tooltip">';
            tooltip += '<tr><td colspan="2" style="font-weight: 600;">' + displayMap[label];
            tooltip += '</td></tr><tr><td style="padding-right: 10px;">Time:</td><td style="font-weight: 400;">';
            tooltip += moment(xval).format('h:mm:ss.SSS a (Z)') + '</td></tr>';
            tooltip += '<tr><td style="padding-right: 10px;">Value:</td><td style="font-weight: 600;">';
            tooltip += $filter('gtMillis')(yval) + ' milliseconds</td></tr>';
            tooltip += '</table>';
            return tooltip;
          }
          var from = xval - chartState.dataPointIntervalMillis;
          // this math is to deal with live aggregate
          from = Math.ceil(from / chartState.dataPointIntervalMillis) * chartState.dataPointIntervalMillis;
          var to = xval;
          return charts.renderTooltipHtml(from, to, undefined, flotItem.dataIndex, flotItem.seriesIndex,
              chartState.plot, function (value, label) {
                var yval = yvalMaps[label][xval];
                if (yval === undefined) {
                  return 'no data';
                }
                return $filter('gtMillis')(yval) + ' milliseconds over ' + executionCounts[flotItem.seriesIndex][xval]
                    + ' executions';
              }, ' (average value over this interval)', true);

        },
        markingContent: function (marking) {

          function smartFormat(millis) {
            var date = moment(millis);
            if (date.valueOf() % 60000 === 0) {
              return date.format('LT');
            } else {
              return date.format('LTS');
            }
          }

          var data = marking.data;
          var html = '<table class="gt-chart-tooltip"><thead><tr><td colspan="2" style="font-weight: 600;">'
              + smartFormat(data.from) + ' to ' + smartFormat(data.to) + '</td></tr></thead><tbody>';

          angular.forEach(data.intervals, function (intervals, syntheticMonitorId) {
            if ($scope.syntheticMonitorIds.length > 1) {
              html += '<tr><td colspan="2">' + displayMap[syntheticMonitorId] + '</td></tr>';
            }
            if ($scope.syntheticMonitorIds.length === 1 && intervals.length === 1) {
              html += '<tr><td colspan="2">' + intervals[0].message + ' (' + intervals[0].count + ' results)</td></tr>';
            } else {
              angular.forEach(intervals, function (interval) {
                html += '<tr><td style="padding-right: 10px;">' + smartFormat(interval.from) + ' to '
                    + smartFormat(interval.to) + '</td><td><span style="font-weight: 600;">' + interval.message
                    + '</span> (' + interval.count + ' results)</td></tr>';
              });
            }
          });
          html += '</tbody></table>';
          return html;
        }
      }
    };

    charts.init(chartState, $('#chart'), $scope);
    charts.plot([[]], chartOptions, chartState, $('#chart'), $scope);
    charts.initResize(chartState.plot, $scope);
    charts.startAutoRefresh($scope, 60000);

    $scope.$watchGroup(['range.chartFrom', 'range.chartTo'], function (newValue, oldValue) {
      if (newValue !== oldValue) {
        // need to refresh selectpicker in order to update hrefs of the items
        $timeout(function () {
          // timeout is needed so this runs after dom is updated
          $('#agentRollupDropdown').selectpicker('refresh');
        });
      }
    });

    var refreshAgentRollups = function () {
      $scope.refreshAgentRollups($scope.range.chartFrom, $scope.range.chartTo, $scope);
    };

    $('#agentRollupDropdown').on('show.bs.select', refreshAgentRollups);

    if ($scope.agentRollups === undefined) {
      refreshAgentRollups();
    }
  }
]);
