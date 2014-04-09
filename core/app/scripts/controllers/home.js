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
  '$timeout',
  '$modal',
  'queryStrings',
  'httpErrors',
  function ($scope, $location, $filter, $http, $q, $timeout, $modal, queryStrings, httpErrors) {
    // \u00b7 is &middot;
    document.title = 'Home \u00b7 Glowroot';
    $scope.$parent.title = 'Home';
    $scope.$parent.activeNavbarItem = 'home';

    var plot;
    // plotTransactionName is only updated when the plot is updated
    var plotTransactionName;

    var fixedAggregationIntervalMillis = $scope.layout.fixedAggregationIntervalSeconds * 1000;

    var currentRefreshId = 0;
    var currentZoomId = 0;

    var $chart = $('#chart');

    // top 25 is a nice number, screen is not too large
    var transactionLimit = 25;

    // this is used to calculate bar width under transaction name representing the proportion of total time
    var maxTransactionTotalMicros;

    $scope.$watchCollection('[containerWidth, windowHeight]', function () {
      plot.resize();
      plot.setupGrid();
      plot.draw();
    });

    $scope.showChartSpinner = 0;

    function refreshChart(refreshButtonDeferred, skipUpdateTransactions) {
      var date = $scope.filterDate;
      var refreshId = ++currentRefreshId;
      var query = {
        from: $scope.chartFrom,
        to: $scope.chartTo
      };
      if ($scope.selectedTransactionName) {
        query.transactionName = $scope.selectedTransactionName;
      }
      var pointsDeferred;
      if (refreshButtonDeferred && !skipUpdateTransactions) {
        pointsDeferred = $q.defer();
      } else {
        // TODO this conditional won't be needed once the home page displays metric stacked chart and
        // skipUpdateTransactions is not needed
        pointsDeferred = refreshButtonDeferred;
      }
      $scope.showChartSpinner++;
      $http.get('backend/home/stacked?' + queryStrings.encodeObject(query))
          .success(function (data) {
            $scope.showChartSpinner--;
            if (refreshId !== currentRefreshId) {
              return;
            }
            $scope.refreshChartError = false;
            // reset axis in case user changed the date and then zoomed in/out to trigger this refresh
            plot.getAxes().xaxis.options.min = query.from;
            plot.getAxes().xaxis.options.max = query.to;
            plot.getAxes().xaxis.options.zoomRange = [
              date.getTime(),
                  date.getTime() + 24 * 60 * 60 * 1000
            ];
            plotTransactionName = query.transactionName;
            // build stacked plot data from metrics
            // take top 5 metrics by total + 1 'Other' bucket
            // do this on server
            var plotData = [];
            angular.forEach(data, function (metricSeries) {
              plotData.push({
                data: metricSeries.data,
                label: metricSeries.name ? metricSeries.name : 'Other'
              });
            });
            plot.setData(plotData);
            plot.setupGrid();
            plot.draw();
            if (pointsDeferred) {
              pointsDeferred.resolve('Success');
            }
          })
          .error(function (data, status) {
            $scope.showChartSpinner--;
            if (refreshId !== currentRefreshId) {
              return;
            }
            $scope.chartLimitExceeded = false;
            if (status === 0) {
              $scope.refreshChartError = 'Unable to connect to server';
            } else {
              $scope.refreshChartError = 'An error occurred';
            }
            if (pointsDeferred) {
              pointsDeferred.reject($scope.refreshChartError);
            }
          });
      if (!skipUpdateTransactions) {
        // TODO this conditional will be always true once the home page displays metric stacked chart and
        // skipUpdateTransactions is not needed
        var transactionsDeferred;
        if (refreshButtonDeferred) {
          transactionsDeferred = $q.defer();
          $q.all([pointsDeferred.promise, transactionsDeferred.promise])
              .then(function () {
                refreshButtonDeferred.resolve('Success');
              }, function (data) {
                refreshButtonDeferred.resolve(data);
              });
        }
        // give the points request above a small head start since otherwise the transactions query could get handled
        // first, which isn't that bad, but the aggregate query is much slower and the glowroot http handler is
        // throttled to one thread currently
        $timeout(function () {
          updateTransactions(transactionsDeferred);
        }, 5);
      }
    }

    $scope.refreshButtonClick = function (deferred) {
      var midnight = new Date($scope.chartFrom).setHours(0, 0, 0, 0);
      if (midnight !== $scope.filterDate.getTime()) {
        // filterDate has changed
        chartFromToDefault = false;
        $scope.chartFrom = $scope.filterDate.getTime() + ($scope.chartFrom - midnight);
        $scope.chartTo = $scope.filterDate.getTime() + ($scope.chartTo - midnight);
      }
      updateLocation();
      refreshChart(deferred);
    };

    $chart.bind('plotzoom', function (event, plot, args) {
      plot.setupGrid();
      // TODO add spinner
      plot.draw();
      $scope.$apply(function () {
        $scope.chartFrom = plot.getAxes().xaxis.min;
        $scope.chartTo = plot.getAxes().xaxis.max;
        chartFromToDefault = false;
        updateLocation();
      });
      var zoomId = ++currentZoomId;
      // use 100 millisecond delay to handle rapid zooming
      setTimeout(function () {
        if (zoomId !== currentZoomId) {
          return;
        }
        $scope.$apply(function () {
          refreshChart();
        });
      }, 100);
    });

    $chart.bind('plotselected', function (event, ranges) {
      plot.clearSelection();
      // perform the zoom
      plot.getAxes().xaxis.options.min = ranges.xaxis.from;
      plot.getAxes().xaxis.options.max = ranges.xaxis.to;
      plot.setupGrid();
      plot.draw();
      $scope.$apply(function () {
        $scope.chartFrom = plot.getAxes().xaxis.min;
        $scope.chartTo = plot.getAxes().xaxis.max;
        chartFromToDefault = false;
        updateLocation();
      });
      currentRefreshId++;
      $scope.$apply(function () {
        refreshChart();
      });
    });

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
      if (plotTransactionName) {
        text += '<strong>' + plotTransactionName + '</strong><br>';
      } else {
        text += '<strong>All Transactions</strong><br>';
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

    $scope.viewTransactionDetail = function () {
      var transactionQuery = {
        from: $scope.chartFrom,
        to: $scope.chartTo,
        transactionName: $scope.selectedTransactionName
      };
      $modal.open({
        templateUrl: 'views/transaction-detail.html',
        controller: 'TransactionDetailCtrl',
        windowClass: 'full-screen-modal',
        resolve: {
          transactionQuery: function() {
            return transactionQuery;
          }
        }
      });
    };

    $scope.showTransactionTableOverlay = 0;
    $scope.showTransactionTableSpinner = 0;

    function updateTransactions(buttonDeferred) {
      var query = {
        from: $scope.chartFrom,
        to: $scope.chartTo,
        sortAttribute: $scope.tableSortAttribute,
        sortDirection: $scope.tableSortDirection,
        // +1 just to find out if there are more and to show "Show more" button, the +1 will not be displayed
        limit: transactionLimit + 1
      };
      $scope.showTransactionTableOverlay++;
      if (!buttonDeferred) {
        // show table spinner if not triggered from refresh button or show more button
        $scope.showTransactionTableSpinner++;
      }
      $http.get('backend/home/transactions?' + queryStrings.encodeObject(query))
          .success(function (data) {
            $scope.showTransactionTableOverlay--;
            if (!buttonDeferred) {
              $scope.showTransactionTableSpinner--;
            }
            $scope.transactionTableError = false;
            $scope.overall = data.overall;
            if (data.transactions.length === query.limit) {
              data.transactions.pop();
              $scope.hasMoreTransactions = true;
            } else {
              $scope.hasMoreTransactions = false;
            }
            $scope.transactions = data.transactions;
            maxTransactionTotalMicros = 0;
            angular.forEach($scope.transactions, function (transaction) {
              maxTransactionTotalMicros =
                  Math.max(maxTransactionTotalMicros, transaction.totalMicros);
            });
            if (buttonDeferred) {
              buttonDeferred.resolve();
            }
          })
          .error(function (data, status) {
            $scope.showTransactionTableOverlay--;
            if (!buttonDeferred) {
              $scope.showTransactionTableSpinner--;
            }
            if (status === 0) {
              $scope.transactionTableError = 'Unable to connect to server';
            } else {
              $scope.transactionTableError = 'An error occurred';
            }
            if (buttonDeferred) {
              buttonDeferred.reject($scope.transactionTableError);
            }
          });
    }

    $scope.overallAverage = function () {
      if (!$scope.overall) {
        // overall hasn't loaded yet
        return '';
      } else if ($scope.overall.count) {
        return (($scope.overall.totalMicros / $scope.overall.count) / 1000000).toFixed(2);
      } else {
        return '-';
      }
    };

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
      updateTransactions();
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
          // from is adjusted because transactions are really aggregates of the interval before the transaction point
          from: $scope.chartFrom - fixedAggregationIntervalMillis,
          to: $scope.chartTo,
          transactionName: transactionName,
          transactionNameComparator: 'equals',
          background: 'false'
        });
      } else {
        return queryStrings.encodeObject({
          // from is adjusted because transactions are really aggregates of the interval before the transaction point
          from: $scope.chartFrom - fixedAggregationIntervalMillis,
          to: $scope.chartTo,
          background: 'false'
        });
      }
    };

    $scope.showMoreTransactions = function (deferred) {
      // double each time
      transactionLimit *= 2;
      updateTransactions(deferred);
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

    $scope.clickTransactionName = function (transactionName) {
      $scope.selectedTransactionName = transactionName;
      updateLocation();
      refreshChart(undefined, true);
    };

    $scope.transactionBarWidth = function (totalMicros) {
      return (totalMicros / maxTransactionTotalMicros) * 100 + '%';
    };

    // TODO CONVERT TO ANGULARJS, global $http error handler?
    Glowroot.configureAjaxError();

    $('#zoomOut').click(function () {
      plot.zoomOut();
    });

    var chartFromToDefault;

    $scope.filter = {};
    $scope.chartFrom = Number($location.search().from);
    $scope.chartTo = Number($location.search().to);
    // both from and to must be supplied or neither will take effect
    if ($scope.chartFrom && $scope.chartTo) {
      $scope.chartFrom += fixedAggregationIntervalMillis;
      $scope.filterDate = new Date($scope.chartFrom);
      $scope.filterDate.setHours(0, 0, 0, 0);
    } else {
      chartFromToDefault = true;
      var today = new Date();
      today.setHours(0, 0, 0, 0);
      $scope.filterDate = today;
      // show 2 hour interval, but nothing prior to today (e.g. if 'now' is 1am) or after today
      // (e.g. if 'now' is 11:55pm)
      var now = new Date();
      now.setSeconds(0, 0);
      var fixedAggregationIntervalMinutes = fixedAggregationIntervalMillis / (60 * 1000);
      if (fixedAggregationIntervalMinutes > 1) {
        // this is the normal case since default aggregation interval is 5 min
        var minutesRoundedDownToNearestAggregationInterval =
            fixedAggregationIntervalMinutes * Math.floor(now.getMinutes() / fixedAggregationIntervalMinutes);
        now.setMinutes(minutesRoundedDownToNearestAggregationInterval);
      }
      $scope.chartFrom = Math.max(now.getTime() - 105 * 60 * 1000, today.getTime());
      $scope.chartTo = Math.min($scope.chartFrom + 120 * 60 * 1000, today.getTime() + 24 * 60 * 60 * 1000);
    }
    $scope.tableSortAttribute = $location.search()['table-sort-attribute'] || 'total';
    $scope.tableSortDirection = $location.search()['table-sort-direction'] || 'desc';

    $scope.selectedTransactionName = $location.search()['transaction-name'];

    function updateLocation() {
      var query = {};
      if (!chartFromToDefault) {
        query.from = $scope.chartFrom - fixedAggregationIntervalMillis;
        query.to = $scope.chartTo;
      }
      if ($scope.selectedTransactionName) {
        query['transaction-name'] = $scope.selectedTransactionName;
      }
      if ($scope.tableSortAttribute !== 'total' || $scope.tableSortDirection !== 'desc') {
        query['table-sort-attribute'] = $scope.tableSortAttribute;
        if ($scope.tableSortDirection !== 'desc') {
          query['table-sort-direction'] = $scope.tableSortDirection;
        }
      }
      $location.search(query).replace();
    }

    (function () {
      var options = {
        grid: {
          hoverable: true,
          mouseActiveRadius: 10,
          // min border margin should match trace chart so they are positioned the same from the top of page
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
          min: $scope.chartFrom,
          max: $scope.chartTo,
          absoluteZoomRange: true,
          zoomRange: [
            $scope.filterDate.getTime(),
                $scope.filterDate.getTime() + 24 * 60 * 60 * 1000
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
          stack: true,
          lines: {
            show: true,
            fill: true
          }
        }
      };
      // render chart with no data points
      plot = $.plot($chart, [], options);
      plot.getAxes().xaxis.options.borderGridLock = fixedAggregationIntervalMillis;
    })();

    plot.getAxes().yaxis.options.max = undefined;
    refreshChart();
  }
]);
