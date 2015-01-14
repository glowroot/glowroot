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
  'httpErrors',
  'traceModal',
  'queryStrings',
  'errorOnly',
  function ($scope, $location, $http, $q, $timeout, httpErrors, traceModal, queryStrings, errorOnly) {

    $scope.$parent.activeTabItem = 'traces';

    var plot;

    var currentRefreshId = 0;
    var currentZoomId = 0;

    var $chart = $('#chart');

    var appliedFilter;

    var filterFromToDefault;
    var filterLimitDefault;

    var fixedAggregateIntervalMillis = 1000 * $scope.layout.fixedAggregateIntervalSeconds;

    $scope.$watchGroup(['containerWidth', 'windowHeight'], function () {
      plot.resize();
      plot.setupGrid();
      plot.draw();
    });

    $scope.showChartSpinner = 0;
    $scope.showErrorFilter = errorOnly;

    $scope.$watch('filterDate', function (newValue, oldValue) {
      if (newValue !== oldValue) {
        // date filter in transaction-header was changed
        appliedFilter.from = $scope.chartFrom;
        appliedFilter.to = $scope.chartTo;
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
      var refreshId = ++currentRefreshId;
      var query = angular.copy(appliedFilter);
      // convert duration from seconds to nanoseconds
      query.durationLow = Math.ceil(query.durationLow * 1000000000);
      if (query.durationHigh) {
        query.durationHigh = Math.floor(query.durationHigh * 1000000000);
      }
      if (errorOnly) {
        query.errorOnly = true;
      }
      if ($scope.transactionName) {
        query.transactionName = $scope.transactionName;
        query.transactionNameComparator = 'equals';
      }
      $scope.showChartSpinner++;
      $http.get('backend/trace/points' + queryStrings.encodeObject(query))
          .success(function (data) {
            $scope.showChartSpinner--;
            if (refreshId !== currentRefreshId) {
              return;
            }
            $scope.chartNoData = !data.normalPoints.length && !data.errorPoints.length && !data.activePoints.length;
            $scope.chartLimitExceeded = data.limitExceeded;
            $scope.chartLimit = limit;
            // user clicked on Refresh button, need to reset axes
            plot.getAxes().xaxis.options.min = from;
            plot.getAxes().xaxis.options.max = to;
            plot.getAxes().yaxis.options.min = durationLow;
            plot.getAxes().yaxis.options.realMax = durationHigh;
            var midnight = new Date(from).setHours(0, 0, 0, 0);
            plot.getAxes().xaxis.options.zoomRange = [
              midnight,
              midnight + 24 * 60 * 60 * 1000
            ];
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

    $scope.clickRefreshButton = function (deferred) {
      var midnight = new Date(appliedFilter.from).setHours(0, 0, 0, 0);
      if (midnight !== $scope.filterDate.getTime()) {
        // filterDate has changed
        filterFromToDefault = false;
        appliedFilter.from = $scope.filterDate.getTime() + (appliedFilter.from - midnight);
        appliedFilter.to = $scope.filterDate.getTime() + (appliedFilter.to - midnight);
      }
      angular.extend(appliedFilter, $scope.filter);
      updateLocation();
      refreshChart(deferred);
    };

    $chart.bind('plotzoom', function (event, plot, args) {
      $scope.$apply(function () {
        // throw up spinner right away
        $scope.showChartSpinner++;
        var zoomingOut = args.amount && args.amount < 1;
        if (zoomingOut) {
          plot.getAxes().yaxis.options.min = 0;
          plot.getAxes().yaxis.options.realMax = undefined;
        }
        if (zoomingOut || $scope.chartLimitExceeded) {
          // need to call setupGrid on each zoom to handle rapid zooming
          plot.setupGrid();
          var zoomId = ++currentZoomId;
          // use 100 millisecond delay to handle rapid zooming
          $timeout(function () {
            if (zoomId === currentZoomId) {
              afterZoom(zoomingOut);
              filterFromToDefault = false;
              updateLocation();
              refreshChart();
            }
            $scope.showChartSpinner--;
          }, 100);
        } else {
          // no need to fetch new data
          plot.setData(getFilteredData());
          plot.setupGrid();
          plot.draw();
          afterZoom(zoomingOut);
          filterFromToDefault = false;
          updateLocation();
          // increment currentRefreshId to cancel any refresh in action
          currentRefreshId++;
          $scope.showChartSpinner--;
        }
      });
    });

    $chart.bind('plotselected', function (event, ranges) {
      $scope.$apply(function () {
        filterFromToDefault = false;
        plot.clearSelection();
        // perform the zoom
        plot.getAxes().xaxis.options.min = ranges.xaxis.from;
        plot.getAxes().xaxis.options.max = ranges.xaxis.to;
        plot.getAxes().yaxis.options.min = ranges.yaxis.from;
        plot.getAxes().yaxis.options.realMax = ranges.yaxis.to;
        if ($scope.chartLimitExceeded) {
          refreshChart();
          afterZoom();
          filterFromToDefault = false;
          updateLocation();
        } else {
          plot.setData(getFilteredData());
          plot.setupGrid();
          plot.draw();
          afterZoom();
          filterFromToDefault = false;
          updateLocation();
          // no need to fetch new data
          // increment currentRefreshId to cancel any refresh in action
          currentRefreshId++;
        }
      });
    });

    $scope.zoomOut = function () {
      // need to execute this outside of $apply since code assumes it needs to do its own $apply
      $timeout(function () {
        plot.zoomOut();
      });
    };

    function afterZoom(zoomingOut) {
      // update filter
      var from = plot.getAxes().xaxis.min;
      var to = plot.getAxes().xaxis.max;
      var durationLow = plot.getAxes().yaxis.min;
      var durationHigh = plot.getAxes().yaxis.options.realMax;
      appliedFilter.from = from;
      appliedFilter.to = to;
      $scope.$parent.chartFrom = fixedAggregateIntervalMillis * Math.floor(from / fixedAggregateIntervalMillis);
      $scope.$parent.chartTo = fixedAggregateIntervalMillis * Math.ceil(to / fixedAggregateIntervalMillis);
      $scope.$parent.chartFromToDefault = false;
      if (zoomingOut) {
        // scroll zooming out, reset duration limits
        $scope.filterDurationComparator = 'greater';
        // set both appliedFilter and $scope.filter durationLow/durationHigh
        appliedFilter.durationLow = $scope.filter.durationLow = 0;
        appliedFilter.durationHigh = $scope.filter.durationHigh = undefined;
      } else {
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
    appliedFilter.transactionNameComparator = $location.search()['transaction-name-comparator'] || 'begins';
    appliedFilter.transactionName = $location.search()['transaction-name'] || '';
    appliedFilter.headlineComparator = $location.search()['headline-comparator'] || 'begins';
    appliedFilter.headline = $location.search().headline || '';
    appliedFilter.errorComparator = $location.search()['error-comparator'] || 'begins';
    appliedFilter.error = $location.search().error || '';
    appliedFilter.userComparator = $location.search()['user-comparator'] || 'begins';
    appliedFilter.user = $location.search().user || '';
    appliedFilter.customAttributeName = $location.search()['custom-attribute-name'] || '';
    appliedFilter.customAttributeValueComparator = $location.search()['custom-attribute-value-comparator'] || 'begins';
    appliedFilter.customAttributeValue = $location.search()['custom-attribute-value'] || '';
    appliedFilter.limit = Number($location.search().limit);
    if (!appliedFilter.limit) {
      filterLimitDefault = true;
      appliedFilter.limit = 500;
    }

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

    $scope.$watch('filter.limit', function (newValue, oldValue) {
      if (newValue !== oldValue) {
        filterLimitDefault = false;
      }
    });

    function updateLocation() {
      var query = $scope.buildQueryObject();
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
      if (appliedFilter.transactionName) {
        query['transaction-name-comparator'] = appliedFilter.transactionNameComparator;
        query['transaction-name'] = appliedFilter.transactionName;
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
      if (!filterLimitDefault) {
        query.limit = appliedFilter.limit;
      }
      $location.search(query).replace();
    }

    (function () {
      var fromMidnight = new Date(appliedFilter.from).setHours(0, 0, 0, 0);
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
          absoluteZoomRange: true,
          zoomRange: [
            fromMidnight,
            fromMidnight + 24 * 60 * 60 * 1000
          ],
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
      plot = $.plot($chart, [
        []
      ], options);
    })();

    plot.getAxes().yaxis.options.max = undefined;
    refreshChart();
  }
]);
