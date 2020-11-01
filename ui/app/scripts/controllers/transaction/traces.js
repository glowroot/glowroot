/*
 * Copyright 2012-2019 the original author or authors.
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

glowroot.controller('TracesCtrl', [
  '$scope',
  '$location',
  '$http',
  '$q',
  'locationChanges',
  'charts',
  'httpErrors',
  'traceModal',
  'queryStrings',
  'traceKind',
  function ($scope, $location, $http, $q, locationChanges, charts, httpErrors, traceModal, queryStrings, traceKind) {

    $scope.$parent.activeTabItem = 'traces';

    if ($scope.hideMainContent()) {
      return;
    }

    var plot;

    var $chart = $('#chart');

    var appliedFilter;

    var defaultFilterLimit = 500;

    var highlightedTraceId;

    // these are needed for handling opening a direct link to a modal trace
    var firstLocation = true;
    var firstLocationModalAgentId;
    var firstLocationModalTraceId;
    var firstLocationModalCheckLiveTraces;

    $scope.showChartSpinner = 0;
    $scope.showErrorMessageFilter = traceKind === 'error';

    $scope.filterLimitOptions = [
      {text: '100', value: 100},
      {text: '200', value: 200},
      {text: '500', value: 500},
      {text: '1,000', value: 1000},
      {text: '2,000', value: 2000},
      {text: '5,000', value: 5000}
    ];

    // using $watch instead of $watchGroup because $watchGroup has confusing behavior regarding oldValues
    // (see https://github.com/angular/angular.js/pull/12643)
    $scope.$watch('[range.chartFrom, range.chartTo, range.chartRefresh, range.chartAutoRefresh]',
        function (newValues, oldValues) {
          if ($scope.formCtrl.$invalid) {
            // TODO display message to user
            return;
          }
          appliedFilter.from = $scope.range.chartFrom;
          appliedFilter.to = $scope.range.chartTo;
          updateLocation();
          if ($scope.suppressChartRefresh) {
            $scope.suppressChartRefresh = false;
            return;
          }
          refreshChart(newValues[3] !== oldValues[3]);
        });

    function refreshChart(autoRefresh) {
      if ((!$scope.agentRollupId && $scope.layout.central) || !$scope.transactionType) {
        return;
      }
      var from = appliedFilter.from;
      var to = appliedFilter.to;
      var limit = appliedFilter.limit;
      if (appliedFilter.durationMillisLow) {
        appliedFilter.durationMillisLow = Number(appliedFilter.durationMillisLow);
      } else if (appliedFilter.durationMillisLow === '') {
        appliedFilter.durationMillisLow = 0;
      }
      if (appliedFilter.durationMillisHigh) {
        appliedFilter.durationMillisHigh = Number(appliedFilter.durationMillisHigh);
      } else if (appliedFilter.durationMillisHigh === '') {
        appliedFilter.durationMillisHigh = undefined;
      }
      var durationMillisLow = appliedFilter.durationMillisLow;
      var durationMillisHigh = appliedFilter.durationMillisHigh;
      var query = angular.copy(appliedFilter);
      query.agentRollupId = $scope.agentRollupId;
      if ($scope.transactionName) {
        query.transactionName = $scope.transactionName;
      }
      if (autoRefresh) {
        query.autoRefresh = true;
      }
      var showChartSpinner = !$scope.suppressChartSpinner;
      if (showChartSpinner) {
        $scope.showChartSpinner++;
      }
      $scope.suppressChartSpinner = false;

      $http.get('backend/' + traceKind + '/points' + queryStrings.encodeObject(query))
          .then(function (response) {
            var data = response.data;

            function tryHighlight(dataPoints, dataSeries) {
              var i;
              for (i = 0; i < dataPoints.length; i++) {
                var activePoint = dataPoints[i];
                if (activePoint[3] === highlightedTraceId) {
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
              tryHighlight(data.partialPoints, plot.getData()[2]);
            }

            // clear http error, especially useful for auto refresh on live data to clear a sporadic error from earlier
            httpErrors.clear();
            if (showChartSpinner) {
              $scope.showChartSpinner--;
            }
            if ($scope.showChartSpinner) {
              // ignore this response, another response has been stacked
              return;
            }
            var traceCount = data.normalPoints.length + data.errorPoints.length + data.partialPoints.length;
            $scope.chartNoData = traceCount === 0;
            $scope.showExpiredMessage = data.expired;
            $scope.chartLimitExceeded = data.limitExceeded;
            $scope.chartLimit = limit;
            // user clicked on Refresh button, need to reset axes
            plot.getAxes().xaxis.options.min = from;
            plot.getAxes().xaxis.options.max = to;
            plot.getAxes().yaxis.options.min = durationMillisLow;
            plot.getAxes().yaxis.options.max = durationMillisHigh;
            plot.setData([data.normalPoints, data.errorPoints, data.partialPoints]);
            // setupGrid is needed in case yaxis.max === undefined
            if (highlightedTraceId) {
              plot.unhighlight();
            }
            plot.setupGrid();
            plot.draw();
            if (highlightedTraceId) {
              highlight();
            }
          }, function (response) {
            if (showChartSpinner) {
              $scope.showChartSpinner--;
            }
            httpErrors.handle(response);
          });
    }

    $scope.refresh = function (deferred) {
      if (deferred) {
        deferred.resolve();
      }
      charts.applyLast($scope);
      angular.extend(appliedFilter, $scope.filter);
      $scope.range.chartRefresh++;
    };

    $scope.clearCriteria = function () {
      $scope.filter.durationMillisLow = 0;
      $scope.filter.durationMillisHigh = undefined;
      $scope.filterDurationComparator = 'greater';
      $scope.filter.headlineComparator = 'begins';
      $scope.filter.headline = '';
      $scope.filter.errorMessageComparator = 'begins';
      $scope.filter.errorMessage = '';
      $scope.filter.userComparator = 'begins';
      $scope.filter.user = '';
      $scope.filter.attributeName = '';
      $scope.filter.attributeValueComparator = 'begins';
      $scope.filter.attributeValue = '';
      $scope.filter.limit = defaultFilterLimit;
      $scope.refresh();
    };

    $chart.bind('plotzoom', function (event, plot, args) {
      var zoomingOut = args.amount && args.amount < 1;
      $scope.$apply(function () {
        var from = plot.getAxes().xaxis.options.min;
        var to = plot.getAxes().xaxis.options.max;
        charts.updateRange($scope, from, to, zoomingOut, false, false, true);
        if (zoomingOut) {
          // scroll zooming out, reset duration limits
          updateFilter($scope.range.chartFrom, $scope.range.chartTo, 0, undefined);
        } else {
          // only update from/to
          appliedFilter.from = $scope.range.chartFrom;
          appliedFilter.to = $scope.range.chartTo;
        }
        if (zoomingOut || $scope.chartLimitExceeded || $scope.range.chartTo >= Date.now()) {
          // adjust interval and redraw chart, this is important for rapid scroll zooming
          plot.getAxes().xaxis.options.min = $scope.range.chartFrom;
          plot.getAxes().xaxis.options.max = $scope.range.chartTo;
          plot.setupGrid();
          plot.draw();
          updateLocation();
        } else {
          // no need to fetch new data
          $scope.suppressChartRefresh = true;
          // adjust interval
          plot.getAxes().xaxis.options.min = $scope.range.chartFrom;
          plot.getAxes().xaxis.options.max = $scope.range.chartTo;
          var data = getFilteredData();
          plot.setData(data);
          plot.setupGrid();
          plot.draw();
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
        charts.updateRange($scope, range.from, range.to, false, true, true, true);
        updateFilter(range.from, range.to, ranges.yaxis.from, ranges.yaxis.to);
        if ($scope.chartLimitExceeded) {
          updateLocation();
        } else {
          // no need to fetch new data
          $scope.suppressChartRefresh = true;
          plot.getAxes().xaxis.options.min = range.from;
          plot.getAxes().xaxis.options.max = range.to;
          plot.getAxes().yaxis.options.min = ranges.yaxis.from;
          plot.getAxes().yaxis.options.max = ranges.yaxis.to;
          plot.setData(getFilteredData());
          // setupGrid needs to be after setData
          plot.setupGrid();
          plot.draw();
          updateLocation();
        }
      });
    });

    $scope.zoomOut = function () {
      var currMin = $scope.range.chartFrom;
      var currMax = $scope.range.chartTo;
      var currRange = currMax - currMin;
      var range = {
        from: currMin - currRange / 2,
        to: currMax + currRange / 2
      };
      charts.updateRange($scope, range.from, range.to, true, false, false, true);
      // scroll zooming out, reset duration limits
      updateFilter($scope.range.chartFrom, $scope.range.chartTo, 0, undefined);
    };

    function updateFilter(from, to, durationMillisLow, durationMillisHigh) {
      appliedFilter.from = from;
      appliedFilter.to = to;
      // set both appliedFilter and $scope.filter durationMillisLow/durationMillisHigh
      appliedFilter.durationMillisLow = $scope.filter.durationMillisLow = durationMillisLow;
      if (durationMillisHigh !== plot.getAxes().yaxis.max) {
        appliedFilter.durationMillisHigh = $scope.filter.durationMillisHigh = durationMillisHigh;
      }
      if (durationMillisHigh && durationMillisLow !== 0) {
        $scope.filterDurationComparator = 'between';
      } else if (durationMillisHigh) {
        $scope.filterDurationComparator = 'less';
      } else {
        $scope.filterDurationComparator = 'greater';
      }
    }

    function getFilteredData() {
      var from = plot.getAxes().xaxis.options.min;
      var to = plot.getAxes().xaxis.options.max;
      var durationMillisLow = plot.getAxes().yaxis.options.min;
      var durationMillisHigh = plot.getAxes().yaxis.options.max || Number.MAX_VALUE;
      var data = [];
      var i, j;
      var nodata = true;
      for (i = 0; i < plot.getData().length; i++) {
        data.push([]);
        var points = plot.getData()[i].data;
        for (j = 0; j < points.length; j++) {
          var point = points[j];
          if (point[0] >= from && point[0] <= to && point[1] >= durationMillisLow && point[1] <= durationMillisHigh) {
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
        var point = plot.getData()[item.seriesIndex].data[item.dataIndex];
        var agentId = point[2];
        var traceId = point[3];
        var checkLiveTraces = point[4];
        if (originalEvent.ctrlKey) {
          var url = $location.url();
          if (url.indexOf('?') === -1) {
            url += '?';
          } else {
            url += '&';
          }
          if (agentId) {
            url += 'modal-agent-id=' + encodeURIComponent(agentId) + '&';
          }
          url += 'modal-trace-id=' + traceId;
          if (checkLiveTraces) {
            url += '&modal-check-live-traces=true';
          }
          window.open(url);
        } else {
          $scope.$apply(function () {
            if (agentId) {
              $location.search('modal-agent-id', agentId);
            }
            $location.search('modal-trace-id', traceId);
            if (checkLiveTraces) {
              $location.search('modal-check-live-traces', 'true');
            }
          });
        }
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
        value: 'not-contains'
      }
    ];

    locationChanges.on($scope, function () {

      $scope.traceAttributeNames = $scope.agentRollup.traceAttributeNames[$scope.transactionType];

      var priorAppliedFilter = appliedFilter;
      appliedFilter = {};
      appliedFilter.transactionType = $scope.transactionType;
      appliedFilter.from = $scope.range.chartFrom;
      appliedFilter.to = $scope.range.chartTo;
      appliedFilter.durationMillisLow = Number($location.search()['duration-millis-low']) || 0;
      appliedFilter.durationMillisHigh = Number($location.search()['duration-millis-high']) || undefined;
      appliedFilter.headlineComparator = $location.search()['headline-comparator'] || 'begins';
      appliedFilter.headline = $location.search().headline || '';
      appliedFilter.errorMessageComparator = $location.search()['error-message-comparator'] || 'begins';
      appliedFilter.errorMessage = $location.search()['error-message'] || '';
      appliedFilter.userComparator = $location.search()['user-comparator'] || 'begins';
      appliedFilter.user = $location.search().user || '';
      appliedFilter.attributeName = $location.search()['custom-attribute-name'] || '';
      appliedFilter.attributeValueComparator = $location.search()['custom-attribute-value-comparator'] || 'begins';
      appliedFilter.attributeValue = $location.search()['custom-attribute-value'] || '';
      appliedFilter.limit = Number($location.search().limit) || defaultFilterLimit;

      if (priorAppliedFilter !== undefined && !angular.equals(appliedFilter, priorAppliedFilter)) {
        // e.g. back or forward button was used to navigate
        $scope.range.chartRefresh++;
      }

      $scope.filter = angular.copy(appliedFilter);
      // need to remove from and to so they aren't copied back during angular.extend(appliedFilter, $scope.filter)
      delete $scope.filter.from;
      delete $scope.filter.to;

      if (appliedFilter.durationMillisLow !== 0 && appliedFilter.durationMillisHigh) {
        $scope.filterDurationComparator = 'between';
      } else if (appliedFilter.durationMillisHigh) {
        $scope.filterDurationComparator = 'less';
      } else {
        $scope.filterDurationComparator = 'greater';
      }

      var modalAgentId = $location.search()['modal-agent-id'] || '';
      var modalTraceId = $location.search()['modal-trace-id'];
      var modalCheckLiveTraces = $location.search()['modal-check-live-traces'];
      if (firstLocationModalTraceId) {
        $location.search('modal-agent-id', firstLocationModalAgentId);
        $location.search('modal-trace-id', firstLocationModalTraceId);
        $location.search('modal-check-live-traces', firstLocationModalCheckLiveTraces);
        firstLocationModalAgentId = undefined;
        firstLocationModalTraceId = undefined;
        firstLocationModalCheckLiveTraces = undefined;
      } else if (modalTraceId) {
        if (firstLocation) {
          $location.search('modal-agent-id', null);
          $location.search('modal-trace-id', null);
          $location.search('modal-check-live-traces', null);
          $location.replace();
          firstLocationModalAgentId = modalAgentId;
          firstLocationModalTraceId = modalTraceId;
          firstLocationModalCheckLiveTraces = modalCheckLiveTraces;
        } else {
          highlightedTraceId = modalTraceId;
          $('#traceModal').data('location-query', 'modal-trace-id');
          traceModal.displayModal(modalAgentId, modalTraceId, modalCheckLiveTraces);
        }
      } else {
        $('#traceModal').modal('hide');
      }
      firstLocation = false;
    });

    $scope.$watch('filterDurationComparator', function (value) {
      if (value === 'greater') {
        $scope.filter.durationMillisHigh = undefined;
      } else if (value === 'less') {
        $scope.filter.durationMillisLow = 0;
      }
    });

    function updateLocation() {
      var query = $scope.buildQueryObjectForTraceTab();
      if (Number(appliedFilter.durationMillisLow)) {
        query['duration-millis-low'] = appliedFilter.durationMillisLow;
      }
      if (Number(appliedFilter.durationMillisHigh)) {
        query['duration-millis-high'] = appliedFilter.durationMillisHigh;
      }
      if (appliedFilter.headline) {
        query['headline-comparator'] = appliedFilter.headlineComparator;
        query.headline = appliedFilter.headline;
      }
      if (appliedFilter.errorMessage) {
        query['error-message-comparator'] = appliedFilter.errorMessageComparator;
        query['error-message'] = appliedFilter.errorMessage;
      }
      if (appliedFilter.user) {
        query['user-comparator'] = appliedFilter.userComparator;
        query.user = appliedFilter.user;
      }
      if (appliedFilter.attributeName) {
        query['custom-attribute-name'] = appliedFilter.attributeName;
      }
      if (appliedFilter.attributeValue) {
        query['custom-attribute-value-comparator'] = appliedFilter.attributeValueComparator;
        query['custom-attribute-value'] = appliedFilter.attributeValue;
      }
      if (Number(appliedFilter.limit) !== defaultFilterLimit) {
        query.limit = appliedFilter.limit;
      }
      // preserve modal-*, otherwise refresh on modal trace does not work
      query['modal-agent-id'] = $location.search()['modal-agent-id'];
      query['modal-trace-id'] = $location.search()['modal-trace-id'];
      query['modal-check-live-traces'] = $location.search()['modal-check-live-traces'];
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
          label: 'milliseconds',
          labelPadding: 7,
          tickFormatter: function (val) {
            return val.toLocaleString(undefined, {maximumSignificantDigits: 15});
          }
        },
        tooltip: true,
        tooltipOpts: {
          content: function (label, xval, yval) {
            function formatMillis(millis) {
              var display = '';

              function append(num, label) {
                if (num) {
                  display += ' ' + num + ' ' + label;
                  if (num !== 1) {
                    display += 's';
                  }
                }
              }

              var duration = moment.duration(millis);
              append(Math.floor(duration.asDays()), 'day');
              append(duration.hours(), 'hour');
              append(duration.minutes(), 'minute');
              append(duration.seconds(), 'second');
              return display;
            }

            return '<div class="gt-chart-tooltip"><div style="font-weight: 600;">'
                + moment(xval - yval).format('LTS') + ' to ' + moment(xval).format('LTS')
                + '</div><div>' +formatMillis(yval) + '</div></div>';
          }
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
          mode: 'xory'
        }
      };
      // render chart with no data points
      plot = $.plot($chart, [[]], options);
    })();

    plot.getAxes().yaxis.options.max = undefined;
    charts.initResize(plot, $scope);
    charts.startAutoRefresh($scope, 60000);
  }
]);
