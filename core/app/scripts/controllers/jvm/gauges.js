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
  'charts',
  'keyedColorPools',
  'queryStrings',
  'httpErrors',
  function ($scope, $location, $filter, $http, $timeout, charts, keyedColorPools, queryStrings, httpErrors) {

    var plot;
    var plotGaugeNames;

    var fixedGaugeIntervalMillis = $scope.layout.fixedGaugeIntervalSeconds * 1000;
    var fixedGaugeRollupMillis = $scope.layout.fixedGaugeRollupSeconds * 1000;

    var currentRefreshId = 0;
    var currentZoomId = 0;
    var currentRollupLevel;

    var originalDataArrays = {};
    var yvalMaps = {};

    var $chart = $('#chart');

    var keyedColorPool = keyedColorPools.create();

    var gaugeScales = {};

    var gaugeDeltas = {};

    var chartFromToDefault;

    $scope.keyedColorPool = keyedColorPool;

    $scope.$watchGroup(['containerWidth', 'windowHeight'], function () {
      plot.resize();
      plot.setupGrid();
      plot.draw();
    });

    $scope.showChartSpinner = 0;

    $scope.gaugeFilter = '';

    $http.get('backend/jvm/all-gauges')
        .success(function (data) {
          $scope.loaded = true;
          $scope.allGauges = data;
          var allGaugeNames = [];
          angular.forEach(data, function (gauge) {
            allGaugeNames.push(gauge.name);
            if (gauge.everIncreasing) {
              gaugeDeltas[gauge.name] = true;
            }
          });
          $scope.allShortGaugeNames = createShortGaugeNames(data);

          var gaugeNames = $location.search()['gauge-name'];
          if (!gaugeNames) {
            gaugeNames = [];
            if (allGaugeNames.indexOf('java.lang:type=Memory,HeapMemoryUsage/used') !== -1) {
              gaugeNames.push('java.lang:type=Memory,HeapMemoryUsage/used');
            }
          }
          if (angular.isArray(gaugeNames)) {
            angular.forEach(gaugeNames, function (gaugeName) {
              if (allGaugeNames.indexOf(gaugeName) !== -1) {
                keyedColorPool.add(gaugeName);
              }
            });
          } else if (gaugeNames && allGaugeNames.indexOf(gaugeNames) !== -1) {
            keyedColorPool.add(gaugeNames);
          }
          if (keyedColorPool.keys().length) {
            refreshChart();
          } else {
            $scope.chartNoData = true;
          }
        })
        .error(httpErrors.handler($scope));

    function createShortGaugeNames(gauges) {
      var splitGaugeNames = [];
      angular.forEach(gauges, function (gauge) {
        splitGaugeNames.push(gauge.display.split('/'));
      });
      var minRequiredForUniqueName;
      var shortNames = {};
      var i, j;
      for (i = 0; i < gauges.length; i++) {
        var splitGaugeName = splitGaugeNames[i];
        minRequiredForUniqueName = 2;
        for (j = 0; j < gauges.length; j++) {
          if (j === i) {
            continue;
          }
          var splitGaugeName2 = splitGaugeNames[j];
          minRequiredForUniqueName = Math.max(minRequiredForUniqueName,
              numSamePartsStartingAtEnd(splitGaugeName, splitGaugeName2) + 1);
        }
        shortNames[gauges[i].name] = splitGaugeName.slice(-minRequiredForUniqueName).join('/');
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
        // 2x in order to deal with displaying deltas
        from: chartFrom - 2 * plusMinus,
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
            plotGaugeNames = query.gaugeNames;
            var i, j;
            originalDataArrays = {};
            for (i = 0; i < plotGaugeNames.length; i++) {
              originalDataArrays[plotGaugeNames[i]] = data[i];
            }
            var dataSeries;
            var point;
            for (i = 0; i < data.length; i++) {
              dataSeries = data[i];
              for (j = 0; j < dataSeries.length; j++) {
                point = dataSeries[j];
                if (point && point[1] < 0) {
                  point[1] = 0;
                }
              }
            }
            updatePlotData(data);
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

    $scope.$watch('filterDate', function (newValue, oldValue) {
      if (newValue && oldValue && newValue.getTime() !== oldValue.getTime()) {
        $timeout(function () {
          $('#refreshButtonContainer button').click();
        });
      }
    });

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

    // scale will bring max into 0..100 range
    // not using Math.log / Math.log(10) due to floating point issues
    function getScale(max) {
      if (max === 0) {
        return 1;
      }
      var scale = 0.000000000000001;
      while (max * scale * 10 <= 100) {
        scale *= 10;
      }
      // deal with floating point problems
      scale = parseFloat(scale.toPrecision(1));
      return scale;
    }

    function updatePlotData(data) {
      var plotData = [];
      // reset gauge scales
      gaugeScales = {};
      yvalMaps = {};
      // using plotGaugeNames.length since data.length is 1 for dummy data [[]] which is needed for flot to draw
      // gridlines

      if (plotGaugeNames && plotGaugeNames.length) {
        for (var i = 0; i < plotGaugeNames.length; i++) {
          var plotGaugeName = plotGaugeNames[i];
          var points = angular.copy(data[i]);
          var j;
          var point;
          if (gaugeDeltas[plotGaugeName]) {
            var deltas = [];
            for (j = 1; j < points.length; j++) {
              point = points[j];
              var lastPoint = points[j - 1];
              if (point && lastPoint) {
                deltas[j - 1] = [point[0], point[1] - lastPoint[1]];
              } else {
                deltas[j - 1] = null;
              }
            }
            points = deltas;
          }
          updateYvalMap(plotGaugeName, points);
          if (points.length) {
            var scale = scalePoints(points);
            gaugeScales[plotGaugeName] = scale;
          } else {
            gaugeScales[plotGaugeName] = undefined;
          }
          if (gaugeDeltas[plotGaugeName]) {
            // now that yval map has correct (possibly negative) values for tooltip
            // truncate negative values so they show up on the chart as 0 (tooltip will reveal true value)
            for (j = 0; j < points.length; j++) {
              point = points[j];
              if (point && point[1] < 0) {
                point[1] = 0;
              }
            }
          }
          plotData.push({
            data: points,
            color: keyedColorPool.get(plotGaugeName),
            label: plotGaugeName
          });
        }
      }
      updateThePlotData(plotData);
    }

    function updateYvalMap(label, points) {
      var map = {};
      var i;
      var point;
      for (i = 0; i < points.length; i++) {
        point = points[i];
        if (point) {
          map[point[0]] = point[1];
        }
      }
      yvalMaps[label] = map;
    }

    function scalePoints(points) {
      var max = 0;
      var j;
      var point;
      for (j = 0; j < points.length; j++) {
        point = points[j];
        if (!point) {
          continue;
        }
        var value = point[1];
        if (value > max) {
          max = value;
        }
      }
      var scale = getScale(max);
      if (scale !== 1) {
        for (j = 0; j < points.length; j++) {
          point = points[j];
          if (point) {
            point[1] *= scale;
          }
        }
      }
      return scale;
    }

    function updateThePlotData(plotData) {
      var nodata = true;
      for (var i = 0; i < plotData.length; i++) {
        var points = plotData[i].data;
        if (nodata) {
          nodata = points.length === 0;
        }
      }
      $scope.chartNoData = nodata;
      if (plotData.length) {
        plot.setData(plotData);
      } else {
        plot.setData([[]]);
      }
      plot.setupGrid();
      plot.draw();
      updateLegend(plot);
    }

    function updateLegend() {
      var plotData = plot.getData();
      $scope.seriesLabels = [];
      var seriesIndex;
      for (seriesIndex = 0; seriesIndex < plotData.length; seriesIndex++) {
        var label = plotData[seriesIndex].label;
        $scope.seriesLabels.push({
          color: plotData[seriesIndex].color,
          text: $scope.allShortGaugeNames[label]
        });
      }
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
      var filteredData = [];
      var i;
      for (i = 0; i < plotGaugeNames.length; i++) {
        var plotGaugeName = plotGaugeNames[i];
        var origData = originalDataArrays[plotGaugeName];
        filteredData.push(getFilteredPoints(origData, from, to));
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

    $scope.lineBreakableGaugeName = function (gaugeName) {
      // \u200b is zero width space and \u00a0 is non-breaking space
      // these are used to change wrapping behavior on smaller screens (or larger mbean names)
      gaugeName = gaugeName.replace(/\//g, '\u200b/');
      gaugeName = gaugeName.replace(/ /g, '\u00a0');
      return gaugeName;
    };

    $scope.gaugeNameStyle = function (gaugeName) {
      var color = keyedColorPool.get(gaugeName);
      if (color) {
        return {
          'font-weight': 'bold',
          cursor: 'pointer'
        };
      } else {
        return {
          'font-weight': 'normal',
          cursor: 'pointer'
        };
      }
    };

    $scope.gaugeColorStyle = function (gaugeName) {
      var style = {
        width: '60px',
        height: '18px',
        'font-style': 'italic'
      };
      if (gaugeScales[gaugeName]) {
        var color = keyedColorPool.get(gaugeName);
        if (color) {
          style['background-color'] = color;
        }
      }
      return style;
    };

    $scope.gaugeColorText = function (gaugeName) {
      if (plotGaugeNames && plotGaugeNames.indexOf(gaugeName) !== -1 && !gaugeScales[gaugeName]) {
        return 'no data';
      }
      return '';
    };

    $scope.hasGaugeScale = function (gaugeName) {
      return gaugeScales[gaugeName];
    };

    $scope.getGaugeScale = function (gaugeName) {
      var scale = gaugeScales[gaugeName];
      if (!scale) {
        return '';
      }
      scale = scale.toString();
      var index = scale.indexOf('e-');
      if (index === -1) {
        return scale;
      }
      var numZeros = scale.substring(index + 2) - 1;
      scale = '0.';
      for (var i = 0; i < numZeros; i++) {
        scale += '0';
      }
      return scale + '1';
    };

    $scope.deltaStyle = function (gaugeName) {
      var style = {
        border: 'none',
        padding: 0
      };
      if (gaugeDeltas[gaugeName]) {
        style.color = '#417998';
      } else {
        style.color = '#e5e5e5';
      }
      return style;
    };

    $scope.toggleDelta = function (gaugeName) {
      if (gaugeDeltas[gaugeName]) {
        delete gaugeDeltas[gaugeName];
      } else {
        gaugeDeltas[gaugeName] = true;
      }
      updatePlotData(getFilteredData());
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
        deselectGauge(gaugeName);
        updateThePlotData(plot.getData());
      } else {
        // check it
        keyedColorPool.add(gaugeName);
        refreshChart();
      }
      updateLocation();
    };

    function deselectGauge(gaugeName) {
      keyedColorPool.remove(gaugeName);
      var index = plotGaugeNames.indexOf(gaugeName);
      plotGaugeNames.splice(index, 1);
      delete gaugeScales[gaugeName];
      delete originalDataArrays[gaugeName];
      delete yvalMaps[gaugeName];
      var plotData = plot.getData();
      var i;
      for (i = 0; i < plotData.length; i++) {
        if (plotData[i].label === gaugeName) {
          plotData.splice(i, 1);
          break;
        }
      }
    }

    $scope.showingAllGauges = function () {
      if (!$scope.allGauges) {
        return true;
      }
      var gauges = $filter('filter')($scope.allGauges, {display: $scope.gaugeFilter});
      return gauges.length === $scope.allGauges.length;
    };

    $scope.selectAllGauges = function () {
      var gauges = $filter('filter')($scope.allGauges, {display: $scope.gaugeFilter});
      angular.forEach(gauges, function (gauge) {
        keyedColorPool.add(gauge.name);
      });
      refreshChart();
      updateLocation();
    };

    $scope.deselectAllGauges = function () {
      var gauges = $filter('filter')($scope.allGauges, {display: $scope.gaugeFilter});
      angular.forEach(gauges, function (gauge) {
        keyedColorPool.remove(gauge.name);
        deselectGauge(gauge.name);
      });
      updateThePlotData(plot.getData());
      updateLocation();
    };

    $scope.zoomOut = function () {
      // need to execute this outside of $apply since code assumes it needs to do its own $apply
      $timeout(function () {
        plot.zoomOut();
      });
    };

    function onLocationChangeSuccess() {
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
        // show 4 hour interval, but nothing prior to today (e.g. if 'now' is 1am) or after today
        // (e.g. if 'now' is 11:55pm)
        var now = new Date();
        now.setSeconds(0, 0);
        $scope.chartFrom = Math.max(now.getTime() - 225 * 60 * 1000, today.getTime());
        $scope.chartTo = Math.min($scope.chartFrom + 240 * 60 * 1000, today.getTime() + 24 * 60 * 60 * 1000);
      }
    }

    // need to defer listener registration, otherwise captures initial location change sometimes
    $timeout(function () {
      $scope.$on('$locationChangeSuccess', onLocationChangeSuccess);
    });
    onLocationChangeSuccess();

    function updateLocation() {
      var query = {};
      if (!chartFromToDefault) {
        query.from = $scope.chartFrom;
        query.to = $scope.chartTo;
      }
      query['gauge-name'] = keyedColorPool.keys();
      $location.search(query);
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
          // TODO after changing gauges to be more like other charts, can remove custom flot code for absoluteZoomRange
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
          ctrlKey: true,
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
            var from = xval - fixedGaugeRollupMillis;
            var to = xval;

            if (currentRollupLevel === 0) {
              var nonScaledValue = yvalMaps[label][xval];
              var tooltip = '<table class="gt-chart-tooltip">';
              tooltip += '<tr><td colspan="2" style="font-weight: 600;">' + $scope.allShortGaugeNames[label];
              tooltip += '</td></tr><tr><td style="padding-right: 10px;">Time:</td><td style="font-weight: 400;">';
              tooltip += moment(xval).format('h:mm:ss.SSS a (Z)') + '</td></tr>';
              tooltip += '<tr><td style="padding-right: 10px;">Value:</td><td style="font-weight: 600;">';
              tooltip += $filter('number')(nonScaledValue) + '</td></tr>';
              tooltip += '</table>';
              return tooltip;
            }

            function smartFormat(millis) {
              if (millis % 60000 === 0) {
                return moment(millis).format('LT');
              } else {
                return moment(millis).format('LTS');
              }
            }

            function display(dataSeries, value) {
              function sixDigitsOfPrecision(value) {
                if (value < 1000000) {
                  return parseFloat(value.toPrecision(6));
                } else {
                  return Math.round(value);
                }
              }

              var nonScaledValue = sixDigitsOfPrecision(yvalMaps[dataSeries.label][xval]);
              return $filter('number')(nonScaledValue);
            }

            var html = '<table class="gt-chart-tooltip"><thead><tr><td colspan="3" style="font-weight: 600;">';
            html += smartFormat(from);
            html += ' to ';
            html += smartFormat(to);
            html += '</td></tr></thead><tbody>';
            var plotData = plot.getData();
            var seriesIndex;
            var dataSeries;
            var value;
            var noData = true;
            for (seriesIndex = 0; seriesIndex < plotData.length; seriesIndex++) {
              dataSeries = plotData[seriesIndex];
              value = yvalMaps[dataSeries.label][xval];
              if (value === undefined) {
                // gap
                continue;
              }
              noData = false;
              html += '<tr';
              if (seriesIndex === flotItem.seriesIndex) {
                html += ' style="background-color: #eee;"';
              }
              html += '>' +
              '<td class="legendColorBox">' +
              '<div style="border: 1px solid rgb(204, 204, 204); padding: 1px;">' +
              '<div style="width: 4px; height: 0px; border: 5px solid ' + dataSeries.color + '; overflow: hidden;">' +
              '</div></div></td>' +
              '<td style="padding-right: 10px;">' + $scope.allShortGaugeNames[dataSeries.label] + '</td>' +
              '<td style="font-weight: 600;">' + display(dataSeries, value) + '</td>' +
              '</tr>';
            }
            if (noData) {
              return 'No data';
            }
            html += '</tbody></table>';
            return html;
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
