/*
 * Copyright 2014 the original author or authors.
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

/* global glowroot, angular, $, RColor, moment */

glowroot.controller('JvmGaugesCtrl', [
  '$scope',
  '$location',
  '$filter',
  '$http',
  '$timeout',
  'traceModal',
  'queryStrings',
  function ($scope, $location, $filter, $http, $timeout, traceModal, queryStrings) {

    var plot;
    var plotColors;
    var plotLabels;

    var fixedGaugeIntervalMillis = $scope.layout.fixedGaugeIntervalSeconds * 1000;

    var currentRefreshId = 0;
    var currentZoomId = 0;

    var $chart = $('#chart');

    var rcolor = new RColor();
    // RColor evenly distributes colors, but largely separated calls can have similar colors
    // so availableColors is used to store returned colors to keep down the number of calls to RColor
    // and to keep the used colors well distributed
    var availableColors = [];

    function nextRColor() {
      return rcolor.get(true, 0.5, 0.5);
    }

    $scope.$watchCollection('[containerWidth, windowHeight]', function () {
      plot.resize();
      plot.setupGrid();
      plot.draw();
    });

    $scope.showChartSpinner = 0;

    $scope.tableLoaded = false;

    $http.get('backend/jvm/all-gauge-names')
        .success(function (data) {
          $scope.tableLoaded = true;
          $scope.allGaugeNames = data;
        })
        .error(function (data, status) {
          // TODO
        });

    function smallestUniqueGaugeNames(gaugeNames) {
      var splitGaugeNames = [];
      angular.forEach(gaugeNames, function (gaugeName) {
        splitGaugeNames.push(gaugeName.split('/'));
      });
      var minRequiredForUniqueName;
      var uniqueNames = [];
      for (var i = 0; i < gaugeNames.length; i++) {
        var si = splitGaugeNames[i];
        minRequiredForUniqueName = 1;
        for (var j = 0; j < gaugeNames.length; j++) {
          if (j === i) {
            continue;
          }
          var sj = splitGaugeNames[j];
          for (var k = 1; k <= Math.min(si.length, sj.length); k++) {
            if (si[si.length - k] !== sj[sj.length - k]) {
              break;
            }
            minRequiredForUniqueName = Math.max(minRequiredForUniqueName, k + 1);
          }
        }
        uniqueNames.push(si.slice(-minRequiredForUniqueName).join('/'));
      }
      return uniqueNames;
    }

    function refreshChart(deferred) {
      var date = $scope.filterDate;
      var refreshId = ++currentRefreshId;
      var gaugeNames = [];
      angular.forEach($scope.checkedGaugeColors, function (color, gaugeName) {
        gaugeNames.push(gaugeName);
      });
      var chartFrom = $scope.chartFrom;
      var chartTo = $scope.chartTo;
      var query = {
        from: chartFrom - fixedGaugeIntervalMillis,
        to: chartTo + fixedGaugeIntervalMillis,
        gaugeNames: gaugeNames
      };
      $scope.showChartSpinner++;
      $http.get('backend/jvm/gauge-points?' + queryStrings.encodeObject(query))
          .success(function (data) {
            $scope.showChartSpinner--;
            if (refreshId !== currentRefreshId) {
              return;
            }
            $scope.refreshChartError = false;
            // reset axis in case user changed the date and then zoomed in/out to trigger this refresh
            plot.getAxes().xaxis.options.min = chartFrom;
            plot.getAxes().xaxis.options.max = chartTo;
            plot.getAxes().xaxis.options.zoomRange = [
              date.getTime(),
                  date.getTime() + 24 * 60 * 60 * 1000
            ];
            var plotData = data;
            plotColors = [];
            angular.forEach(query.gaugeNames, function (gaugeName) {
              plotColors.push($scope.checkedGaugeColors[gaugeName]);
            });
            plotLabels = smallestUniqueGaugeNames(query.gaugeNames);
            updatePlotData(plotData);
            if (deferred) {
              deferred.resolve('Success');
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
            if (deferred) {
              deferred.reject($scope.refreshChartError);
            }
          });
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

    function updatePlotData(data) {
      var plotData = [];
      for (var i = 0; i < data.length; i++) {
        plotData.push({
          data: data[i],
          color: plotColors[i],
          label: plotLabels[i]
        });
      }
      plot.setData(plotData);
      plot.setupGrid();
      plot.draw();
    }

    $chart.bind('plotzoom', function (event, plot, args) {
      var zoomingOut = args.amount && args.amount < 1;
      updatePlotData(getFilteredData());
      $scope.$apply(function () {
        $scope.chartFrom = plot.getAxes().xaxis.min;
        $scope.chartTo = plot.getAxes().xaxis.max;
        chartFromToDefault = false;
        updateLocation();
      });
      if (zoomingOut) {
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
      } else {
        // no need to fetch new data
        // increment currentRefreshId to cancel any refresh in action
        currentRefreshId++;
      }
    });

    $chart.bind('plotselected', function (event, ranges) {
      plot.clearSelection();
      // perform the zoom
      plot.getAxes().xaxis.options.min = ranges.xaxis.from;
      plot.getAxes().xaxis.options.max = ranges.xaxis.to;
      updatePlotData(getFilteredData());
      $scope.$apply(function () {
        $scope.chartFrom = plot.getAxes().xaxis.min;
        $scope.chartTo = plot.getAxes().xaxis.max;
        chartFromToDefault = false;
        updateLocation();
      });
      // no need to fetch new data
      // increment currentRefreshId to cancel any refresh in action
      currentRefreshId++;
    });

    function getFilteredData() {
      var from = plot.getAxes().xaxis.options.min;
      var to = plot.getAxes().xaxis.options.max;
      var i, j;
      var filteredData = [];
      var data = plot.getData();
      for (i = 0; i < data.length; i++) {
        var filteredPoints = [];
        var points = data[i].data;
        var lastPoint = null;
        var lastPointInRange = false;
        for (j = 0; j < points.length; j++) {
          var currPoint = points[j];
          if (!currPoint) {
            // point can be undefined which is used to represent no data collected in that period
            if (lastPointInRange) {
              filteredPoints.push(currPoint);
            }
            lastPoint = currPoint;
            continue;
          }
          var currPointInRange = currPoint[0] >= from && currPoint[0] <= to;
          if (!lastPointInRange && currPointInRange) {
            // add one extra so partial line slope to point off the chart will be displayed
            filteredPoints.push(lastPoint);
            filteredPoints.push(currPoint);
          } else if (lastPointInRange && !currPointInRange) {
            // add one extra so partial line slope to point off the chart will be displayed
            filteredPoints.push(currPoint);
            // past the end, no other data points need to be checked
            break;
          }
          if (currPointInRange) {
            filteredPoints.push(currPoint);
          }
          lastPoint = currPoint;
          lastPointInRange = currPointInRange;
        }
        filteredData.push(filteredPoints);
      }
      return filteredData;
    }

    $scope.showTableOverlay = 0;
    $scope.showTableSpinner = 0;

    $scope.gaugeRowStyle = function (gaugeName) {
      var color = $scope.checkedGaugeColors[gaugeName];
      if (color) {
        return {
          color: color,
          'font-weight': 'bold'
        };
      } else {
        return {
          'font-weight': 'normal'
        };
      }
    };

    $scope.displayFilterRowStyle = function (gaugeName) {
      var color = $scope.checkedGaugeColors[gaugeName];
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

    $scope.clickGaugeName = function (gaugeName) {
      var color = $scope.checkedGaugeColors[gaugeName];
      if (color) {
        // uncheck it
        availableColors.push(color);
        delete $scope.checkedGaugeColors[gaugeName];
      } else {
        // check it
        color = availableColors.length ? availableColors.pop() : nextRColor();
        $scope.checkedGaugeColors[gaugeName] = color;
      }
      refreshChart();
    };

    $scope.removeDisplayedGauge = function (gaugeName) {
      var color = $scope.checkedGaugeColors[gaugeName];
      availableColors.push(color);
      delete $scope.checkedGaugeColors[gaugeName];
      refreshChart();
    };

    $('#zoomOut').click(function () {
      plot.zoomOut();
    });
    $('#modalHide').click(traceModal.hideModal);

    var chartFromToDefault;

    $scope.filter = {};
    $scope.chartFrom = Number($location.search().from);
    $scope.chartTo = Number($location.search().to);
    // both from and to must be supplied or neither will take effect
    if ($scope.chartFrom && $scope.chartTo) {
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
      $scope.chartFrom = Math.max(now.getTime() - 105 * 60 * 1000, today.getTime());
      $scope.chartTo = Math.min($scope.chartFrom + 120 * 60 * 1000, today.getTime() + 24 * 60 * 60 * 1000);
    }

    $scope.checkedGaugeColors = {};
    var gaugeNames = $location.search()['gauge-name'];
    $scope.gaugeNames = [];
    if (angular.isArray(gaugeNames)) {
      angular.forEach(gaugeNames, function (gaugeName) {
        $scope.checkedGaugeColors[gaugeName] = nextRColor();
        $scope.gaugeNames.push(gaugeName);
      });
    } else if (gaugeNames) {
      $scope.checkedGaugeColors[gaugeNames] = nextRColor();
      $scope.gaugeNames.push(gaugeNames);
    }

    function updateLocation() {
      var gaugeNames = [];
      angular.forEach($scope.checkedGaugeColors, function (color, gaugeName) {
        gaugeNames.push(gaugeName);
      });
      var query = {};
      if (!chartFromToDefault) {
        query.from = $scope.chartFrom;
        query.to = $scope.chartTo;
      }
      if (gaugeNames) {
        query['gauge-name'] = gaugeNames;
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
          mouseActiveRadius: 10,
          // min border margin should match aggregate chart so they are positioned the same from the top of page
          // without specifying min border margin, the point radius is used
          minBorderMargin: 10,
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
          // 10 second yaxis max just for initial empty chart rendering
          max: 10
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
          lines: {
            show: true
          },
          points: {
            radius: 6
          }
        },
        tooltip: true,
        tooltipOpts: {
          content: function (label, xval, yval, flotItem) {
            // TODO internationalize time format
            var index = label.lastIndexOf('/');
            return moment(xval).format('h:mm:ss.SSS a (Z)') + '<br>' + label.substring(index + 1) + ': ' + yval;
          }
        }
      };
      // render chart with no data points
      plot = $.plot($chart, [], options);
      plot.getAxes().xaxis.options.borderGridLock = 1;
    })();

    plot.getAxes().yaxis.options.max = undefined;
    refreshChart();
  }
]);
