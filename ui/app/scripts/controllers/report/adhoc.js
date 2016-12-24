/*
 * Copyright 2016 the original author or authors.
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

/* global glowroot, angular, moment, $ */

glowroot.controller('ReportAdhocCtrl', [
  '$scope',
  '$location',
  '$http',
  '$filter',
  '$timeout',
  'locationChanges',
  'charts',
  'keyedColorPools',
  'queryStrings',
  'httpErrors',
  function ($scope, $location, $http, $filter, $timeout, locationChanges, charts, keyedColorPools, queryStrings, httpErrors) {

    $scope.$parent.heading = 'Ad hoc report';

    $scope.report = {};

    $scope.range = {};

    $scope.showChartSpinner = 0;

    $scope.metrics = [
      {
        id: 'response-time-avg',
        display: 'Response time: average'
      },
      {
        id: 'response-time-percentile',
        display: 'Response time: percentile'
      },
      {
        id: 'throughput',
        display: 'Throughput'
      }
    ];

    $scope.rollups = [
      'Hourly',
      'Daily',
      'Weekly',
      'Monthly'
    ];

    $scope.NEGATIVE_INFINITY = Number.NEGATIVE_INFINITY;

    $scope.currentTabUrl = function () {
      return 'report/ad-hoc';
    };

    $scope.hasAgents = function () {
      return !angular.equals({}, $scope.layout.agentRollups);
    };

    var temp = {};
    angular.forEach($scope.layout.agentRollups, function (agentRollup) {
      angular.forEach(agentRollup.transactionTypes, function (transactionType) {
        temp[transactionType] = true;
      });
    });

    $scope.allTransactionTypes = Object.keys(temp).sort();

    var appliedReport;

    var keyedColorPool = keyedColorPools.create();
    var plot;
    var yvalMaps = {};

    $scope.tableRows = [];

    var browserTimeZone = moment.tz.guess();

    locationChanges.on($scope, function () {
      var priorAppliedReport = appliedReport;
      appliedReport = {};
      appliedReport.agentRollupIds = $location.search()['agent-rollup-id'];
      if (appliedReport.agentRollupIds === undefined) {
        appliedReport.agentRollupIds = [];
      } else if (!angular.isArray(appliedReport.agentRollupIds)) {
        appliedReport.agentRollupIds = [appliedReport.agentRollupIds];
      }
      appliedReport.metricId = $location.search()['metric-id'];
      appliedReport.metricPercentile = Number($location.search()['metric-percentile']);
      if (isNaN(appliedReport.metricPercentile)) {
        delete appliedReport.metricPercentile;
      }
      appliedReport.transactionType = $location.search()['transaction-type'];
      appliedReport.fromDate = $location.search()['from-date'];
      appliedReport.toDate = $location.search()['to-date'];
      if (appliedReport.fromDate && appliedReport.toDate) {
        // both from-date and to-date must be supplied or neither will take effect
        appliedReport.fromDate = moment(appliedReport.fromDate).valueOf();
        appliedReport.toDate = moment(appliedReport.toDate).valueOf();
      } else {
        appliedReport.fromDate = moment().startOf('day').subtract(7, 'days').valueOf();
        appliedReport.toDate = moment().startOf('day').subtract(1, 'days').valueOf();
      }
      appliedReport.rollup = $location.search().rollup || 'daily';
      appliedReport.timeZoneId = $location.search()['time-zone-id'] || browserTimeZone;

      if (priorAppliedReport !== undefined && !angular.equals(appliedReport, priorAppliedReport)) {
        // e.g. back or forward button was used to navigate
        $scope.showChart = false;
      }

      $scope.report = angular.copy(appliedReport);
    });

    $scope.$watch('report.metricId', function (newValue) {
      if (newValue !== 'response-time-percentile') {
        $scope.report.metricPercentile = undefined;
      }
    });

    $scope.$watch('report', function () {
      // clear button error message if any
      var $buttonMessage = $('.gt-form-buttons .gt-button-message');
      $buttonMessage.text('');
      $buttonMessage.removeClass('gt-button-message-error');
      $buttonMessage.addClass('gt-button-message-success');
    }, true);

    $scope.runReport = function (deferred) {
      if (!$scope.layout.embedded && !$scope.report.agentRollupIds.length) {
        deferred.reject('Select one or more agents');
        return;
      }
      if (!$scope.report.metricId) {
        deferred.reject('Select metric');
        return;
      }
      if ($scope.report.metricId === 'response-time-percentile'
          && !$scope.report.metricPercentile && $scope.report.metricPercentile !== 0) {
        deferred.reject('Select percentile');
        return;
      }
      if (!$scope.report.transactionType) {
        deferred.reject('Select transaction type');
        return;
      }
      if ($scope.report.rollup === 'weekly'
          && moment($scope.report.toDate).diff(moment($scope.report.fromDate), 'days') < 6) {
        deferred.reject('Must select at least one full week when using weekly rollup');
        return;
      }
      if ($scope.report.rollup === 'monthly') {
        if (moment($scope.report.fromDate).date() !== 1) {
          deferred.reject('From date must be the first day of a month when using monthly rollup');
          return;
        }
        if (moment($scope.report.toDate).valueOf() < moment($scope.report.fromDate).endOf('month').startOf('day')) {
          deferred.reject('Must select at least one full month when using month rollup');
          return;
        }
      }
      if (!$scope.layout.embedded) {
        $location.search('agent-rollup-id', $scope.report.agentRollupIds);
      }
      $location.search('metric-id', $scope.report.metricId);
      $location.search('metric-percentile', $scope.report.metricPercentile);
      $location.search('transaction-type', $scope.report.transactionType);
      var fromDate = moment($scope.report.fromDate).format('YYYYMMDD');
      var toDate = moment($scope.report.toDate).format('YYYYMMDD');
      $location.search('from-date', fromDate);
      $location.search('to-date', toDate);
      if ($scope.report.rollup === 'daily') {
        $location.search('rollup', undefined);
      } else {
        $location.search('rollup', $scope.report.rollup);
      }
      // always include timeZone, even if default, since copy pasting url should not change data
      $location.search('time-zone-id', $scope.report.timeZoneId);
      deferred.resolve();
      appliedReport = angular.copy($scope.report);
      refreshData();
    };

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

    function refreshData() {
      var query = angular.copy(appliedReport);
      query.fromDate = moment(appliedReport.fromDate).format('YYYYMMDD');
      query.toDate = moment(appliedReport.toDate).format('YYYYMMDD');
      if (appliedReport.metricId !== 'response-time-percentile') {
        delete query.metricPercentile;
      }
      if ($scope.layout.embedded) {
        query.agentRollupIds = [''];
      }
      $scope.showChartSpinner++;
      $scope.showChart = true;
      $http.get('backend/report' + queryStrings.encodeObject(query))
          .then(function (response) {
            $scope.showChartSpinner--;
            if ($scope.showChartSpinner) {
              // ignore this response, another response has been stacked
              return;
            }
            var nodata = true;
            var data = response.data;
            for (var i = 0; i < data.dataSeries.length; i++) {
              var points = data.dataSeries[i].data;
              if (nodata) {
                nodata = points.length === 0;
              }
            }
            $scope.chartNoData = nodata;
            var plotData = [];
            var labels = [];
            var allXvalsMap = {};
            angular.forEach(data.dataSeries, function (dataSeries) {
              labels.push(dataSeries.name);
              angular.forEach(dataSeries.data, function (point) {
                if (point) {
                  // need to use value, since getting keys later
                  allXvalsMap[point[0]] = point[0];
                }
              });
            });
            // cannot use Object.keys to get xvals since that returns array of strings, not array of numbers
            $scope.allXvals = [];
            angular.forEach(allXvalsMap, function (xval) {
              $scope.allXvals.push(xval);
            });
            $scope.allXvals.sort();
            keyedColorPool.reset(labels);
            yvalMaps = {};
            $scope.tableRows = [];
            angular.forEach(query.agentRollupIds, function (agentRollupId, index) {
              var dataSeries = data.dataSeries[index];
              var label = dataSeries.name;
              updateYvalMap(label, dataSeries.data);
              var tableRow = {};
              var yvalMap = yvalMaps[label];
              angular.forEach($scope.allXvals, function (xval) {
                var yval = yvalMap[xval];
                if (yval === undefined) {
                  tableRow['' + xval] = Number.NEGATIVE_INFINITY; // for sorting purposes
                } else {
                  tableRow['' + xval] = yval;
                }
              });
              tableRow.label = label;
              if (dataSeries.overall === undefined) {
                tableRow.overall = Number.NEGATIVE_INFINITY; // for sorting purposes
              } else {
                tableRow.overall = dataSeries.overall;
              }
              tableRow.agentRollupId = agentRollupId;
              $scope.tableRows.push(tableRow);
              var plotDataItem = {
                data: dataSeries.data,
                label: label,
                shortLabel: dataSeries.shortLabel,
                color: keyedColorPool.get(label),
                points: {
                  fillColor: keyedColorPool.get(label)
                }
              };
              plotData.push(plotDataItem);
            });
            if (query.metricId === 'response-time-avg' || query.metricId === 'response-time-percentile') {
              plot.getAxes().yaxis.options.label = 'milliseconds';
            } else if (query.metricId === 'throughput') {
              plot.getAxes().yaxis.options.label = 'transactions per minute';
            } else {
              plot.getAxes().yaxis.options.label = '';
            }
            plot.getAxes().xaxis.options.timezone = query.timeZoneId;
            plot.getAxes().xaxis.options.min = moment.tz(query.fromDate, query.timeZoneId);
            plot.getAxes().xaxis.options.max = moment.tz(query.toDate, query.timeZoneId).add(1, 'days');
            plot.setData(plotData);
            plot.setupGrid();
            plot.draw();

            // update legend
            $scope.seriesLabels = [];
            var seriesIndex;
            for (seriesIndex = 0; seriesIndex < plotData.length; seriesIndex++) {
              $scope.seriesLabels.push({
                color: plotData[seriesIndex].color,
                text: plotData[seriesIndex].label,
                agentRollupId: query.agentRollupIds[seriesIndex]
              });
            }
          }, function (response) {
            $scope.showChartSpinner--;
            httpErrors.handle(response, $scope);
          });
    }

    function getFromTo(xval) {
      var from, to;
      if (appliedReport.rollup === 'hourly') {
        from = moment(xval - 30 * 60 * 1000);
        from.tz(appliedReport.timeZoneId);
        to = moment(from);
        to.add(1, 'hour');
      } else if (appliedReport.rollup === 'daily') {
        from = moment(xval);
        from.tz(appliedReport.timeZoneId);
        from.startOf('day');
        to = moment(from);
        to.add(1, 'days');
      } else if (appliedReport.rollup === 'weekly') {
        from = moment(xval);
        from.tz(appliedReport.timeZoneId);
        from.subtract(3, 'days').startOf('day');
        to = moment(from);
        to.add(7, 'days');
      } else if (appliedReport.rollup === 'monthly') {
        from = moment(xval);
        from.tz(appliedReport.timeZoneId);
        from.startOf('month');
        to = moment(from);
        to.add(1, 'month');
      }
      return [from, to];
    }

    $scope.columnHeader = function (xval) {
      var fromTo = getFromTo(xval);
      var from = fromTo[0];
      var to = fromTo[1];
      if (appliedReport.rollup === 'hourly') {
        return from.format('L') + ' ' + from.format('LT') + ' to ' + to.format('LT');
      } else if (appliedReport.rollup === 'daily') {
        return from.format('ddd L');
      } else if (appliedReport.rollup === 'weekly') {
        to.subtract(1, 'days');
        return moment(from).format('ddd L') + ' through ' + moment(to).format('ddd L');
      } else if (appliedReport.rollup === 'monthly') {
        to.subtract(1, 'days');
        return moment(from).format('L') + ' through ' + moment(to).format('L');
      }
    };

    $scope.sortIconClass = function (attr) {
      if ($scope.sortAttribute !== attr) {
        return 'caret gt-visibility-hidden';
      }
      if ($scope.sortAsc) {
        return 'caret gt-caret-sort-ascending';
      } else {
        return 'caret';
      }
    };

    $scope.sort = function (attr) {
      if ($scope.sortAttribute === attr) {
        $scope.sortAsc = !$scope.sortAsc;
      } else {
        $scope.sortAsc = false;
      }
      $scope.sortAttribute = attr;
      $scope.sortAttr = '-' + attr;
    };

    function drillDownLink(agentRollupId, from, to) {
      var path;
      if (appliedReport.metricId === 'response-time-avg') {
        path = 'average';
      } else if (appliedReport.metricId === 'response-time-percentile') {
        path = 'percentiles';
      } else if (appliedReport.metricId === 'throughput') {
        path = 'throughput';
      }
      var url = 'transaction/' + path + '?agent-rollup-id=' + encodeURIComponent(agentRollupId) + '&from=' + from
          + '&to=' + to + '&transaction-type=' + encodeURIComponent(appliedReport.transactionType);
      if (appliedReport.metricId === 'response-time-percentile') {
        url += '&percentile=' + appliedReport.metricPercentile;
      }
      return url;
    }

    $scope.drillDownLink = function (agentRollupId, xval) {
      var fromTo = getFromTo(xval);
      return drillDownLink(agentRollupId, fromTo[0], fromTo[1]);
    };

    $scope.drillDownLinkOverall = function (agentRollupId) {
      var from = appliedReport.fromDate;
      var to = getFromTo(appliedReport.toDate)[1];
      return drillDownLink(agentRollupId, from, to);
    };

    var options = {
      grid: {
        borderColor: '#7d7358',
        borderWidth: 1,
        // this is needed for tooltip plugin to work
        hoverable: true
      },
      xaxis: {
        mode: 'time',
        // this is updated dynamically based on selected time zone
        timezone: 'browser',
        twelveHourClock: true,
        ticks: 5,
        reserveSpace: false
      },
      yaxis: {
        ticks: 10,
        zoomRange: false,
        min: 0
      },
      series: {
        lines: {
          show: true
        },
        points: {
          radius: 8
        }
      },
      legend: {
        show: false
      },
      tooltip: true,
      tooltipOpts: {
        content: function (label, xval, yval, flotItem) {
          var fromTo = getFromTo(xval);
          var from = fromTo[0];
          var to = fromTo[1];
          var dateFormat;
          var altBetweenText;
          if (appliedReport.rollup === 'hourly') {
            dateFormat = 'LT';
            // normal between text ' to '
          } else if (appliedReport.rollup === 'daily') {
            to = undefined;
            dateFormat = 'ddd L';
            // there is no between text in this case since 'to' is undefined
          } else if (appliedReport.rollup === 'weekly') {
            to.subtract(1, 'days');
            dateFormat = 'ddd L';
            altBetweenText = ' through ';
          } else if (appliedReport.rollup === 'monthly') {
            to.subtract(1, 'days');
            dateFormat = 'L';
            altBetweenText = ' through ';
          }
          return charts.renderTooltipHtml(from, to, undefined, flotItem.dataIndex,
              flotItem.seriesIndex, plot, function (value, label) {
                var nonScaledValue = yvalMaps[label][xval];
                if (nonScaledValue === undefined) {
                  return 'no data';
                }
                return $filter('gtMillis')(nonScaledValue) + ' ' + plot.getAxes().yaxis.options.label;
              }, undefined, true, dateFormat, altBetweenText);
        }
      }
    };
    plot = $.plot($('#chart'), [[]], options);
    $scope.$watchGroup(['containerWidth', 'windowHeight'], function () {
      plot.resize();
      plot.setupGrid();
      plot.draw();
    });
  }
]);
