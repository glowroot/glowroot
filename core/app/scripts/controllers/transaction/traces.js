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
  'charts',
  'httpErrors',
  'traceModal',
  'queryStrings',
  'errorOnly',
  function ($scope, $location, $http, $q, charts, httpErrors, traceModal, queryStrings, errorOnly) {

    $scope.$parent.activeTabItem = 'traces';

    if ($scope.last) {
      // force the sidebar to update
      $scope.$parent.chartRefresh++;
    }

    var plot;

    var $chart = $('#chart');

    var appliedFilter;

    var defaultFilterLimit = 500;

    var highlightedTraceId;

    $scope.showChartSpinner = 0;
    $scope.showErrorFilter = errorOnly;

    $scope.filterLimitOptions = [
      {text: '100', value: 100},
      {text: '200', value: 200},
      {text: '500', value: 500},
      {text: '1,000', value: 1000},
      {text: '2,000', value: 2000},
      {text: '5,000', value: 5000}
    ];

    $scope.$watchGroup(['chartFrom', 'chartTo', 'chartRefresh'], function () {
      appliedFilter.from = $scope.traceChartFrom || $scope.chartFrom;
      appliedFilter.to = $scope.traceChartTo || $scope.chartTo;
      updateLocation();
      if ($scope.suppressChartRefresh) {
        $scope.suppressChartRefresh = false;
        return;
      }
      refreshChart();
    });

    function refreshChart(deferred) {
      var from = appliedFilter.from;
      var to = appliedFilter.to;
      var limit = appliedFilter.limit;
      if (appliedFilter.durationLow) {
        appliedFilter.durationLow = Number(appliedFilter.durationLow);
      }
      if (appliedFilter.durationHigh) {
        appliedFilter.durationHigh = Number(appliedFilter.durationHigh);
      }
      var durationLow = appliedFilter.durationLow;
      var durationHigh = appliedFilter.durationHigh;
      var query = angular.copy(appliedFilter);
      if ($scope.transactionName) {
        query.transactionNameComparator = 'equals';
        query.transactionName = $scope.transactionName;
      }
      // convert duration from milliseconds to nanoseconds
      query.durationLow = Math.ceil(query.durationLow * 1000000);
      if (query.durationHigh) {
        query.durationHigh = Math.floor(query.durationHigh * 1000000);
      }
      if (errorOnly) {
        query.errorOnly = true;
      }
      $scope.showChartSpinner++;
      $http.get('backend/trace/points' + queryStrings.encodeObject(query))
          .success(function (data) {
            function tryHighlight(dataPoints, dataSeries) {
              var i;
              for (i = 0; i < dataPoints.length; i++) {
                var activePoint = dataPoints[i];
                if (activePoint[2] === highlightedTraceId) {
                  plot.highlight(dataSeries, activePoint.slice(0, 2));
                  return true;
                }
              }
              return false;
            }

            function highlight() {
              if (tryHighlight(data.normalPoints, plot.getData()[0])) {
                return;
              }
              if (tryHighlight(data.errorPoints, plot.getData()[1])) {
                return;
              }
              tryHighlight(data.activePoints, plot.getData()[2]);
            }

            $scope.showChartSpinner--;
            if ($scope.showChartSpinner) {
              return;
            }
            var traceCount = data.normalPoints.length + data.errorPoints.length + data.activePoints.length;
            $scope.chartNoData = traceCount === 0;
            $scope.showExpiredMessage = data.expired;
            $scope.chartLimitExceeded = data.limitExceeded;
            $scope.chartLimit = limit;
            // user clicked on Refresh button, need to reset axes
            plot.getAxes().xaxis.options.min = from;
            plot.getAxes().xaxis.options.max = to;
            plot.getAxes().yaxis.options.min = durationLow;
            plot.getAxes().yaxis.options.realMax = durationHigh;
            plot.setData([data.normalPoints, data.errorPoints, data.activePoints]);
            // setupGrid is needed in case yaxis.max === undefined
            if (highlightedTraceId) {
              plot.unhighlight();
            }
            plot.setupGrid();
            plot.draw();
            if (highlightedTraceId) {
              highlight();
            }
            broadcastTraceTabCount();
            if (deferred) {
              deferred.resolve('Success');
            }
          })
          .error(function (data, status) {
            $scope.showChartSpinner--;
            httpErrors.handler($scope, deferred)(data, status);
          });
    }

    function broadcastTraceTabCount() {
      var data = plot.getData();
      var traceCount = data[0].data.length + data[1].data.length + data[2].data.length;
      // parent scope can be null if user has moved on to another controller by the time http get returns
      if ($scope.$parent) {
        if ($scope.chartLimitExceeded) {
          $scope.$parent.$broadcast('updateTraceTabCount', undefined);
        } else {
          $scope.$parent.$broadcast('updateTraceTabCount', traceCount);
        }
      }
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

    function postUpdateRange(range) {
      if (range.to - range.from >= 300000) {
        // lock to overall chartFrom/chartTo (just updated above in updateRange) if range > 5 minutes
        range.from = $scope.chartFrom;
        range.to = $scope.chartTo;
        delete $scope.traceChartFrom;
        delete $scope.traceChartTo;
      } else {
        // use chartFrom/chartTo (just updated above in updateRange) as bounds for from/to:
        range.from = Math.max(range.from, $scope.chartFrom);
        range.to = Math.min(range.to, $scope.chartTo);
        $scope.traceChartFrom = range.from;
        $scope.traceChartTo = range.to;
        // last doesn't make sense with trace-chart-from/trace-chart-to (what to do on browser refresh at later time?)
        delete $scope.$parent.last;
      }
    }

    $chart.bind('plotzoom', function (event, plot, args) {
      var zoomingOut = args.amount && args.amount < 1;
      $scope.$apply(function () {
        var range = {
          from: plot.getAxes().xaxis.options.min,
          to: plot.getAxes().xaxis.options.max
        };
        charts.updateRange($scope.$parent, range.from, range.to, zoomingOut);
        postUpdateRange(range);
        if (zoomingOut) {
          // scroll zooming out, reset duration limits
          updateFilter(range.from, range.to, 0, undefined);
        } else {
          // only update from/to
          appliedFilter.from = range.from;
          appliedFilter.to = range.to;
        }
        if (zoomingOut || $scope.chartLimitExceeded) {
          updateLocation();
        } else {
          // no need to fetch new data
          $scope.suppressChartRefresh = true;
          var data = getFilteredData();
          plot.setData(data);
          plot.setupGrid();
          plot.draw();
          broadcastTraceTabCount();
          updateLocation();
        }
      });
    });

    $chart.bind('plotselected', function (event, ranges) {
      $scope.$apply(function () {
        plot.clearSelection();
        var range = {
          from: ranges.xaxis.from,
          to: ranges.xaxis.to
        };
        charts.updateRange($scope.$parent, range.from, range.to, false, true, true);
        postUpdateRange(range);
        updateFilter(range.from, range.to, ranges.yaxis.from, ranges.yaxis.to);
        if ($scope.chartLimitExceeded) {
          updateLocation();
        } else {
          // no need to fetch new data
          $scope.suppressChartRefresh = true;
          plot.getAxes().xaxis.options.min = range.from;
          plot.getAxes().xaxis.options.max = range.to;
          plot.getAxes().yaxis.options.min = ranges.yaxis.from;
          plot.getAxes().yaxis.options.realMax = ranges.yaxis.to;
          plot.setData(getFilteredData());
          // setupGrid needs to be after setData
          plot.setupGrid();
          plot.draw();
          broadcastTraceTabCount();
          updateLocation();
        }
      });
    });

    $scope.zoomOut = function () {
      var currMin = $scope.chartFrom;
      var currMax = $scope.chartTo;
      var currRange = currMax - currMin;
      var range = {
        from: currMin - currRange / 2,
        to: currMax + currRange / 2
      };
      charts.updateRange($scope.$parent, range.from, range.to, true);
      postUpdateRange(range);
      if ($scope.traceChartFrom && $scope.traceChartTo) {
        // use updated $scope.chartFrom/To as bounds for from/to:
        $scope.traceChartFrom = Math.max(range.from, $scope.chartFrom);
        $scope.traceChartTo = Math.min(range.to, $scope.chartTo);
      }
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

    $chart.bind('plotclick', function (event, pos, item, originalEvent) {
      if (item) {
        plot.unhighlight();
        plot.highlight(item.series, item.datapoint);
        var traceId = plot.getData()[item.seriesIndex].data[item.dataIndex][2];
        if (originalEvent.ctrlKey) {
          var url = $location.url();
          if (url.indexOf('?') === -1) {
            url += '?';
          } else {
            url += '&';
          }
          url += 'modal-trace-id=' + traceId;
          window.open(url);
        } else {
          $scope.$apply(function () {
            $location.search('modal-trace-id', traceId);
          });
        }
        highlightedTraceId = item.seriesIndex === 2 ? traceId : null;
      }
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

    function onLocationChangeSuccess() {
      var priorAppliedFilter = appliedFilter;
      appliedFilter = {};
      appliedFilter.transactionType = $scope.transactionType;
      $scope.traceChartFrom = Number($location.search()['trace-chart-from']);
      $scope.traceChartTo = Number($location.search()['trace-chart-to']);
      appliedFilter.from = $scope.traceChartFrom || $scope.chartFrom;
      appliedFilter.to = $scope.traceChartTo || $scope.chartTo;
      appliedFilter.durationLow = Number($location.search()['duration-low']) || 0;
      appliedFilter.durationHigh = Number($location.search()['duration-high']) || undefined;
      appliedFilter.headlineComparator = $location.search()['headline-comparator'] || 'begins';
      appliedFilter.headline = $location.search().headline || '';
      appliedFilter.errorComparator = $location.search()['error-comparator'] || 'begins';
      appliedFilter.error = $location.search().error || '';
      appliedFilter.userComparator = $location.search()['user-comparator'] || 'begins';
      appliedFilter.user = $location.search().user || '';
      appliedFilter.customAttributeName = $location.search()['custom-attribute-name'] || '';
      appliedFilter.customAttributeValueComparator =
          $location.search()['custom-attribute-value-comparator'] || 'begins';
      appliedFilter.customAttributeValue = $location.search()['custom-attribute-value'] || '';
      appliedFilter.limit = Number($location.search().limit) || defaultFilterLimit;

      if (priorAppliedFilter !== undefined && !angular.equals(appliedFilter, priorAppliedFilter)) {
        // e.g. back or forward button was used to navigate
        $scope.$parent.chartRefresh++;
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

      var modalTraceId = $location.search()['modal-trace-id'];
      if (modalTraceId) {
        highlightedTraceId = modalTraceId;
        $('#traceModal').data('location-query', 'modal-trace-id');
        traceModal.displayModal(modalTraceId);
      } else {
        $('#traceModal').modal('hide');
      }
    }

    $scope.$on('$locationChangeSuccess', onLocationChangeSuccess);
    onLocationChangeSuccess();

    $scope.$watch('filterDurationComparator', function (value) {
      if (value === 'greater') {
        $scope.filter.durationHigh = undefined;
      } else if (value === 'less') {
        $scope.filter.durationLow = 0;
      }
    });

    function updateLocation() {
      var query = $scope.buildQueryObject({});
      if ($scope.traceChartFrom) {
        query['trace-chart-from'] = $scope.traceChartFrom;
      }
      if ($scope.traceChartTo) {
        query['trace-chart-to'] = $scope.traceChartTo;
      }
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
      // preserve modal-trace-id, otherwise refresh on modal trace does not work
      query['modal-trace-id'] = $location.search()['modal-trace-id'];
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
          borderGridLock: 1,
          min: 0,
          // 10 second yaxis max just for initial empty chart rendering
          max: 10,
          label: 'milliseconds'
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
    charts.initResize(plot, $scope);
  }
]);
