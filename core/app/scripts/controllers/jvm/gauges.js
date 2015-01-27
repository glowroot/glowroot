/*
 * Copyright 2014-2015 the original author or authors.
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

/* global glowroot, angular, $, moment */

glowroot.controller('JvmGaugesCtrl', [
  '$scope',
  '$location',
  '$filter',
  '$http',
  '$timeout',
  'keyedColorPools',
  'queryStrings',
  'httpErrors',
  function ($scope, $location, $filter, $http, $timeout, keyedColorPools, queryStrings, httpErrors) {

    var plot;
    var plotGaugeNames;

    var fixedGaugeIntervalMillis = $scope.layout.fixedGaugeIntervalSeconds * 1000;
    var fixedGaugeRollupMillis = $scope.layout.fixedGaugeRollupSeconds * 1000;

    var currentRefreshId = 0;
    var currentZoomId = 0;
    var currentRollupLevel;

    var $chart = $('#chart');

    var keyedColorPool = keyedColorPools.create();

    var chartFromToDefault;

    $scope.keyedColorPool = keyedColorPool;

    $scope.$watchGroup(['containerWidth', 'windowHeight'], function () {
      plot.resize();
      plot.setupGrid();
      plot.draw();
    });

    $scope.showChartSpinner = 0;

    $http.get('backend/jvm/all-gauge-names')
        .success(function (data) {
          $scope.loaded = true;
          $scope.allGaugeNames = data;
          $scope.allShortGaugeNames = createShortGaugeNames($scope.allGaugeNames);

          var gaugeNames = $location.search()['gauge-name'];
          if (!gaugeNames) {
            gaugeNames = [];
            if ($scope.allGaugeNames.indexOf('java.lang/Memory/HeapMemoryUsage.used') !== -1) {
              gaugeNames.push('java.lang/Memory/HeapMemoryUsage.used');
            }
            if ($scope.allGaugeNames.indexOf('java.lang/MemoryPool/PS Old Gen/Usage.used') !== -1) {
              gaugeNames.push('java.lang/MemoryPool/PS Old Gen/Usage.used');
            }
          }
          if (angular.isArray(gaugeNames)) {
            angular.forEach(gaugeNames, function (gaugeName) {
              if ($scope.allGaugeNames.indexOf(gaugeName) !== -1) {
                keyedColorPool.add(gaugeName);
              }
            });
          } else if (gaugeNames && $scope.allGaugeNames.indexOf(gaugeNames) !== -1) {
            keyedColorPool.add(gaugeNames);
          }
          if (keyedColorPool.keys().length) {
            refreshChart();
          } else {
            $scope.chartNoData = true;
          }
        })
        .error(httpErrors.handler($scope));

    function createShortGaugeNames(gaugeNames) {
      var splitGaugeNames = [];
      angular.forEach(gaugeNames, function (gaugeName) {
        splitGaugeNames.push(gaugeName.split('/'));
      });
      var minRequiredForUniqueName;
      var shortNames = {};
      var i, j;
      for (i = 0; i < gaugeNames.length; i++) {
        var splitGaugeName = splitGaugeNames[i];
        minRequiredForUniqueName = 1;
        for (j = 0; j < gaugeNames.length; j++) {
          if (j === i) {
            continue;
          }
          var splitGaugeName2 = splitGaugeNames[j];
          minRequiredForUniqueName = Math.max(minRequiredForUniqueName,
              numSamePartsStartingAtEnd(splitGaugeName, splitGaugeName2) + 1);
        }
        shortNames[gaugeNames[i]] = splitGaugeName.slice(-minRequiredForUniqueName).join('/');
      }
      return shortNames;
    }

    function numSamePartsStartingAtEnd(array1, array2) {
      var k = 0;
      var len1 = array1.length;
      var len2 = array2.length;
      while (k < Math.min(len1, len2) && array1[len1 - 1 - k] === array2[len2 - 1 - k]) {
        k++;
      }
      return k;
    }

    function newRollupLevel(from, to) {
      // over 1 hour rendered at fine-grained detail looks very jumpy
      if (to - from <= 3600 * 1000) {
        return 0;
      } else {
        return 1;
      }
    }

    function refreshChart(deferred) {
      var date = $scope.filterDate;
      var refreshId = ++currentRefreshId;
      var chartFrom = $scope.chartFrom;
      var chartTo = $scope.chartTo;
      var plusMinus;
      var rollupLevel = newRollupLevel(chartFrom, chartTo);
      if (rollupLevel === 0) {
        plusMinus = fixedGaugeIntervalMillis;
      } else {
        plusMinus = fixedGaugeRollupMillis;
      }
      var query = {
        from: chartFrom - plusMinus,
        to: chartTo + plusMinus,
        gaugeNames: keyedColorPool.keys(),
        rollupLevel: rollupLevel
      };
      $scope.showChartSpinner++;
      $http.get('backend/jvm/gauge-points' + queryStrings.encodeObject(query))
          .success(function (data) {
            $scope.showChartSpinner--;
            if (refreshId !== currentRefreshId) {
              return;
            }
            // reset axis in case user changed the date and then zoomed in/out to trigger this refresh
            plot.getAxes().xaxis.options.min = chartFrom;
            plot.getAxes().xaxis.options.max = chartTo;
            plot.getAxes().xaxis.options.zoomRange = [
              date.getTime(),
              date.getTime() + 24 * 60 * 60 * 1000
            ];
            if (rollupLevel === 0) {
              plot.getAxes().xaxis.options.borderGridLock = fixedGaugeIntervalMillis;
            } else {
              plot.getAxes().xaxis.options.borderGridLock = fixedGaugeRollupMillis;
            }
            var plotData = data;
            plotGaugeNames = [];
            angular.forEach(query.gaugeNames, function (gaugeName) {
              plotGaugeNames.push(gaugeName);
            });
            updatePlotData(plotData);
            currentRollupLevel = rollupLevel;
            if (deferred) {
              deferred.resolve('Success');
            }
          })
          .error(function (data, status) {
            $scope.showChartSpinner--;
            httpErrors.handler($scope, deferred)(data, status);
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
      refreshChart(deferred);
      updateLocation();
    };

    function updatePlotData(data) {
      var plotData = [];
      var nodata = true;
      // using plotGaugeNames.length since data.length is 1 for dummy data [[]] which is needed for flot to draw
      // gridlines
      if (plotGaugeNames && plotGaugeNames.length) {
        for (var i = 0; i < plotGaugeNames.length; i++) {
          if (nodata) {
            nodata = !data[i].length;
          }
          plotData.push({
            data: data[i],
            color: keyedColorPool.get(plotGaugeNames[i]),
            label: plotGaugeNames[i]
          });
        }
        $scope.chartNoData = nodata;
      } else {
        $scope.chartNoData = true;
      }
      if (plotData.length) {
        plot.setData(plotData);
      } else {
        plot.setData([
          []
        ]);
      }
      plot.setupGrid();
      plot.draw();
    }

    $chart.bind('plotzoom', function (event, plot, args) {
      $scope.$apply(function () {
        // throw up spinner right away
        $scope.showChartSpinner++;
        $scope.showTableOverlay++;
        // need to call setupGrid on each zoom to handle rapid zooming
        plot.setupGrid();
        var zoomingOut = args.amount && args.amount < 1;
        var zoomingInNewRollup =
            (currentRollupLevel !== newRollupLevel(plot.getAxes().xaxis.options.min, plot.getAxes().xaxis.options.max));
        if (zoomingOut || zoomingInNewRollup) {
          // need to clear y-axis on zooming in with new rollup also since could be fine-grained outliers
          plot.getAxes().yaxis.options.min = 0;
          plot.getAxes().yaxis.options.realMax = undefined;
        }
        if (zoomingOut || zoomingInNewRollup) {
          var zoomId = ++currentZoomId;
          // use 100 millisecond delay to handle rapid zooming
          $timeout(function () {
            if (zoomId === currentZoomId) {
              $scope.chartFrom = plot.getAxes().xaxis.min;
              $scope.chartTo = plot.getAxes().xaxis.max;
              chartFromToDefault = false;
              refreshChart();
              updateLocation();
            }
            $scope.showChartSpinner--;
            $scope.showTableOverlay--;
          }, 100);
        } else {
          // no need to fetch new data
          $scope.chartFrom = plot.getAxes().xaxis.min;
          $scope.chartTo = plot.getAxes().xaxis.max;
          chartFromToDefault = false;
          updatePlotData(getFilteredData());
          updateLocation();
          // increment currentRefreshId to cancel any refresh in action
          currentRefreshId++;
          $scope.showChartSpinner--;
          $scope.showTableOverlay--;
        }
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
        // increment currentRefreshId to cancel any refresh in action
        currentRefreshId++;
        var zoomingInNewRollup =
            (currentRollupLevel !== newRollupLevel(plot.getAxes().xaxis.options.min, plot.getAxes().xaxis.options.max));
        if (zoomingInNewRollup) {
          refreshChart();
        } else {
          // no need to fetch new data
          updatePlotData(getFilteredData());
        }
      });
    });

    function getFilteredData() {
      var from = plot.getAxes().xaxis.options.min;
      var to = plot.getAxes().xaxis.options.max;
      var data = plot.getData();
      var filteredData = [];
      var i;
      for (i = 0; i < data.length; i++) {
        filteredData.push(getFilteredPoints(data[i].data, from, to));
      }
      return filteredData;
    }

    function getFilteredPoints(points, from, to) {
      var lastPoint = null;
      var lastPointInRange = false;
      var filteredPoints = [];
      var j;
      for (j = 0; j < points.length; j++) {
        var currPoint = points[j];
        if (currPoint) {
          var currPointInRange = currPoint[0] >= from && currPoint[0] <= to;
          if (!lastPointInRange && currPointInRange) {
            // add one extra so partial line slope to point off the chart will be displayed
            filteredPoints.push(lastPoint);
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
        } else {
          // point can be undefined which is used to represent no data collected in that period
          if (lastPointInRange) {
            filteredPoints.push(currPoint);
          }
          lastPoint = currPoint;
        }
      }
      return filteredPoints;
    }

    $scope.gaugeRowStyle = function (gaugeName) {
      var color = keyedColorPool.get(gaugeName);
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
      var color = keyedColorPool.get(gaugeName);
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
      var color = keyedColorPool.get(gaugeName);
      if (color) {
        // uncheck it
        keyedColorPool.remove(gaugeName);
      } else {
        // check it
        color = keyedColorPool.add(gaugeName);
      }
      refreshChart();
      updateLocation();
    };

    $scope.removeDisplayedGauge = function (gaugeName) {
      keyedColorPool.remove(gaugeName);
      refreshChart();
      updateLocation();
    };

    $scope.selectAllGauges = function () {
      var gaugeNames = $filter('filter')($scope.allGaugeNames, $scope.gaugeNameFilter);
      angular.forEach(gaugeNames, function (gaugeName) {
        keyedColorPool.add(gaugeName);
      });
      refreshChart();
      updateLocation();
    };

    $scope.deselectAllGauges = function () {
      var gaugeNames = $filter('filter')($scope.allGaugeNames, $scope.gaugeNameFilter);
      angular.forEach(gaugeNames, function (gaugeName) {
        keyedColorPool.remove(gaugeName);
      });
      refreshChart();
      updateLocation();
    };

    $scope.zoomOut = function () {
      // need to execute this outside of $apply since code assumes it needs to do its own $apply
      $timeout(function () {
        plot.zoomOut();
      });
    };

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

    function updateLocation() {
      var query = {};
      if (!chartFromToDefault) {
        query.from = $scope.chartFrom;
        query.to = $scope.chartTo;
      }
      query['gauge-name'] = keyedColorPool.keys();
      $location.search(query).replace();
    }

    (function () {
      var options = {
        grid: {
          mouseActiveRadius: 10,
          // without specifying min border margin, the point radius is used
          minBorderMargin: 0,
          borderColor: '#7d7358',
          borderWidth: 1,
          // this is needed for tooltip plugin to work
          hoverable: true
        },
        legend: {
          show: false
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
          content: function (label, xval, yval) {
            var shortLabel = $scope.allShortGaugeNames[label];
            return moment(xval).format('h:mm:ss.SSS a (Z)') + '<br>' + shortLabel + ': ' + yval;
          }
        }
      };
      // render chart with no data points
      plot = $.plot($chart, [[]], options);
      plot.getAxes().xaxis.options.borderGridLock = fixedGaugeIntervalMillis;
    })();

    plot.getAxes().yaxis.options.max = undefined;
  }
]);
