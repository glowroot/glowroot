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

/* global glowroot, angular, Glowroot, $, RColor */

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
    var plotColors;
    // plotTransactionNames is only updated when the plot is updated, and it is only used by the tooltip to identify
    // which transaction name to use based on the plot series index
    var plotTransactionNames;

    var plotColor0 = $('#offscreenNormalColor').css('border-top-color');

    var fixedAggregationIntervalMillis = $scope.layout.fixedAggregationIntervalSeconds * 1000;

    var currentRefreshId = 0;
    var currentZoomId = 0;

    var $chart = $('#chart');

    var rcolor = new RColor();
    // RColor evenly distributes colors, but largely separated calls can have similar colors
    // so availableColors is used to store returned colors to keep down the number of calls to RColor
    // and to keep the used colors well distributed
    var availableColors = [];

    // top 25 is a nice number, screen is not too large
    var transactionAggregatesLimit = 25;

    // this is used to calculate bar width under transaction name representing the proportion of total time
    var maxTransactionAggregateTotalMillis;

    function nextRColor() {
      return rcolor.get(true, 0.5, 0.5);
    }

    $scope.$watchCollection('[containerWidth, windowHeight]', function () {
      plot.resize();
      plot.setupGrid();
      plot.draw();
    });

    $scope.refreshChart = function (deferred, skipUpdateTransactions) {
      var refreshId = ++currentRefreshId;
      var date = $scope.filter.date;
      var transactionNames = [];
      angular.forEach($scope.checkedTransactionColors, function (color, transactionName) {
        transactionNames.push(transactionName);
      });
      var midnight = new Date($scope.filter.from).setHours(0, 0, 0, 0);
      $scope.filter.from = date.getTime() + ($scope.filter.from - midnight);
      $scope.filter.to = date.getTime() + ($scope.filter.to - midnight);
      var query = {
        from: $scope.filter.from,
        to: $scope.filter.to,
        transactionNames: transactionNames
      };
      updateLocation();
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
            var plotData = [ data.points ];
            angular.forEach(data.transactionPoints, function (points, transactionName) {
              plotData.push(points);
            });
            plotColors = [];
            plotColors.push(plotColor0);
            angular.forEach(query.transactionNames, function (transactionName) {
              plotColors.push($scope.checkedTransactionColors[transactionName]);
            });
            plotTransactionNames = query.transactionNames;
            updatePlotData(plotData);
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
      if (!skipUpdateTransactions) {
        updateAggregates();
      }
    };

    function updatePlotData(data) {
      var plotData = [
        {
          data: data[0],
          lines: {
            lineWidth: 3
          },
          color: plotColors[0]
        }
      ];
      for (var i = 1; i < data.length; i++) {
        plotData.push({
          data: data[i],
          lines: {
            lineWidth: 1
          },
          color: plotColors[i]
        });
      }
      plot.setData(plotData);
      plot.setupGrid();
      plot.draw();
    }

    $chart.bind('plotzoom', function (event, plot, args) {
      var zoomingOut = args.amount && args.amount < 1;
      updatePlotData(getFilteredData());
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
          updateAggregates();
        });
      }
    });

    $chart.bind('plotselected', function (event, ranges) {
      plot.clearSelection();
      // perform the zoom
      plot.getAxes().xaxis.options.min = ranges.xaxis.from;
      plot.getAxes().xaxis.options.max = ranges.xaxis.to;
      updatePlotData(getFilteredData());
      afterZoom();
      // no need to fetch new data
      // increment currentRefreshId to cancel any refresh in action
      currentRefreshId++;
      $scope.$apply(function () {
        updateAggregates();
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
      var i, j;
      var filteredData = [];
      var data = plot.getData();
      for (i = 0; i < data.length; i++) {
        var filteredPoints = [];
        var points = data[i].data;
        for (j = 0; j < points.length; j++) {
          var point = points[j];
          // !point handles undefined points which are used to represent no data collected in that period
          if (!point || point[0] >= from && point[0] <= to) {
            filteredPoints.push(point);
          }
        }
        filteredData.push(filteredPoints);
      }
      return filteredData;
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
      var text = '';
      if (item.seriesIndex === 0) {
        text += '<strong>All Transactions</strong><br>';
      } else {
        text += '<strong>' + plotTransactionNames[item.seriesIndex - 1] + '</strong><br>';
      }
      text += '<span class="home-tooltip-label">From:</span>' + from + '<br>' +
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

    function updateAggregates(deferred) {
      var query = {
        from: $scope.filter.from,
        to: $scope.filter.to,
        sortAttribute: $scope.tableSortAttribute,
        sortDirection: $scope.tableSortDirection,
        // +1 just to find out if there are more and to show "Show more" button, the +1 will not be displayed
        transactionAggregatesLimit: transactionAggregatesLimit + 1
      };
      $http.get('backend/home/aggregates?' + queryStrings.encodeObject(query))
          .success(function (data) {
            $scope.aggregatesError = false;
            $scope.overallAggregate = data.overallAggregate;
            if (data.transactionAggregates.length === transactionAggregatesLimit + 1) {
              data.transactionAggregates.pop();
              $scope.hasMoreAggregates = true;
            } else {
              $scope.hasMoreAggregates = false;
            }
            $scope.transactionAggregates = data.transactionAggregates;
            maxTransactionAggregateTotalMillis = 0;
            angular.forEach($scope.transactionAggregates, function (transactionAggregate) {
              maxTransactionAggregateTotalMillis =
                  Math.max(maxTransactionAggregateTotalMillis, transactionAggregate.totalMillis);
            });
            if (deferred) {
              deferred.resolve();
            }
          })
          .error(function (data, status) {
            if (status === 0) {
              $scope.aggregatesError = 'Unable to connect to server';
            } else {
              $scope.aggregatesError = 'An error occurred';
            }
            if (deferred) {
              deferred.reject($scope.aggregatesError);
            }
          });
    }

    function updateLocation() {
      var transactionNames = [];
      angular.forEach($scope.checkedTransactionColors, function (color, transactionName) {
        transactionNames.push(transactionName);
      });
      var query = {
        from: $scope.filter.from - fixedAggregationIntervalMillis,
        to: $scope.filter.to,
        'table-sort-attribute': $scope.tableSortAttribute,
        'table-sort-direction': $scope.tableSortDirection,
        'transaction-name': transactionNames
      };
      $location.search(query).replace();
    }

    $scope.sortTable = function (attributeName) {
      if ($scope.tableSortAttribute === attributeName) {
        // switch direction
        if ($scope.tableSortDirection === 'desc') {
          $scope.tableSortDirection = 'asc';
        } else {
          $scope.tableSortDirection = 'desc';
        }
      } else {
        $scope.tableSortAttribute = attributeName;
        $scope.tableSortDirection = 'desc';
      }
      updateLocation();
      updateAggregates();
    };

    $scope.sortIconClass = function (attributeName) {
      if ($scope.tableSortAttribute !== attributeName) {
        return '';
      }
      if ($scope.tableSortDirection === 'desc') {
        return 'caret';
      } else {
        return 'caret home-caret-reversed';
      }
    };

    $scope.tracesQueryString = function (transactionName) {
      if (transactionName) {
        return queryStrings.encodeObject({
          // from is adjusted because aggregates are really aggregates of interval before aggregate timestamp
          from: $scope.filter.from - fixedAggregationIntervalMillis,
          to: $scope.filter.to,
          transactionName: transactionName,
          transactionNameComparator: 'equals',
          background: 'false'
        });
      } else {
        return queryStrings.encodeObject({
          // from is adjusted because aggregates are really aggregates of interval before aggregate timestamp
          from: $scope.filter.from - fixedAggregationIntervalMillis,
          to: $scope.filter.to,
          background: 'false'
        });
      }
    };

    $scope.showMoreAggregates = function (deferred) {
      // double each time
      transactionAggregatesLimit *= 2;
      updateAggregates(deferred);
    };

    $scope.updateFilterLimit = function (limit) {
      $scope.updatingFilterLimit = true;
      var deferred = $q.defer();
      deferred.promise.then(function () {
        $scope.updatingFilterLimit = false;
      }, function (error) {
        // TODO handle error
        $scope.updatingFilterLimit = false;
      });
    };

    $scope.transactionRowStyle = function (transactionName) {
      var color = $scope.checkedTransactionColors[transactionName];
      if (color) {
        return {
          color: color,
          'font-weight': 'bold'
        };
      } else {
        return {};
      }
    };

    $scope.displayFilterRowStyle = function (transactionName) {
      var color = transactionName ? $scope.checkedTransactionColors[transactionName] : plotColor0;
      return {
        'background-color': color,
        color: 'white',
        padding: '5px 10px',
        // for some reason there is already a gap when running under grunt serve, and margin-right makes it too big
        // but this is correct when not running under grunt serve
        'margin-right': '5px',
        'border-radius': '3px',
        'margin-bottom': '5px'
      };
    };

    $scope.clickTransactionName = function (transactionName) {
      var color = $scope.checkedTransactionColors[transactionName];
      if (color) {
        // uncheck it
        availableColors.push(color);
        delete $scope.checkedTransactionColors[transactionName];
      } else {
        // check it
        color = availableColors.length ? availableColors.pop() : nextRColor();
        $scope.checkedTransactionColors[transactionName] = color;
      }
      updateLocation();
      $scope.refreshChart(undefined, true);
    };

    $scope.removeDisplayedTransaction = function (transactionName) {
      var color = $scope.checkedTransactionColors[transactionName];
      availableColors.push(color);
      delete $scope.checkedTransactionColors[transactionName];
      updateLocation();
      $scope.refreshChart(undefined, true);
    };

    $scope.transactionBarWidth = function (totalMillis) {
      return (totalMillis / maxTransactionAggregateTotalMillis) * 100 + '%';
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
      var fixedAggregationIntervalMinutes = fixedAggregationIntervalMillis / (60 * 1000);
      if (fixedAggregationIntervalMinutes > 1) {
        // this is the normal case since default aggregation interval is 5 min
        var minutesRoundedDownToNearestAggregationInterval =
            fixedAggregationIntervalMinutes * Math.floor(now.getMinutes() / fixedAggregationIntervalMinutes);
        now.setMinutes(minutesRoundedDownToNearestAggregationInterval);
      }
      $scope.filter.from = Math.max(now.getTime() - 105 * 60 * 1000, today.getTime());
      $scope.filter.to = Math.min($scope.filter.from + 120 * 60 * 1000, today.getTime() + 24 * 60 * 60 * 1000);
    }
    $scope.tableSortAttribute = $location.search()['table-sort-attribute'] || 'total';
    $scope.tableSortDirection = $location.search()['table-sort-direction'] || 'desc';

    $scope.checkedTransactionColors = {};
    var transactionNames = $location.search()['transaction-name'];
    if (angular.isArray(transactionNames)) {
      angular.forEach(transactionNames, function (transactionName) {
        $scope.checkedTransactionColors[transactionName] = nextRColor();
      });
    } else if (transactionNames) {
      $scope.checkedTransactionColors[transactionNames] = nextRColor();
    }

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
          max: 10,
          label: 'seconds'
        },
        zoom: {
          interactive: true,
          amount: 2,
          skipDraw: true
        },
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
