/*
 * Copyright 2012-2015 the original author or authors.
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

glowroot.controller('TracesCtrl', [
  '$scope',
  '$location',
  '$http',
  '$q',
  '$timeout',
  'charts',
  'httpErrors',
  'traceModal',
  'queryStrings',
  'errorOnly',
  function ($scope, $location, $http, $q, $timeout, charts, httpErrors, traceModal, queryStrings, errorOnly) {

    $scope.$parent.activeTabItem = 'traces';

    var plot;

    var $chart = $('#chart');

    var appliedFilter;

    var defaultFilterLimit = 500;

    $scope.$watchGroup(['containerWidth', 'windowHeight'], function () {
      plot.resize();
      plot.setupGrid();
      plot.draw();
    });

    $scope.showChartSpinner = 0;
    $scope.showErrorFilter = errorOnly;

    $scope.$watchGroup(['chartFrom', 'chartTo', 'chartRefresh'], function (newValues, oldValues) {
      if (newValues !== oldValues) {
        if ($scope.suppressChartRefresh) {
          $scope.suppressChartRefresh = false;
          return;
        }
        if ($scope.useRealChartFromTo) {
          appliedFilter.from = $scope.realChartFrom;
          appliedFilter.to = $scope.realChartTo;
          $scope.useRealChartFromTo = false;
        } else {
          appliedFilter.from = $scope.chartFrom;
          appliedFilter.to = $scope.chartTo;
        }
        updateLocation();
        refreshChart();
      }
    });

    function refreshChart(deferred) {
      var from = appliedFilter.from;
      var to = appliedFilter.to;
      var limit = appliedFilter.limit;
      var durationLow = appliedFilter.durationLow;
      var durationHigh = appliedFilter.durationHigh;
      var query = angular.copy(appliedFilter);
      if ($scope.transactionName) {
        query.transactionNameComparator = 'equals';
        query.transactionName = $scope.transactionName;
      }
      // convert duration from seconds to nanoseconds
      query.durationLow = Math.ceil(query.durationLow * 1000000000);
      if (query.durationHigh) {
        query.durationHigh = Math.floor(query.durationHigh * 1000000000);
      }
      if (errorOnly) {
        query.errorOnly = true;
      }
      $scope.showChartSpinner++;
      $http.get('backend/trace/points' + queryStrings.encodeObject(query))
          .success(function (data) {
            $scope.showChartSpinner--;
            if ($scope.showChartSpinner) {
              return;
            }
            var traceCount = data.normalPoints.length + data.errorPoints.length + data.activePoints.length;
            $scope.chartNoData = traceCount === 0;
            $scope.tracesExpired = data.tracesExpired;
            $scope.chartLimitExceeded = data.limitExceeded;
            $scope.chartLimit = limit;
            // update tab bar in case viewing live data and tab bar trace count is now out of sync
            $scope.$parent.$broadcast('updateTraceTabCount', traceCount);
            // user clicked on Refresh button, need to reset axes
            plot.getAxes().xaxis.options.min = from;
            plot.getAxes().xaxis.options.max = to;
            plot.getAxes().yaxis.options.min = durationLow;
            plot.getAxes().yaxis.options.realMax = durationHigh;
            plot.setData([data.normalPoints, data.errorPoints, data.activePoints]);
            // setupGrid is needed in case yaxis.max === undefined
            plot.setupGrid();
            plot.draw();
            if (deferred) {
              deferred.resolve('Success');
            }
          })
          .error(function (data, status) {
            $scope.showChartSpinner--;
            httpErrors.handler($scope, deferred)(data, status);
          });
    }

    $scope.refreshButtonClick = function () {
      $scope.applyLast();
      angular.extend(appliedFilter, $scope.filter);
      $scope.$parent.chartRefresh++;
    };

    $scope.clearCriteria = function () {
      $scope.filter.durationLow = 0;
      $scope.filter.durationHigh = undefined;
      $scope.filterDurationComparator = 'greater';
      $scope.filter.headlineComparator = 'begins';
      $scope.filter.headline = '';
      $scope.filter.errorComparator = 'begins';
      $scope.filter.error = '';
      $scope.filter.userComparator = 'begins';
      $scope.filter.user = '';
      $scope.filter.customAttributeName = '';
      $scope.filter.customAttributeValueComparator = 'begins';
      $scope.filter.customAttributeValue = '';
      $scope.filter.limit = defaultFilterLimit;
      $scope.refreshButtonClick();
    };

    $chart.bind('plotzoom', function (event, plot, args) {
      var zoomingOut = args.amount && args.amount < 1;
      $scope.$apply(function () {
        var from = plot.getAxes().xaxis.options.min;
        var to = plot.getAxes().xaxis.options.max;
        charts.updateRange($scope, from, to, false, zoomingOut);
        if (zoomingOut) {
          // scroll zooming out, reset duration limits
          updateFilter(from, to, 0, undefined);
        } else {
          // only update from/to
          appliedFilter.from = from;
          appliedFilter.to = to;
        }
        if (zoomingOut || $scope.chartLimitExceeded) {
          $scope.useRealChartFromTo = true;
          $scope.realChartFrom = from;
          $scope.realChartTo = to;
          updateLocation();
        } else {
          $scope.suppressChartRefresh = true;
          // no need to fetch new data
          plot.setData(getFilteredData());
          plot.setupGrid();
          plot.draw();
          updateLocation();
        }
      });
    });

    $chart.bind('plotselected', function (event, ranges) {
      $scope.$apply(function () {
        plot.clearSelection();
        var from = ranges.xaxis.from;
        var to = ranges.xaxis.to;
        updateFilter(from, to, ranges.yaxis.from, ranges.yaxis.to);
        charts.updateRange($scope, from, to, true);
        if ($scope.chartLimitExceeded) {
          $scope.useRealChartFromTo = true;
          $scope.realChartFrom = from;
          $scope.realChartTo = to;
          updateLocation();
        } else {
          $scope.suppressChartRefresh = true;
          plot.getAxes().xaxis.options.min = ranges.xaxis.from;
          plot.getAxes().xaxis.options.max = ranges.xaxis.to;
          plot.getAxes().yaxis.options.min = ranges.yaxis.from;
          plot.getAxes().yaxis.options.realMax = ranges.yaxis.to;
          // no need to fetch new data
          plot.setData(getFilteredData());
          // setupGrid needs to be after setData
          plot.setupGrid();
          plot.draw();
          updateLocation();
        }
      });
    });

    $scope.zoomOut = function () {
      var currMin = $scope.chartFrom;
      var currMax = $scope.chartTo;
      var currRange = currMax - currMin;
      charts.updateRange($scope, currMin - currRange / 2, currMax + currRange / 2, false, true);
    };

    function updateFilter(from, to, durationLow, durationHigh) {
      appliedFilter.from = from;
      appliedFilter.to = to;
      // set both appliedFilter and $scope.filter durationLow/durationHigh
      appliedFilter.durationLow = $scope.filter.durationLow = durationLow;
      appliedFilter.durationHigh = $scope.filter.durationHigh = durationHigh;
      if (durationHigh && durationLow !== 0) {
        $scope.filterDurationComparator = 'between';
      } else if (durationHigh) {
        $scope.filterDurationComparator = 'less';
      } else {
        $scope.filterDurationComparator = 'greater';
      }
    }

    function getFilteredData() {
      var from = plot.getAxes().xaxis.options.min;
      var to = plot.getAxes().xaxis.options.max;
      var durationLow = plot.getAxes().yaxis.options.min;
      var durationHigh = plot.getAxes().yaxis.options.realMax || Number.MAX_VALUE;
      var data = [];
      var i, j;
      var nodata = true;
      for (i = 0; i < plot.getData().length; i++) {
        data.push([]);
        var points = plot.getData()[i].data;
        for (j = 0; j < points.length; j++) {
          var point = points[j];
          if (point[0] >= from && point[0] <= to && point[1] >= durationLow && point[1] <= durationHigh) {
            data[i].push(point);
            nodata = false;
          }
        }
      }
      $scope.chartNoData = nodata;
      return data;
    }

    $chart.bind('plotclick', function (event, pos, item) {
      if (item) {
        plot.unhighlight();
        plot.highlight(item.series, item.datapoint);
        showTrace(item);
      }
    });

    function showTrace(item) {
      var traceId = plot.getData()[item.seriesIndex].data[item.dataIndex][2];
      $scope.$apply(function () {
        traceModal.displayModal(traceId);
      });
    }

    $scope.filterDurationComparatorOptions = [
      {
        display: 'Greater than',
        value: 'greater'
      },
      {
        display: 'Less than',
        value: 'less'
      },
      {
        display: 'Between',
        value: 'between'
      }
    ];

    $scope.filterTextComparatorOptions = [
      {
        display: 'Begins with',
        value: 'begins'
      },
      {
        display: 'Equals',
        value: 'equals'
      },
      {
        display: 'Ends with',
        value: 'ends'
      },
      {
        display: 'Contains',
        value: 'contains'
      },
      {
        display: 'Does not contain',
        value: 'notContains'
      }
    ];

    appliedFilter = {};
    appliedFilter.transactionType = $scope.transactionType;
    appliedFilter.from = $scope.chartFrom;
    appliedFilter.to = $scope.chartTo;
    appliedFilter.durationLow = Number($location.search()['duration-low']) || 0;
    appliedFilter.durationHigh = Number($location.search()['duration-high']) || undefined;
    appliedFilter.headlineComparator = $location.search()['headline-comparator'] || 'begins';
    appliedFilter.headline = $location.search().headline || '';
    appliedFilter.errorComparator = $location.search()['error-comparator'] || 'begins';
    appliedFilter.error = $location.search().error || '';
    appliedFilter.userComparator = $location.search()['user-comparator'] || 'begins';
    appliedFilter.user = $location.search().user || '';
    appliedFilter.customAttributeName = $location.search()['custom-attribute-name'] || '';
    appliedFilter.customAttributeValueComparator = $location.search()['custom-attribute-value-comparator'] || 'begins';
    appliedFilter.customAttributeValue = $location.search()['custom-attribute-value'] || '';
    appliedFilter.limit = Number($location.search().limit) || defaultFilterLimit;

    $scope.filter = angular.copy(appliedFilter);
    // need to remove from and to so they aren't copied back during angular.extend(appliedFilter, $scope.filter)
    delete $scope.filter.from;
    delete $scope.filter.to;

    if (appliedFilter.durationLow !== 0 && appliedFilter.durationHigh) {
      $scope.filterDurationComparator = 'between';
    } else if (appliedFilter.durationHigh) {
      $scope.filterDurationComparator = 'less';
    } else {
      $scope.filterDurationComparator = 'greater';
    }

    $scope.$watch('filterDurationComparator', function (value) {
      if (value === 'greater') {
        $scope.filter.durationHigh = undefined;
      } else if (value === 'less') {
        $scope.filter.durationLow = 0;
      }
    });

    function updateLocation() {
      var query = $scope.buildQueryObject({});
      if (Number(appliedFilter.durationLow)) {
        query['duration-low'] = appliedFilter.durationLow;
      }
      if (Number(appliedFilter.durationHigh)) {
        query['duration-high'] = appliedFilter.durationHigh;
      }
      if (appliedFilter.headline) {
        query['headline-comparator'] = appliedFilter.headlineComparator;
        query.headline = appliedFilter.headline;
      }
      if (appliedFilter.error) {
        query['error-comparator'] = appliedFilter.errorComparator;
        query.error = appliedFilter.error;
      }
      if (appliedFilter.user) {
        query['user-comparator'] = appliedFilter.userComparator;
        query.user = appliedFilter.user;
      }
      if (appliedFilter.customAttributeName) {
        query['custom-attribute-name'] = appliedFilter.customAttributeName;
      }
      if (appliedFilter.customAttributeValue) {
        query['custom-attribute-value-comparator'] = appliedFilter.customAttributeValueComparator;
        query['custom-attribute-value'] = appliedFilter.customAttributeValue;
      }
      if (Number(appliedFilter.limit) !== defaultFilterLimit) {
        query.limit = appliedFilter.limit;
      }
      $location.search(query);
    }

    (function () {
      var options = {
        legend: {
          show: false
        },
        series: {
          points: {
            show: true
          }
        },
        grid: {
          hoverable: true,
          clickable: true,
          // min border margin should match aggregate chart so they are positioned the same from the top of page
          // without specifying min border margin, the point radius is used
          minBorderMargin: 0,
          borderColor: '#7d7358',
          borderWidth: 1
        },
        xaxis: {
          mode: 'time',
          timezone: 'browser',
          twelveHourClock: true,
          ticks: 5,
          // xaxis is in milliseconds, so grid lock to 1 second
          borderGridLock: 1000,
          min: appliedFilter.from,
          max: appliedFilter.to,
          reserveSpace: false
        },
        yaxis: {
          ticks: 10,
          zoomRange: false,
          borderGridLock: 0.001,
          min: 0,
          // 10 second yaxis max just for initial empty chart rendering
          max: 10,
          label: 'seconds'
        },
        zoom: {
          interactive: true,
          ctrlKey: true,
          amount: 2,
          skipDraw: true
        },
        colors: [
          $('#offscreenNormalColor').css('border-top-color'),
          $('#offscreenErrorColor').css('border-top-color'),
          $('#offscreenActiveColor').css('border-top-color')
        ],
        selection: {
          mode: 'xy'
        }
      };
      // render chart with no data points
      plot = $.plot($chart, [[]], options);
    })();

    plot.getAxes().yaxis.options.max = undefined;
    refreshChart();
  }
]);
