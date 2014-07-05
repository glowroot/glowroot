/*
 * Copyright 2012-2014 the original author or authors.
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

/* global glowroot, angular, Glowroot, $ */

glowroot.controller('TracesCtrl', [
  '$scope',
  '$location',
  '$http',
  '$q',
  'traceModal',
  'queryStrings',
  function ($scope, $location, $http, $q, traceModal, queryStrings) {
    // \u00b7 is &middot;
    document.title = 'Traces \u00b7 Glowroot';
    $scope.$parent.title = 'Traces';
    $scope.$parent.activeNavbarItem = 'traces';

    var plot;

    var currentRefreshId = 0;
    var currentZoomId = 0;

    var $chart = $('#chart');

    var appliedFilter;

    $scope.$watchCollection('[containerWidth, windowHeight]', function () {
      plot.resize();
      plot.setupGrid();
      plot.draw();
    });

    function refreshChart(deferred) {
      var from = appliedFilter.from;
      var to = appliedFilter.to;
      var limit = appliedFilter.limit;
      var durationLow = appliedFilter.durationLow;
      var durationHigh = appliedFilter.durationHigh;
      var refreshId = ++currentRefreshId;
      var spinner = Glowroot.showSpinner('#chartSpinner');
      var query = angular.copy(appliedFilter);
      // convert duration from seconds to nanoseconds
      query.durationLow = Math.ceil(query.durationLow * 1000000000);
      if (query.durationHigh) {
        query.durationHigh = Math.floor(query.durationHigh * 1000000000);
      }
      $http.get('backend/trace/points?' + queryStrings.encodeObject(query))
          .success(function (data) {
            if (refreshId !== currentRefreshId) {
              return;
            }
            spinner.stop();
            $scope.refreshChartError = false;
            $scope.chartLimitExceeded = data.limitExceeded;
            $scope.chartLimit = limit;
            if (deferred) {
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
              plot.unhighlight();
            }
            plot.setData([data.normalPoints, data.errorPoints, data.activePoints]);
            // setupGrid is needed in case yaxis.max === undefined
            plot.setupGrid();
            plot.draw();
            if (deferred) {
              deferred.resolve('Success');
            }
          })
          .error(function (data, status) {
            if (refreshId !== currentRefreshId) {
              return;
            }
            spinner.stop();
            $scope.chartLimitExceeded = false;
            if (status === 0) {
              $scope.refreshChartError = 'Unable to connect to server';
            } else {
              $scope.refreshChartError = 'An error occurred';
            }
            if (deferred) {
              deferred.reject($scope.refreshChartError);
            }
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
      var zoomingOut = args.amount && args.amount < 1;
      if (zoomingOut) {
        plot.getAxes().yaxis.options.min = 0;
        plot.getAxes().yaxis.options.realMax = undefined;
      }
      plot.setData(getFilteredData());
      plot.setupGrid();
      plot.draw();
      $scope.$apply(function () {
        afterZoom(zoomingOut);
        filterFromToDefault = false;
        updateLocation();
      });
      if (zoomingOut || $scope.chartLimitExceeded) {
        var zoomId = ++currentZoomId;
        // use 100 millisecond delay to handle rapid zooming
        setTimeout(function () {
          if (zoomId !== currentZoomId) {
            return;
          }
          $scope.$apply(function () {
            refreshChart(undefined);
          });
        }, 100);
      } else {
        // no need to fetch new data
        // increment currentRefreshId to cancel any refresh in action
        currentRefreshId++;
      }
    });

    $chart.bind('plotselected', function (event, ranges) {
      filterFromToDefault = false;
      plot.clearSelection();
      // perform the zoom
      plot.getAxes().xaxis.options.min = ranges.xaxis.from;
      plot.getAxes().xaxis.options.max = ranges.xaxis.to;
      plot.getAxes().yaxis.options.min = ranges.yaxis.from;
      plot.getAxes().yaxis.options.realMax = ranges.yaxis.to;
      plot.setData(getFilteredData());
      plot.setupGrid();
      plot.draw();
      $scope.$apply(function () {
        afterZoom();
        filterFromToDefault = false;
        updateLocation();
      });
      if ($scope.chartLimitExceeded) {
        $scope.$apply(function () {
          refreshChart();
        });
      } else {
        // no need to fetch new data
        // increment currentRefreshId to cancel any refresh in action
        currentRefreshId++;
      }
    });

    function afterZoom(zoomingOut) {
      // update filter
      var from = plot.getAxes().xaxis.min;
      var to = plot.getAxes().xaxis.max;
      var durationLow = plot.getAxes().yaxis.min;
      var durationHigh = plot.getAxes().yaxis.options.realMax;
      appliedFilter.from = from;
      appliedFilter.to = to;
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
      for (i = 0; i < plot.getData().length; i++) {
        data.push([]);
        var points = plot.getData()[i].data;
        for (j = 0; j < points.length; j++) {
          var point = points[j];
          if (point[0] >= from && point[0] <= to && point[1] >= durationLow && point[1] <= durationHigh) {
            data[i].push(point);
          }
        }
      }
      return data;
    }

    $chart.bind('plotclick', function (event, pos, item) {
      if (item) {
        plot.unhighlight();
        // TODO highlight with bolder or larger outline
        plot.highlight(item.series, item.datapoint);
        showTrace(item);
      }
    });

    function showTrace(item) {
      var x = item.pageX;
      var y = item.pageY;
      var traceId = plot.getData()[item.seriesIndex].data[item.dataIndex][2];
      var modalVanishPoint = [x, y];
      $scope.$apply(function () {
        traceModal.displayModal(traceId, modalVanishPoint);
      });
    }

    function hideTooltip() {
      $chart.qtip('hide');
    }

    $('body').mousedown(function (e) {
      if ($(e.target).parents('.qtip').length === 0) {
        // click occurred outside of qtip
        hideTooltip();
      }
    });

    // TODO CONVERT TO ANGULARJS, global $http error handler?
    Glowroot.configureAjaxError();

    $('#zoomOut').click(function () {
      plot.zoomOut();
    });
    $('#modalHide').click(function () {
      traceModal.hideModal();
    });

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

    var filterFromToDefault;
    var filterLimitDefault;

    appliedFilter = {};
    appliedFilter.from = Number($location.search().from);
    appliedFilter.to = Number($location.search().to);
    // both from and to must be supplied or neither will take effect
    if (appliedFilter.from && appliedFilter.to) {
      $scope.filterDate = new Date(appliedFilter.from);
      $scope.filterDate.setHours(0, 0, 0, 0);
    } else {
      filterFromToDefault = true;
      var today = new Date();
      today.setHours(0, 0, 0, 0);
      $scope.filterDate = today;
      // show 2 hour interval, but nothing prior to today (e.g. if 'now' is 1am) or after today
      // (e.g. if 'now' is 11:55pm)
      var now = new Date();
      now.setSeconds(0, 0);
      appliedFilter.from = Math.max(now.getTime() - 105 * 60 * 1000, today.getTime());
      appliedFilter.to = Math.min(appliedFilter.from + 120 * 60 * 1000, today.getTime() + 24 * 60 * 60 * 1000);
    }
    appliedFilter.durationLow = Number($location.search().durationLow) || 0;
    appliedFilter.durationHigh = Number($location.search().durationHigh) || undefined;
    appliedFilter.transactionType = $location.search()['transaction-type'] || '';
    appliedFilter.transactionNameComparator = $location.search()['transaction-name-comparator'] || 'begins';
    appliedFilter.transactionName = $location.search()['transaction-name'] || '';
    appliedFilter.headlineComparator = $location.search()['headline-comparator'] || 'begins';
    appliedFilter.headline = $location.search().headline || '';
    appliedFilter.errorComparator = $location.search()['error-comparator'] || 'begins';
    appliedFilter.error = $location.search().error || '';
    appliedFilter.userComparator = $location.search()['user-comparator'] || 'begins';
    appliedFilter.user = $location.search().user || '';
    appliedFilter.attributeName = $location.search()['attribute-name'] || '';
    appliedFilter.attributeValueComparator = $location.search()['attribute-value-comparator'] || 'begins';
    appliedFilter.attributeValue = $location.search()['attribute-value'] || '';
    appliedFilter.limit = Number($location.search().limit);
    if (!appliedFilter.limit) {
      filterLimitDefault = true;
      appliedFilter.limit = 500;
    }
    appliedFilter.errorOnly = $location.search()['error-only'] === 'true';
    appliedFilter.fineOnly = $location.search()['fine-only'] === 'true';

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
      var query = {};
      if (!filterFromToDefault) {
        query.from = appliedFilter.from;
        query.to = appliedFilter.to;
      }
      if (Number(appliedFilter.durationLow)) {
        query['duration-low'] = appliedFilter.durationLow;
      }
      if (Number(appliedFilter.durationHigh)) {
        query['duration-high'] = appliedFilter.durationHigh;
      }
      if (appliedFilter.transactionType) {
        query['transaction-type'] = appliedFilter.transactionType;
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
      if (appliedFilter.attributeName) {
        query['attribute-name'] = appliedFilter.attributeName;
      }
      if (appliedFilter.attributeValue) {
        query['attribute-value-comparator'] = appliedFilter.attributeValueComparator;
        query['attribute-value'] = appliedFilter.attributeValue;
      }
      if (appliedFilter.errorOnly) {
        query['error-only'] = 'true';
      }
      if (appliedFilter.fineOnly) {
        query['fine-only'] = 'true';
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
          minBorderMargin: 10,
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
          ]
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
      plot = $.plot($chart, [], options);
    })();

    plot.getAxes().yaxis.options.max = undefined;
    refreshChart();
  }
]);
