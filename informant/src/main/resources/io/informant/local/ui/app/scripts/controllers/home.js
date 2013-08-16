/*
 * Copyright 2013 the original author or authors.
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

/* global informant, Informant, $, alert */

informant.controller('HomeCtrl', function ($scope, $filter, $http, $q, traceModal) {

  document.title = 'Home | Informant';
  $scope.$parent.title = 'Home';

  var plot;

  var fixedAggregateIntervalMillis;

  var currentRefreshId = 0;
  var currentZoomId = 0;

  var $chart = $('#chart');

  // qtip adds some code to the beginning of jquery's cleanData function which causes the trace
  // detail modal to close slowly when it has 5000 spans
  // this extra cleanup code is not needed anyways since cleanup is performed explicitly
  /* jshint -W106 */ // W106 is camelcase
  $.cleanData = $.cleanData_replacedByqTip;
  /* jshint +W106 */

  (function () {
    // with responsive design, body width doesn't change on every window resize event
    var $body = $('body');
    var bodyWidth = $body.width();
    var bodyHeight = $body.height();

    $(window).resize(function () {
      if ($body.width() !== bodyWidth || $body.height() !== bodyHeight) {
        bodyWidth = $body.width();
        bodyHeight = $body.height();
        plot.resize();
        plot.setupGrid();
        plot.draw();
      }
    });
  })();

  $scope.refreshChart = function (deferred) {
    var refreshId = ++currentRefreshId;
    var date = $scope.filter.date;
    var query = {
      from: $scope.filter.from,
      to: $scope.filter.to
    };
    $http.post('backend/aggregate/points', query)
        .success(function (response) {
          if (refreshId !== currentRefreshId) {
            return;
          }
          Informant.hideSpinner('#chartSpinner');
          fixedAggregateIntervalMillis = response.fixedAggregateIntervalSeconds * 1000;
          plot.getAxes().xaxis.options.gridLock = fixedAggregateIntervalMillis;
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
          plot.setData([ response.points ]);
          plot.setupGrid();
          plot.draw();
          if (deferred) {
            deferred.resolve('Success');
          }
        })
        .error(function () {
          if (refreshId !== currentRefreshId) {
            return;
          }
          if (deferred) {
            deferred.reject('Error occurred');
          } else {
            // TODO handle this better
            alert('Error occurred');
          }
        });
    updateGroupings();
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
      Informant.hideSpinner('#chartSpinner');
      $scope.$apply(function () {
        updateGroupings();
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
    Informant.hideSpinner('#chartSpinner');
    $scope.$apply(function () {
      updateGroupings();
    });
  });

  function afterZoom() {
    $scope.$apply(function () {
      $scope.filter.from = plot.getAxes().xaxis.min;
      $scope.filter.to = plot.getAxes().xaxis.max;
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
      if (point[0] >= from && point[0] <= to) {
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
    var from = $filter('date')(captureTime - fixedAggregateIntervalMillis, 'mediumTime');
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
    $chart.qtip({
      content: {
        text: text
      },
      position: {
        my: 'bottom center',
        target: [ x, y ],
        adjust: {
          y: -10
        },
        viewport: $(window)
      },
      style: {
        classes: 'ui-tooltip-bootstrap qtip-override qtip-border-color-0'
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

  function updateGroupings() {
    var query = {
      from: $scope.filter.from,
      to: $scope.filter.to,
      limit: 10
    };
    $http.post('backend/aggregate/groupings', query)
        .success(function (response) {
          $('#groupAggregates').html('');
          $.each(response, function (i, grouping) {
            var average = ((grouping.durationTotal / grouping.traceCount) / 1000000000).toFixed(2);
            $('#groupAggregates').append('<div>' + grouping.grouping + ': ' + average + '</div>');
          });
        })
        .error(function () {
          // TODO handle this better
          alert('Error occurred');
        });
  }

  // TODO CONVERT TO ANGULARJS, global $http error handler?
  Informant.configureAjaxError();

  $('#zoomOut').click(function () {
    plot.zoomOut();
  });
  $('#modalHide').click(traceModal.hideModal);

  var now = new Date();
  now.setSeconds(0);
  var today = new Date();
  today.setHours(0, 0, 0, 0);

  $scope.filter = {};
  $scope.filter.date = today;
  // show 2 hour interval, but nothing prior to today (e.g. if 'now' is 1am) or after today (e.g. if 'now' is 11:55pm)
  $scope.filter.from = Math.max(now.getTime() - 105 * 60 * 1000, today.getTime());
  $scope.filter.to = Math.min($scope.filter.from + 120 * 60 * 1000, today.getTime() + 24 * 60 * 60 * 1000);

  $scope.$watch('filter.date', function (date) {
    var midnight = new Date($scope.filter.from).setHours(0, 0, 0, 0);
    $scope.filter.from = date.getTime() + ($scope.filter.from - midnight);
    $scope.filter.to = date.getTime() + ($scope.filter.to - midnight);
  });

  (function () {
    var options = {
      legend: {
        show: false
      },
      grid: {
        hoverable: true,
        mouseActiveRadius: 10
      },
      xaxis: {
        mode: 'time',
        timezone: 'browser',
        twelveHourClock: true,
        ticks: 5,
        gridLock: 1
      },
      yaxis: {
        ticks: 10,
        zoomRange: false,
        gridLock: 15
      },
      zoom: {
        interactive: true,
        amount: 1.5,
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
          radius: 10,
          lineWidth: 0
        }
      }
    };
    options.xaxis.min = $scope.filter.from;
    options.xaxis.max = $scope.filter.to;
    options.xaxis.zoomRange = [
      $scope.filter.date.getTime(),
      $scope.filter.date.getTime() + 24 * 60 * 60 * 1000
    ];
    options.yaxis.min = 0;
    // 10 second yaxis max just for initial empty chart rendering
    options.yaxis.max = 10;
    // render chart with no data points
    plot = $.plot($chart, [], options);
  })();

  plot.getAxes().yaxis.options.max = undefined;
  Informant.showSpinner('#chartSpinner');
  $scope.refreshChart();
});
