/*
 * Copyright 2015 the original author or authors.
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

/* global glowroot, angular, moment, $ */

// common code shared between transaction.js and errors.js
glowroot.factory('charts', [
  '$http',
  '$location',
  '$rootScope',
  'keyedColorPools',
  'queryStrings',
  'httpErrors',
  function ($http, $location, $rootScope, keyedColorPools, queryStrings, httpErrors) {

    function createState() {
      return {
        plot: undefined,
        keyedColorPool: keyedColorPools.create()
      };
    }

    function init(chartState, $chart, $scope) {

      $scope.showChartSpinner = 0;

      $chart.bind('plotzoom', function (event, plot, args) {
        var zoomingOut = args.amount && args.amount < 1;
        $scope.$apply(function () {
          var from = plot.getAxes().xaxis.options.min;
          var to = plot.getAxes().xaxis.options.max;
          updateRange($scope, from, to, zoomingOut);
        });
      });

      $chart.bind('plotselected', function (event, ranges) {
        $scope.$apply(function () {
          chartState.plot.clearSelection();
          var from = ranges.xaxis.from;
          var to = ranges.xaxis.to;
          updateRange($scope, from, to, false, true);
        });
      });

      $scope.zoomOut = function () {
        var currMin = $scope.chartFrom;
        var currMax = $scope.chartTo;
        var currRange = currMax - currMin;
        updateRange($scope, currMin - currRange / 2, currMax + currRange / 2, true);
      };

      $scope.refresh = function () {
        $scope.applyLast();
        $scope.chartRefresh++;
      };
    }

    function initResize(plot, $scope) {
      $scope.$watchGroup(['containerWidth', 'windowHeight'], function () {
        plot.resize();
        plot.setupGrid();
        plot.draw();
      });
    }

    function updateRange($scope, from, to, zoomingOut, selection, selectionNearestLarger) {
      // force chart refresh even if chartFrom/chartTo don't change (e.g. trying to zoom in beyond single interval)
      $scope.chartRefresh++;

      if (zoomingOut && $scope.last) {
        $scope.last = roundUpLast($scope.last * 2);
        $scope.applyLast();
        return;
      }

      var dataPointIntervalMillis = getDataPointIntervalMillis(from, to);
      var revisedFrom;
      var revisedTo;
      if (zoomingOut || selectionNearestLarger) {
        revisedFrom = Math.floor(from / dataPointIntervalMillis) * dataPointIntervalMillis;
        revisedTo = Math.ceil(to / dataPointIntervalMillis) * dataPointIntervalMillis;
        var revisedDataPointIntervalMillis = getDataPointIntervalMillis(revisedFrom, revisedTo);
        if (revisedDataPointIntervalMillis !== dataPointIntervalMillis) {
          // expanded out to larger rollup threshold so need to re-adjust
          // ok to use original from/to instead of revisedFrom/revisedTo
          revisedFrom = Math.floor(from / revisedDataPointIntervalMillis) * revisedDataPointIntervalMillis;
          revisedTo = Math.ceil(to / revisedDataPointIntervalMillis) * revisedDataPointIntervalMillis;
        }
      } else {
        revisedFrom = Math.ceil(from / dataPointIntervalMillis) * dataPointIntervalMillis;
        revisedTo = Math.floor(to / dataPointIntervalMillis) * dataPointIntervalMillis;
        if (revisedTo <= revisedFrom) {
          // shrunk too far
          if (revisedFrom - from < to - revisedTo) {
            // 'from' was not revised as much as 'to'
            revisedFrom = Math.floor(from / dataPointIntervalMillis) * dataPointIntervalMillis;
            revisedTo = revisedFrom + dataPointIntervalMillis;
          } else {
            revisedTo = Math.ceil(to / dataPointIntervalMillis) * dataPointIntervalMillis;
            revisedFrom = revisedTo - dataPointIntervalMillis;
          }
        }
      }
      var now = new Date().getTime();
      // need to compare original 'to' in case it was revised below 'now'
      if (revisedTo > now || to > now) {
        if (!zoomingOut && !selection && $scope.last) {
          // double-click or scrollwheel zooming in, need special case here, otherwise might zoom in a bit too much
          // due to shrinking the zoom to data point interval, which could result in strange 2 days --> 22 hours
          // instead of the more obvious 2 days --> 1 day
          $scope.last = roundUpLast($scope.last / 2);
          $scope.applyLast();
          return;
        }
        $scope.last = roundUpLast(now - revisedFrom, selection);
        $scope.applyLast();
      } else {
        $scope.chartFrom = revisedFrom;
        $scope.chartTo = revisedTo;
        $scope.last = 0;
      }
    }

    function roundUpLast(last, selection) {
      var hour = 60 * 60 * 1000;
      var day = 24 * hour;
      if (last > day) {
        if (selection) {
          // round down to nearest hour
          return Math.floor(last / hour) * hour;
        } else {
          // round up to nearest day
          return Math.ceil(last / day) * day;
        }
      }
      if (last > hour && !selection) {
        // round up to nearest hour
        return Math.ceil(last / hour) * hour;
      }
      var minute = 60 * 1000;
      if (selection) {
        // round down to nearest minute
        return Math.floor(last / minute) * minute;
      } else {
        // round up to nearest minute
        return Math.ceil(last / minute) * minute;
      }
    }

    function getDataPointIntervalMillis(from, to) {
      var millis = to - from;
      var timeAgoMillis = Date.now() - from;
      var i;
      var rollupConfigs = $rootScope.layout.rollupConfigs;
      for (i = 0; i < rollupConfigs.length - 1; i++) {
        var currRollupConfig = rollupConfigs[i];
        var nextRollupConfig = rollupConfigs[i + 1];
        if (millis < nextRollupConfig.viewThresholdMillis
            && $rootScope.layout.rollupExpirationMillis[i] > timeAgoMillis) {
          return currRollupConfig.intervalMillis;
        }
      }
      return rollupConfigs[rollupConfigs.length - 1].intervalMillis;
    }

    function plot(data, chartOptions, chartState, $chart, $scope) {
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
          reserveSpace: false
        },
        yaxis: {
          ticks: 10,
          zoomRange: false,
          min: 0,
          // 10 second yaxis max just for initial empty chart rendering
          max: 10,
          label: 'milliseconds'
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
          stack: true,
          lines: {
            show: true,
            fill: true
          },
          points: {
            radius: 8
          }
        },
        legend: {
          show: false
        }
      };
      chartState.plot = $.plot($chart, data, $.extend(true, options, chartOptions));
      chartState.plot.getAxes().yaxis.options.max = undefined;
    }

    function refreshData(url, chartState, $scope, addToQuery, onRefreshData) {
      // addToQuery may change query.from/query.to (see gauges.js)
      var chartFrom = $scope.chartFrom;
      var chartTo = $scope.chartTo;
      var query = {
        serverId: $scope.serverId,
        transactionType: $scope.transactionType,
        transactionName: $scope.transactionName,
        from: chartFrom,
        to: chartTo
      };
      if (addToQuery) {
        addToQuery(query);
      }
      $scope.showChartSpinner++;
      $http.get(url + queryStrings.encodeObject(query))
          .success(function (data) {
            $scope.showChartSpinner--;
            if ($scope.showChartSpinner) {
              // ignore this response, another response has been stacked
              return;
            }
            $scope.chartNoData = !data.dataSeries.length;
            // allow callback to modify data if desired
            onRefreshData(data, query);
            // reset axis in case user changed the date and then zoomed in/out to trigger this refresh
            chartState.plot.getAxes().xaxis.options.min = chartFrom;
            chartState.plot.getAxes().xaxis.options.max = chartTo;
            // data point interval calculation must match server-side calculation, so based on query.from/query.to
            // instead of chartFrom/chartTo
            chartState.dataPointIntervalMillis = getDataPointIntervalMillis(query.from, query.to);
            var plotData = [];
            var labels = [];
            angular.forEach(data.dataSeries, function (dataSeries) {
              labels.push(dataSeries.name ? dataSeries.name : 'Other');
            });
            chartState.keyedColorPool.reset(labels);
            angular.forEach(data.dataSeries, function (dataSeries, index) {
              var label = labels[index];
              var plotDataItem = {
                data: dataSeries.data,
                label: label,
                shortLabel: dataSeries.shortLabel,
                color: chartState.keyedColorPool.get(label)
              };
              if (dataSeries.data.length === 1) {
                plotDataItem.points = {
                  show: true,
                  radius: 3,
                  fillColor: chartState.keyedColorPool.get(label)
                };
              }
              plotData.push(plotDataItem);
            });
            if (plotData.length) {
              chartState.plot.setData(plotData);
            } else {
              chartState.plot.setData([[]]);
            }
            chartState.plot.setupGrid();
            chartState.plot.draw();
            updateLegend(chartState, $scope);
          })
          .error(function (data, status) {
            $scope.showChartSpinner--;
            httpErrors.handler($scope)(data, status);
          });
    }

    function updateLegend(chartState, $scope) {
      var plotData = chartState.plot.getData();
      $scope.seriesLabels = [];
      var seriesIndex;
      for (seriesIndex = 0; seriesIndex < plotData.length; seriesIndex++) {
        $scope.seriesLabels.push({
          color: plotData[seriesIndex].color,
          text: plotData[seriesIndex].label
        });
      }
    }

    function renderTooltipHtml(from, to, transactionCount, dataIndex, highlightSeriesIndex, plot, display,
                               headerSuffix) {
      function smartFormat(millis) {
        if (millis % 60000 === 0) {
          return moment(millis).format('LT');
        } else {
          return moment(millis).format('LTS');
        }
      }

      var html = '<table class="gt-chart-tooltip"><thead><tr><td colspan="3" style="font-weight: 600;">';
      html += smartFormat(from);
      html += ' to ';
      html += smartFormat(to);
      if (headerSuffix) {
        html += '<span style="font-weight: 400;">' + headerSuffix + '</span>';
      }
      html += '</td></tr>';
      if (transactionCount !== undefined) {
        html += '<tr><td colspan="3">';
        html += transactionCount;
        html += ' transactions';
        html += '</td></tr>';
      }
      html += '</thead><tbody>';
      var plotData = plot.getData();
      var seriesIndex;
      var dataSeries;
      var value;
      var label;
      var found = false;
      var displayText;
      for (seriesIndex = 0; seriesIndex < plotData.length; seriesIndex++) {
        dataSeries = plotData[seriesIndex];
        if (dataSeries.data[dataIndex]) {
          value = dataSeries.data[dataIndex][1];
          found = true;
          displayText = display(value, dataSeries.label);
        } else {
          displayText = 'no data';
        }
        html += '<tr';
        label = dataSeries.shortLabel ? dataSeries.shortLabel : dataSeries.label;
        if (seriesIndex === highlightSeriesIndex) {
          html += ' style="background-color: #eee;"';
        }
        html += '>' +
            '<td class="legendColorBox">' +
            '<div style="border: 1px solid rgb(204, 204, 204); padding: 1px;">' +
            '<div style="width: 4px; height: 0px; border: 5px solid ' + dataSeries.color + '; overflow: hidden;">' +
            '</div></div></td>' +
            '<td style="padding-right: 10px;">' + label + '</td>' +
            '<td style="font-weight: 600;">' + displayText + '</td>' +
            '</tr>';
      }
      if (!found) {
        return 'No data';
      }
      html += '</tbody></table>';
      return html;
    }

    return {
      createState: createState,
      init: init,
      initResize: initResize,
      plot: plot,
      refreshData: refreshData,
      renderTooltipHtml: renderTooltipHtml,
      updateRange: updateRange,
      getDataPointIntervalMillis: getDataPointIntervalMillis
    };
  }
]);
