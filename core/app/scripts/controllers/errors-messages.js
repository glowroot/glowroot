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

/* global glowroot, $ */

glowroot.controller('ErrorsMessagesCtrl', [
  '$scope',
  '$location',
  '$filter',
  '$http',
  '$q',
  '$timeout',
  'httpErrors',
  'queryStrings',
  function ($scope, $location, $filter, $http, $q, $timeout, httpErrors, queryStrings) {
    // \u00b7 is &middot;
    document.title = 'Errors \u00b7 Glowroot';
    $scope.$parent.activeNavbarItem = 'errors';

    var plot;

    var fixedAggregateIntervalMillis = $scope.layout.fixedAggregateIntervalSeconds * 1000;

    var currentRefreshId = 0;
    var currentZoomId = 0;

    var $chart = $('#chart');

    var summaryLimit = 100;

    var dataSeriesExtra;

    var chartFromToDefault;

    $scope.$watchCollection('[containerWidth, windowHeight]', function () {
      plot.resize();
      plot.setupGrid();
      plot.draw();
    });

    $scope.showChartSpinner = 0;
    $scope.showTableOverlay = 0;

    $scope.tracesQueryString = function (errorMessage) {
      var query = {
        transactionType: $scope.transactionType,
        transactionName: $scope.transactionName,
        transactionNameComparator: 'equals',
        from: $scope.chartFrom,
        to: $scope.chartTo,
        errorOnly: true
      };
      if (errorMessage.message.length <= 1000) {
        query.error = errorMessage.message;
        query.errorComparator = 'equals';
      } else {
        // this keeps url length under control
        query.error = errorMessage.message.substring(0, 1000);
        query.errorComparator = 'begins';
      }
      return queryStrings.encodeObject(query);
    };

    function refreshData(deferred) {
      var date = $scope.filterDate;
      var refreshId = ++currentRefreshId;
      var query = {
        from: $scope.chartFrom,
        to: $scope.chartTo,
        transactionType: $scope.transactionType,
        transactionName: $scope.transactionName,
        includes: $scope.errorFilterIncludes,
        excludes: $scope.errorFilterExcludes,
        limit: summaryLimit
      };
      $scope.showChartSpinner++;
      $scope.showTableOverlay++;
      $http.get('backend/error/messages?' + queryStrings.encodeObject(query))
          .success(function (data) {
            $scope.showChartSpinner--;
            $scope.showTableOverlay--;
            if (refreshId !== currentRefreshId) {
              return;
            }
            $scope.chartNoData = !data.dataSeries.data.length;
            // reset axis in case user changed the date and then zoomed in/out to trigger this refresh
            plot.getAxes().xaxis.options.min = query.from;
            plot.getAxes().xaxis.options.max = query.to;
            plot.getAxes().xaxis.options.zoomRange = [
              date.getTime(),
                  date.getTime() + 24 * 60 * 60 * 1000
            ];
            if (data.dataSeries.data.length) {
              plot.setData([
                {
                  data: data.dataSeries.data
                }
              ]);
            } else {
              plot.setData([
                []
              ]);
            }
            plot.setupGrid();
            plot.draw();
            dataSeriesExtra = data.dataSeriesExtra;

            $scope.moreAvailable = data.moreAvailable;
            $scope.errorMessages = data.errorMessages;
            $scope.traceCount = data.traceCount;
            if (deferred) {
              deferred.resolve('Success');
            }
          })
          .error(function (data, status) {
            $scope.showChartSpinner--;
            $scope.showTableOverlay--;
            if (refreshId !== currentRefreshId) {
              return;
            }
            httpErrors.handler($scope, deferred)(data, status);
          });
    }

    $scope.refreshButtonClick = function (deferred) {
      $scope.parsingError = undefined;
      parseQuery($scope.errorFilter || '');
      if ($scope.parsingError) {
        deferred.reject($scope.parsingError);
        return;
      }
      updateLocation();
      refreshData(deferred);
    };

    $chart.bind('plotzoom', function (event, plot, args) {
      $scope.$apply(function () {
        // throw up spinner right away
        $scope.showChartSpinner++;
        $scope.showTableOverlay++;
        // need to call setupGrid on each zoom to handle rapid zooming
        plot.setupGrid();
        var zoomId = ++currentZoomId;
        // use 100 millisecond delay to handle rapid zooming
        $timeout(function () {
          if (zoomId === currentZoomId) {
            $scope.chartFrom = plot.getAxes().xaxis.min;
            $scope.chartTo = plot.getAxes().xaxis.max;
            chartFromToDefault = false;
            updateLocation();
            refreshData();
          }
          $scope.showChartSpinner--;
          $scope.showTableOverlay--;
        }, 100);
      });
    });

    $chart.bind('plotselected', function (event, ranges) {
      $scope.$apply(function () {
        plot.clearSelection();
        // perform the zoom
        plot.getAxes().xaxis.options.min = ranges.xaxis.from;
        plot.getAxes().xaxis.options.max = ranges.xaxis.to;
        plot.setupGrid();
        $scope.chartFrom = plot.getAxes().xaxis.min;
        $scope.chartTo = plot.getAxes().xaxis.max;
        chartFromToDefault = false;
        updateLocation();
        currentRefreshId++;
        refreshData();
      });
    });

    $scope.zoomOut = function () {
      // need to execute this outside of $apply since code assumes it needs to do its own $apply
      $timeout(function () {
        plot.zoomOut();
      });
    };

    function parseQuery(text) {
      var includes = [];
      var excludes = [];
      var i;
      var c;
      var currTerm;
      var inQuote;
      var inExclude;
      for (i = 0; i < text.length; i++) {
        c = text.charAt(i);
        if (currTerm !== undefined) {
          // inside quoted or non-quoted term
          if (c === inQuote || !inQuote && c === ' ') {
            // end of term (quoted or non-quoted)
            if (inExclude) {
              excludes.push(currTerm);
            } else {
              includes.push(currTerm);
            }
            currTerm = undefined;
            inQuote = undefined;
            inExclude = false;
          } else if (!inQuote && (c === '\'' || c === '"')) {
            $scope.parsingError = 'Mismatched quote';
            return;
          } else {
            currTerm += c;
          }
        } else if (c === '\'' || c === '"') {
          // start of quoted term
          currTerm = '';
          inQuote = c;
        } else if (c === '-') {
          // validate there is an immediate next term
          if (i === text.length - 1 || text.charAt(i + 1) === ' ') {
            $scope.parsingError = 'Invalid location for minus';
          }
          // next term is an exclude
          inExclude = true;
        } else if (c !== ' ') {
          // start of non-quoted term
          currTerm = c;
        }
      }
      if (inQuote) {
        $scope.parsingError = 'Mismatched quote';
        return;
      }
      if (currTerm) {
        // end the last non-quoted term
        if (inExclude) {
          excludes.push(currTerm);
        } else {
          includes.push(currTerm);
        }
      }
      $scope.errorFilterIncludes = includes;
      $scope.errorFilterExcludes = excludes;
    }

    $scope.filter = {};
    $scope.chartFrom = Number($location.search().from);
    $scope.chartTo = Number($location.search().to);
    // both from and to must be supplied or neither will take effect
    if ($scope.chartFrom && $scope.chartTo) {
      $scope.chartFrom += fixedAggregateIntervalMillis;
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
      var fixedAggregateIntervalMinutes = fixedAggregateIntervalMillis / (60 * 1000);
      if (fixedAggregateIntervalMinutes > 1) {
        // this is the normal case since default aggregate interval is 5 min
        var minutesRoundedDownToNearestAggregationInterval =
            fixedAggregateIntervalMinutes * Math.floor(now.getMinutes() / fixedAggregateIntervalMinutes);
        now.setMinutes(minutesRoundedDownToNearestAggregationInterval);
      }
      $scope.chartFrom = Math.max(now.getTime() - 105 * 60 * 1000, today.getTime());
      $scope.chartTo = Math.min($scope.chartFrom + 120 * 60 * 1000, today.getTime() + 24 * 60 * 60 * 1000);
    }
    $scope.transactionType = $location.search()['transaction-type'];
    $scope.transactionName = $location.search()['transaction-name'];

    function updateLocation() {
      var query = {
        'transaction-type': $scope.transactionType,
        'transaction-name': $scope.transactionName
      };
      if (!chartFromToDefault) {
        query.from = $scope.chartFrom - fixedAggregateIntervalMillis;
        query.to = $scope.chartTo;
      }
      $location.search(query).replace();
    }

    (function () {
      var options = {
        grid: {
          // min border margin should match trace chart so they are positioned the same from the top of page
          // without specifying min border margin, the point radius is used
          minBorderMargin: 0,
          borderColor: '#7d7358',
          borderWidth: 1,
          // this is needed for tooltip plugin to work
          hoverable: true
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
          ],
          reserveSpace: false
        },
        yaxis: {
          ticks: 10,
          zoomRange: false,
          min: 0,
          // 100% yaxis max just for initial empty chart rendering
          max: 100,
          label: 'error percentage'
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
          },
          points: {
            radius: 8
          }
        },
        tooltip: true,
        tooltipOpts: {
          content: function (label, xval, yval, flotItem) {
            if (yval === 0 && !dataSeriesExtra[xval]) {
              // this is synthetic point for initial upslope, gap or final downslope
              return 'No errors';
            }
            return 'Error percentage: ' + yval.toFixed(1) + '<br>Error count: ' + dataSeriesExtra[xval][0] +
                '<br>Transaction count: ' + dataSeriesExtra[xval][1];
          }
        }
      };
      // render chart with no data points
      plot = $.plot($chart, [
        []
      ], options);
      plot.getAxes().xaxis.options.borderGridLock = fixedAggregateIntervalMillis;
    })();

    plot.getAxes().yaxis.options.max = undefined;
    refreshData();
  }
]);
