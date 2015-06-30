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

    var DEFAULT_GAUGES = ['java.lang:type=Memory,HeapMemoryUsage/used'];

    var plotGaugeNames;

    var chartState = charts.createState();

    var yvalMaps = {};

    var gaugeScales = {};

    var gaugeDeltas = {};

    var gaugeShortDisplayMap = {};

    $scope.gaugeFilter = '';

    $scope.currentTabUrl = function () {
      return 'jvm/gauges';
    };

    function refreshData() {
      charts.refreshData('backend/jvm/gauge-points', chartState, $scope, addToQuery, onRefreshData);
    }

    $scope.$watch('[last, chartFrom, chartTo, gaugeNames, chartRefresh]', function (newValues, oldValues) {
      if (newValues !== oldValues) {
        $location.search($scope.buildQueryObject());
        if ($scope.gaugeNames.length) {
          refreshData();
        } else {
          $scope.chartNoData = true;
        }
      }
    }, true);

    $scope.$watch('seriesLabels', function (newValues, oldValues) {
      if (newValues !== oldValues) {
        var i;
        for (i = 0; i < newValues.length; i++) {
          var shortDisplay = gaugeShortDisplayMap[newValues[i].text];
          if (shortDisplay) {
            newValues[i].text = shortDisplay;
          }
        }
      }
    });

    $scope.buildQueryObject = function (baseQuery) {
      var query = baseQuery || angular.copy($location.search());
      if (!$scope.last) {
        query.from = $scope.chartFrom;
        query.to = $scope.chartTo;
        delete query.last;
      } else if ($scope.last !== 4 * 60 * 60 * 1000) {
        query.last = $scope.last;
        delete query.from;
        delete query.to;
      }
      if (angular.equals($scope.gaugeNames, DEFAULT_GAUGES)) {
        delete query['gauge-name'];
      } else {
        query['gauge-name'] = $scope.gaugeNames;
      }
      return query;
    };

    // TODO this is exact duplicate of same function in transaction.js
    $scope.applyLast = function () {
      if (!$scope.last) {
        return;
      }
      var dataPointIntervalMillis = charts.getDataPointIntervalMillis(0, 1.1 * $scope.last);
      var now = moment().startOf('second').valueOf();
      var from = now - $scope.last;
      var to = now + $scope.last / 10;
      var revisedFrom = Math.floor(from / dataPointIntervalMillis) * dataPointIntervalMillis;
      var revisedTo = Math.ceil(to / dataPointIntervalMillis) * dataPointIntervalMillis;
      var revisedDataPointIntervalMillis = charts.getDataPointIntervalMillis(revisedFrom, revisedTo);
      if (revisedDataPointIntervalMillis !== dataPointIntervalMillis) {
        // expanded out to larger rollup threshold so need to re-adjust
        // ok to use original from/to instead of revisedFrom/revisedTo
        revisedFrom = Math.floor(from / revisedDataPointIntervalMillis) * revisedDataPointIntervalMillis;
        revisedTo = Math.ceil(to / revisedDataPointIntervalMillis) * revisedDataPointIntervalMillis;
      }
      $scope.chartFrom = revisedFrom;
      $scope.chartTo = revisedTo;
    };

    var location;

    function addToQuery(query) {
      query.gaugeNames = $scope.gaugeNames;
      var plusMinus;
      if ($scope.chartTo - $scope.chartFrom > 3600 * 1000) {
        plusMinus = 1000 * $scope.layout.fixedGaugeRollupSeconds;
      } else {
        plusMinus = 1000 * $scope.layout.fixedGaugeIntervalSeconds;
      }
      // 2x in order to deal with displaying deltas
      query.from = $scope.chartFrom - 2 * plusMinus;
      query.to = $scope.chartTo + plusMinus;
    }

    function onRefreshData(data) {
      var i, j;
      var dataSeries;
      var point;
      for (i = 0; i < data.dataSeries.length; i++) {
        dataSeries = data.dataSeries[i];
        for (j = 0; j < dataSeries.data.length; j++) {
          point = dataSeries.data[j];
          if (point && point[1] < 0) {
            point[1] = 0;
          }
        }
      }
      updatePlotData(data.dataSeries);
      for (i = 0; i < data.dataSeries.length; i++) {
        data.dataSeries[i].shortLabel = gaugeShortDisplayMap[data.dataSeries[i].name];
      }
    }

    function onLocationChangeSuccess() {
      var priorLocation = location;
      location = {};
      location.last = Number($location.search().last);
      location.chartFrom = Number($location.search().from);
      location.chartTo = Number($location.search().to);
      // both from and to must be supplied or neither will take effect
      if (location.chartFrom && location.chartTo) {
        location.last = 0;
      } else if (!location.last) {
        location.last = 4 * 60 * 60 * 1000;
      }
      location.gaugeNames = $location.search()['gauge-name'] || angular.copy(DEFAULT_GAUGES);
      if (!angular.isArray(location.gaugeNames)) {
        location.gaugeNames = [location.gaugeNames];
      }
      if (!angular.equals(location, priorLocation)) {
        // only update scope if relevant change
        $scope.gaugeNames = angular.copy(location.gaugeNames);
        angular.extend($scope, location);
        $scope.applyLast();
      }
    }

    // need to defer listener registration, otherwise captures initial location change sometimes
    $timeout(function () {
      $scope.$on('$locationChangeSuccess', onLocationChangeSuccess);
    });

    $http.get('backend/jvm/all-gauges')
        .success(function (data) {
          $scope.loaded = true;
          $scope.allGauges = data;
          createShortDataSeriesNames(data);
          var allGaugeNames = [];
          gaugeShortDisplayMap = {};
          angular.forEach(data, function (gauge) {
            allGaugeNames.push(gauge.name);
            gaugeShortDisplayMap[gauge.name] = gauge.shortDisplay;
            if (gauge.everIncreasing) {
              gaugeDeltas[gauge.name] = true;
            }
          });
          refreshData();
        })
        .error(httpErrors.handler($scope));

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
      // reset gauge scales
      gaugeScales = {};
      yvalMaps = {};

      for (var i = 0; i < data.length; i++) {
        var dataSeries = data[i];
        var j;
        var point;
        if (gaugeDeltas[dataSeries.name]) {
          var deltas = [];
          var lastPoint;
          for (j = 1; j < dataSeries.data.length; j++) {
            point = dataSeries.data[j];
            if (point && lastPoint) {
              deltas[j - 1] = [point[0], point[1] - lastPoint[1]];
            } else {
              deltas[j - 1] = null;
            }
            if (point) {
              lastPoint = point;
            }
          }
          dataSeries.data = deltas;
        } else {
          // need to remove first data point so the x-axis of the non-deltas and deltas match (when rolled up)
          dataSeries.data.splice(0, 1);
        }
        updateYvalMap(dataSeries.name, dataSeries.data);
        if (dataSeries.data.length) {
          var scale = scalePoints(dataSeries.data);
          gaugeScales[dataSeries.name] = scale;
        } else {
          gaugeScales[dataSeries.name] = undefined;
        }
        if (gaugeDeltas[dataSeries.name]) {
          // now that yval map has correct (possibly negative) values for tooltip
          // truncate negative values so they show up on the chart as 0 (tooltip will reveal true value)
          for (j = 0; j < dataSeries.data.length; j++) {
            point = dataSeries.data[j];
            if (point && point[1] < 0) {
              point[1] = 0;
            }
          }
        }
      }
      updateThePlotData(data);
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

    function updateThePlotData(data) {
      var nodata = true;
      for (var i = 0; i < data.length; i++) {
        var points = data[i].data;
        if (nodata) {
          nodata = points.length === 0;
        }
      }
      $scope.chartNoData = nodata;
    }

    function createShortDataSeriesNames(gauges) {
      var splitGaugeNames = [];
      angular.forEach(gauges, function (gauge) {
        splitGaugeNames.push(gauge.display.split('/'));
      });
      var minRequiredForUniqueName;
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
        gauges[i].shortDisplay = splitGaugeName.slice(-minRequiredForUniqueName).join('/');
      }
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

    $scope.lineBreakableGaugeName = function (gaugeName) {
      // \u200b is zero width space and \u00a0 is non-breaking space
      // these are used to change wrapping behavior on smaller screens (or larger mbean names)
      gaugeName = gaugeName.replace(/\//g, '\u200b/');
      gaugeName = gaugeName.replace(/ /g, '\u00a0');
      return gaugeName;
    };

    $scope.gaugeNameStyle = function (gaugeName) {
      if ($scope.gaugeNames.indexOf(gaugeName) === -1) {
        return {
          'font-weight': 'normal',
          cursor: 'pointer'
        };
      } else {
        return {
          'font-weight': 'bold',
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
        var color = chartState.keyedColorPool.get(gaugeName);
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
      refreshData();
    };

    $scope.clickGaugeName = function (gaugeName) {
      var index = $scope.gaugeNames.indexOf(gaugeName);
      if (index === -1) {
        $scope.gaugeNames.push(gaugeName);
      } else {
        $scope.gaugeNames.splice(index, 1);
      }
    };

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
        var index = $scope.gaugeNames.indexOf(gauge.name);
        if (index === -1) {
          $scope.gaugeNames.push(gauge.name);
        }
      });
    };

    $scope.deselectAllGauges = function () {
      var gauges = $filter('filter')($scope.allGauges, {display: $scope.gaugeFilter});
      angular.forEach(gauges, function (gauge) {
        var index = $scope.gaugeNames.indexOf(gauge.name);
        if (index !== -1) {
          $scope.gaugeNames.splice(index, 1);
        }
      });
    };

    function displaySixDigitsOfPrecision(value) {
      var nonScaledValue;
      if (value < 1000000) {
        nonScaledValue = parseFloat(value.toPrecision(6));
      } else {
        nonScaledValue = Math.round(value);
      }
      return $filter('number')(nonScaledValue);
    }

    var chartOptions = {
      tooltip: true,
      yaxis: {
        label: ''
      },
      series: {
        stack: false,
        lines: {
          fill: false
        }
      },
      tooltipOpts: {
        content: function (label, xval, yval, flotItem) {
          if ($scope.chartTo - $scope.chartFrom <= 3600 * 1000) {
            var nonScaledValue = yvalMaps[label][xval];
            var tooltip = '<table class="gt-chart-tooltip">';
            tooltip += '<tr><td colspan="2" style="font-weight: 600;">' + gaugeShortDisplayMap[label];
            tooltip += '</td></tr><tr><td style="padding-right: 10px;">Time:</td><td style="font-weight: 400;">';
            tooltip += moment(xval).format('h:mm:ss.SSS a (Z)') + '</td></tr>';
            tooltip += '<tr><td style="padding-right: 10px;">Value:</td><td style="font-weight: 600;">';
            tooltip += $filter('number')(nonScaledValue) + '</td></tr>';
            tooltip += '</table>';
            return tooltip;
          }
          var from = xval - chartState.dataPointIntervalMillis;
          var to = xval;
          return charts.renderTooltipHtml(from, to, undefined, flotItem.dataIndex, flotItem.seriesIndex,
              chartState.plot, function (value, label) {
                var nonScaledValue = yvalMaps[label][xval];
                return displaySixDigitsOfPrecision(nonScaledValue);
              });
        }
      }
    };

    charts.init(chartState, $('#chart'), $scope);
    charts.plot([[]], chartOptions, chartState, $('#chart'), $scope);
    onLocationChangeSuccess();
  }
]);
