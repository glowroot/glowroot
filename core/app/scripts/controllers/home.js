/*
 * Copyright 2013-2014 the original author or authors.
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

glowroot.controller('HomeCtrl', [
  '$scope',
  '$location',
  '$filter',
  '$http',
  '$q',
  'traceModal',
  'queryStrings',
  function ($scope, $location, $filter, $http, $q, traceModal, queryStrings) {
    // \u00b7 is &middot;
    document.title = 'Home \u00b7 Glowroot';
    $scope.$parent.title = 'Home';
    $scope.$parent.activeNavbarItem = 'home';

    var plot;

    var fixedAggregationIntervalMillis = $scope.layout.fixedAggregationIntervalSeconds * 1000;

    var currentRefreshId = 0;
    var currentZoomId = 0;

    var $chart = $('#chart');

    $scope.$watchCollection('[containerWidth, windowHeight]', function () {
      plot.resize();
      plot.setupGrid();
      plot.draw();
    });

    $scope.refreshChart = function (deferred) {
      var refreshId = ++currentRefreshId;
      var date = $scope.filter.date;
      var query = {
        from: $scope.filter.from,
        to: $scope.filter.to
      };
      var spinner = Glowroot.showSpinner('#chartSpinner');
      $http.get('backend/home/points?' + queryStrings.encodeObject(query))
          .success(function (data) {
            if (refreshId !== currentRefreshId) {
              return;
            }
            spinner.stop();
            $scope.refreshChartError = false;
            if (deferred) {
              // user clicked on Refresh button, need to reset axes
              plot.getAxes().xaxis.options.min = query.from;
              plot.getAxes().xaxis.options.max = query.to;
              plot.getAxes().xaxis.options.zoomRange = [
                date.getTime(),
                date.getTime() + 24 * 60 * 60 * 1000
              ];
              plot.unhighlight();
            }
            var points = [];
            var lastPoint;
            angular.forEach(data.points, function (currentPoint) {
              if (lastPoint && (currentPoint[0] - lastPoint[0]) > fixedAggregationIntervalMillis * 1.5) {
                points.push(undefined);
              }
              points.push(currentPoint);
              lastPoint = currentPoint;
            });
            plot.setData([ points ]);
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
      updateTransactions();
    };

    $chart.bind('plotzoom', function (event, plot, args) {
      var zoomingOut = args.amount && args.amount < 1;
      plot.setData(getFilteredData());
      plot.setupGrid();
      plot.draw();
      afterZoom();
      if (zoomingOut) {
        var zoomId = ++currentZoomId;
        // use 100 millisecond delay to handle rapid zooming
        setTimeout(function () {
          if (zoomId !== currentZoomId) {
            return;
          }
          $scope.$apply(function () {
            $scope.refreshChart(undefined);
          });
        }, 100);
      } else {
        // no need to fetch new data
        // increment currentRefreshId to cancel any refresh in action
        currentRefreshId++;
        $scope.$apply(function () {
          updateTransactions();
        });
      }
    });

    $chart.bind('plotselected', function (event, ranges) {
      plot.clearSelection();
      // perform the zoom
      plot.getAxes().xaxis.options.min = ranges.xaxis.from;
      plot.getAxes().xaxis.options.max = ranges.xaxis.to;
      plot.setData(getFilteredData());
      plot.setupGrid();
      plot.draw();
      afterZoom();
      // no need to fetch new data
      // increment currentRefreshId to cancel any refresh in action
      currentRefreshId++;
      $scope.$apply(function () {
        updateTransactions();
      });
    });

    function afterZoom() {
      $scope.$apply(function () {
        $scope.filter.from = plot.getAxes().xaxis.min;
        $scope.filter.to = plot.getAxes().xaxis.max;
        updateLocation();
      });
    }

    function getFilteredData() {
      var from = plot.getAxes().xaxis.options.min;
      var to = plot.getAxes().xaxis.options.max;
      var data = [];
      var i;
      var points = plot.getData()[0].data;
      for (i = 0; i < points.length; i++) {
        var point = points[i];
        // !point handles undefined points which are used to represent no data collected in that period
        if (!point || point[0] >= from && point[0] <= to) {
          data.push(point);
        }
      }
      return [ data ];
    }

    var showingItemId;
    $chart.bind('plothover', function (event, pos, item) {
      if (item) {
        var itemId = item.datapoint[0];
        if (itemId !== showingItemId) {
          showTraceDetailTooltip(item);
          showingItemId = itemId;
        }
      } else {
        hideTooltip();
      }
    });

    function showTraceDetailTooltip(item) {
      var x = item.pageX;
      var y = item.pageY;
      var captureTime = item.datapoint[0];
      var from = $filter('date')(captureTime - fixedAggregationIntervalMillis, 'mediumTime');
      var to = $filter('date')(captureTime, 'mediumTime');
      var traceCount = plot.getData()[item.seriesIndex].data[item.dataIndex][2];
      var average;
      if (traceCount === 0) {
        average = '--';
      } else {
        average = item.datapoint[1].toFixed(2);
      }
      if (traceCount === 1) {
        traceCount = traceCount + ' trace';
      } else {
        traceCount = traceCount + ' traces';
      }
      var text = '<span class="home-tooltip-label">From:</span>' + from + '<br>' +
          '<span class="home-tooltip-label">To:</span>' + to + '<br>' +
          '<span class="home-tooltip-label">Average:</span>' + average + ' seconds<br>' +
          '<span class="home-tooltip-label"></span>(' + traceCount + ')';
      var $chartContainer = $('.chart-container');
      var chartOffset = $chartContainer.offset();
      var target = [ x - chartOffset.left + 1, y - chartOffset.top ];
      $chart.qtip({
        content: {
          text: text
        },
        position: {
          my: 'bottom center',
          target: target,
          adjust: {
            y: -5
          },
          viewport: $(window),
          // container is the dom node where qtip div is attached
          // this needs to be inside the angular template so that its lifecycle is tied to the angular template
          container: $chartContainer
        },
        style: {
          classes: 'ui-tooltip-bootstrap qtip-override-home qtip-border-color-0'
        },
        hide: {
          event: false
        },
        show: {
          event: false
        },
        events: {
          hide: function () {
            showingItemId = undefined;
          }
        }
      });
      $chart.qtip('show');
    }

    function hideTooltip() {
      $chart.qtip('hide');
    }

    $chart.mousedown(function () {
      hideTooltip();
    });

    $(document).keyup(function (e) {
      // esc key
      if (e.keyCode === 27 && showingItemId) {
        // the tooltips have hide events that set showingItemId = undefined
        // so showingItemId must be checked before calling hideTooltip()
        hideTooltip();
      }
    });

    function updateTransactions() {
      var query = {
        from: $scope.filter.from,
        to: $scope.filter.to,
        limit: 40
      };
      $http.get('backend/home/transaction-aggregates?' + queryStrings.encodeObject(query))
          .success(function (data) {
            $scope.transactionAggregatesError = false;
            $scope.transactionAggregates = data;
          })
          .error(function (data, status) {
            if (status === 0) {
              $scope.transactionAggregatesError = 'Unable to connect to server';
            } else {
              $scope.transactionAggregatesError = 'An error occurred';
            }
          });
    }

    function updateLocation() {
      var query = {
        from: $scope.filter.from - fixedAggregationIntervalMillis,
        to: $scope.filter.to
      };
      $location.search(query).replace();
    }

    $scope.tracesQueryString = function (transactionName) {
      // from is adjusted because aggregates are really aggregates of interval before aggregate timestamp
      var adjustedFrom = $scope.filter.from - fixedAggregationIntervalMillis;
      return 'from=' + adjustedFrom + '&to=' + $scope.filter.to + '&transactionName=' +
          transactionName +  '&transactionNameComparator=equals&background=false';
    };

    // TODO CONVERT TO ANGULARJS, global $http error handler?
    Glowroot.configureAjaxError();

    $('#zoomOut').click(function () {
      plot.zoomOut();
    });
    $('#modalHide').click(traceModal.hideModal);

    $scope.filter = {};
    $scope.filter.from = Number($location.search().from);
    $scope.filter.to = Number($location.search().to);
    // both from and to must be supplied or neither will take effect
    if ($scope.filter.from && $scope.filter.to) {
      $scope.filter.from += fixedAggregationIntervalMillis;
      $scope.filter.date = new Date($scope.filter.from);
      $scope.filter.date.setHours(0, 0, 0, 0);
    } else {
      var today = new Date();
      today.setHours(0, 0, 0, 0);
      $scope.filter.date = today;
      // show 2 hour interval, but nothing prior to today (e.g. if 'now' is 1am) or after today
      // (e.g. if 'now' is 11:55pm)
      var now = new Date();
      now.setSeconds(0);
      $scope.filter.from = Math.max(now.getTime() - 105 * 60 * 1000, today.getTime());
      $scope.filter.to = Math.min($scope.filter.from + 120 * 60 * 1000, today.getTime() + 24 * 60 * 60 * 1000);
    }
    updateLocation();

    $scope.$watch('filter.date', function (date) {
      var midnight = new Date($scope.filter.from).setHours(0, 0, 0, 0);
      $scope.filter.from = date.getTime() + ($scope.filter.from - midnight);
      $scope.filter.to = date.getTime() + ($scope.filter.to - midnight);
      updateLocation();
    });

    (function () {
      var options = {
        legend: {
          show: false
        },
        grid: {
          hoverable: true,
          mouseActiveRadius: 10,
          // min border margin should match aggregate chart so they are positioned the same from the top of page
          // without specifying min border margin, the point radius is used
          minBorderMargin: 10
        },
        xaxis: {
          mode: 'time',
          timezone: 'browser',
          twelveHourClock: true,
          ticks: 5,
          min: $scope.filter.from,
          max: $scope.filter.to,
          absoluteZoomRange: true,
          zoomRange: [
            $scope.filter.date.getTime(),
            $scope.filter.date.getTime() + 24 * 60 * 60 * 1000
          ]
        },
        yaxis: {
          ticks: 10,
          zoomRange: false,
          min: 0,
          // 10 second yaxis max just for initial empty chart rendering
          max: 10
        },
        zoom: {
          interactive: true,
          amount: 2,
          skipDraw: true
        },
        colors: [
          $('#offscreenNormalColor').css('border-top-color')
        ],
        selection: {
          mode: 'x'
        },
        series: {
          points: {
            show: true
          },
          lines: {
            show: true
          }
        }
      };
      // render chart with no data points
      plot = $.plot($chart, [], options);
      plot.getAxes().xaxis.options.borderGridLock = fixedAggregationIntervalMillis;
    })();

    plot.getAxes().yaxis.options.max = undefined;
    $scope.refreshChart();
  }
]);
