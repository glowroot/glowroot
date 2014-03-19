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

/* global glowroot, angular, Glowroot, TraceRenderer, $, Spinner, alert */

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

    var summaryItem;

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
      var low = appliedFilter.low;
      var high = appliedFilter.high;
      var refreshId = ++currentRefreshId;
      var spinner = Glowroot.showSpinner('#chartSpinner');
      $http.get('backend/trace/points?' + queryStrings.encodeObject(appliedFilter))
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
              plot.getAxes().yaxis.options.min = low;
              plot.getAxes().yaxis.options.realMax = high;
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
      var low = plot.getAxes().yaxis.min;
      var high = plot.getAxes().yaxis.options.realMax;
      appliedFilter.from = from;
      appliedFilter.to = to;
      if (zoomingOut) {
        // scroll zooming out, reset duration limits
        $scope.filterDurationComparator = 'greater';
        // set both appliedFilter and $scope.filter low/high
        appliedFilter.low = $scope.filter.low = 0;
        appliedFilter.high = $scope.filter.high = undefined;
      } else {
        // set both appliedFilter and $scope.filter low/high
        appliedFilter.low = $scope.filter.low = low;
        appliedFilter.high = $scope.filter.high = high;
        if (high && low !== 0) {
          $scope.filterDurationComparator = 'between';
        } else if (high) {
          $scope.filterDurationComparator = 'less';
        } else {
          $scope.filterDurationComparator = 'greater';
        }
      }
    }

    function getFilteredData() {
      var from = plot.getAxes().xaxis.options.min;
      var to = plot.getAxes().xaxis.options.max;
      var low = plot.getAxes().yaxis.options.min;
      var high = plot.getAxes().yaxis.options.realMax || Number.MAX_VALUE;
      var data = [];
      var i, j;
      for (i = 0; i < plot.getData().length; i++) {
        data.push([]);
        var points = plot.getData()[i].data;
        for (j = 0; j < points.length; j++) {
          var point = points[j];
          if (point[0] >= from && point[0] <= to && point[1] >= low && point[1] <= high) {
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
        showTraceDetailTooltip(item);
      }
    });

    function showTraceDetailTooltip(item) {
      var x = item.pageX;
      var y = item.pageY;
      var spinner = new Spinner({ lines: 10, width: 3, radius: 6, top: 2, left: 2 });

      function displaySpinner() {
        if (spinner) {
          var html = '<div id="tooltipSpinner" style="width: 36px; height: 36px;"></div>';
          $chart.qtip({
            content: {
              text: html
            },
            position: {
              my: 'left center',
              target: [ x, y ],
              viewport: $chart
            },
            style: {
              classes: 'ui-tooltip-bootstrap qtip-override qtip-border-color-' + item.seriesIndex
            },
            hide: {
              event: 'unfocus'
            },
            show: {
              event: false
            },
            events: {
              hide: function () {
                summaryItem = undefined;
              }
            }
          });
          $chart.qtip('show');
          spinner.spin($('#tooltipSpinner').get(0));
        }
      }

      // small delay so that if there is an immediate response the spinner doesn't blink
      setTimeout(displaySpinner, 100);
      var id = plot.getData()[item.seriesIndex].data[item.dataIndex][2];
      summaryItem = item;
      var modalVanishPoint = [x, y];
      var localSummaryItem = summaryItem;
      $scope.$apply(function () {
        $http.get('backend/trace/summary/' + id)
            .success(function (data) {
              spinner.stop();
              spinner = undefined;
              // intentionally not using itemEquals() here to avoid edge case where user clicks on item
              // then zooms and clicks on the same item. if the first request comes back after the second
              // click, then itemEquals() will be true and it will pop up the correct summary, but at the
              // incorrect location
              if (localSummaryItem !== summaryItem) {
                // too slow, the user has moved on to another summary already
                return;
              }
              var text;
              var summaryTrace;
              if (data.expired) {
                text = 'expired';
              } else {
                summaryTrace = data;
                summaryTrace.truncateMetrics = true;
                var html = TraceRenderer.renderSummary(summaryTrace);
                var showDetailHtml = '<div style="margin-top: 0.5em;">' +
                    '<button class="flat-btn flat-btn-big-pad1aligned glowroot-link-color" id="showDetail"' +
                    ' style="font-size: 12px;">show detail</button></div>';
                text = html + showDetailHtml;
              }
              var $chartContainer = $('.chart-container');
              var chartOffset = $chartContainer.offset();
              // the +2 makes tooltip spacing from the data point the same when tooltip is both left and right of the
              // data point
              var target = [ x - chartOffset.left + 2, y - chartOffset.top ];
              $chart.qtip({
                content: {
                  text: text
                },
                position: {
                  my: 'left center',
                  target: target,
                  adjust: {
                    x: 5
                  },
                  viewport: $(window),
                  // container is the dom node where qtip div is attached
                  // this needs to be inside the angular template so that its lifecycle is tied to the angular template
                  container: $chartContainer
                },
                style: {
                  classes: 'ui-tooltip-bootstrap qtip-override qtip-border-color-' + item.seriesIndex
                },
                hide: {
                  event: false
                },
                show: {
                  event: false
                },
                events: {
                  hide: function () {
                    summaryItem = undefined;
                  }
                }
              });

              $chart.qtip('show');
              $('#showDetail').click(function () {
                var $qtip = $('.qtip');
                var initialFixedOffset = {
                  top: $qtip.offset().top - $(window).scrollTop(),
                  left: $qtip.offset().left - $(window).scrollLeft()
                };
                var initialWidth = $qtip.width();
                var initialHeight = $qtip.height();
                $chart.qtip('hide');
                traceModal.displayModal(summaryTrace, initialFixedOffset, initialWidth, initialHeight,
                    modalVanishPoint);
                return false;
              });
            })
            .error(function () {
              // TODO handle this better
              alert('Error occurred');
            });
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

    $(document).keyup(function (e) {
      // esc key
      if (e.keyCode === 27 && summaryItem) {
        // the tooltips (spinny and summary) have hide events that set summaryItem = undefined
        // so summaryItem must be checked before calling hideTooltip()
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
        value: 'not_contains'
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
    appliedFilter.low = Number($location.search().low) || 0;
    appliedFilter.high = Number($location.search().high) || undefined;
    appliedFilter.background = $location.search().background || '';
    appliedFilter.headlineComparator = $location.search()['headline-comparator'] || 'begins';
    appliedFilter.headline = $location.search().headline || '';
    appliedFilter.transactionNameComparator = $location.search()['transaction-name-comparator'] || 'begins';
    appliedFilter.transactionName = $location.search()['transaction-name'] || '';
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
      appliedFilter.limit = $('html').hasClass('lt-ie9') ? 100 : 500;
    }
    appliedFilter.errorOnly = $location.search()['error-only'] === 'true';
    appliedFilter.fineOnly = $location.search()['fine-only'] === 'true';

    $scope.filter = angular.copy(appliedFilter);
    // need to remove from and to so they aren't copied back during angular.extend(appliedFilter, $scope.filter)
    delete $scope.filter.from;
    delete $scope.filter.to;

    if (appliedFilter.low !== 0 && appliedFilter.high) {
      $scope.filterDurationComparator = 'between';
    } else if (appliedFilter.high) {
      $scope.filterDurationComparator = 'less';
    } else {
      $scope.filterDurationComparator = 'greater';
    }

    $scope.$watch('filterDurationComparator', function (value) {
      if (value === 'greater') {
        $scope.filter.high = undefined;
      } else if (value === 'less') {
        $scope.filter.low = 0;
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
      if (Number(appliedFilter.low)) {
        query.low = appliedFilter.low;
      }
      if (Number(appliedFilter.high)) {
        query.high = appliedFilter.high;
      }
      if (appliedFilter.background === 'true' || appliedFilter.background === 'false') {
        query.background = appliedFilter.background;
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
