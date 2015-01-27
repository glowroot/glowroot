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
  '$timeout',
  'keyedColorPools',
  'queryStrings',
  'httpErrors',
  function ($http, $location, $timeout, keyedColorPools, queryStrings, httpErrors) {

    function createState() {
      return {
        plot: undefined,
        keyedColorPool: keyedColorPools.create()
      };
    }

    function init(chartState, $chart, $scope) {

      $scope.showChartSpinner = 0;

      $scope.$watchGroup(['containerWidth', 'windowHeight'], function () {
        chartState.plot.resize();
        chartState.plot.setupGrid();
        chartState.plot.draw();
      });

      $chart.bind('plotzoom', function (event, plot, args) {
        var zoomingOut = args.amount && args.amount < 1;
        $scope.$apply(function () {
          plot.setupGrid();
          updateRange($scope, plot, plot.getAxes().xaxis.min, plot.getAxes().xaxis.max, zoomingOut);
          $location.search($scope.buildQueryObject()).replace();
        });
      });

      $chart.bind('plotselected', function (event, ranges) {
        $scope.$apply(function () {
          chartState.plot.clearSelection();
          // unlike plotzoom, plotselected should shrink down
          updateRange($scope, chartState.plot, ranges.xaxis.from, ranges.xaxis.to);
          $location.search($scope.buildQueryObject()).replace();
        });
      });

      $scope.zoomOut = function () {
        // need to execute this outside of $apply since code assumes it needs to do its own $apply
        $timeout(function () {
          chartState.plot.zoomOut();
        });
      };
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
          },
          points: {
            radius: 8
          }
        }
      };
      chartState.plot = $.plot($chart, data, $.extend(true, options, chartOptions));
      chartState.plot.getAxes().yaxis.options.max = undefined;
    }

    function refreshData(url, chartState, $scope, onRefreshData) {
      var query = {
        from: $scope.chartFrom,
        to: $scope.chartTo,
        transactionType: $scope.transactionType,
        transactionName: $scope.transactionName
      };
      var date = $scope.filterDate;
      $scope.showChartSpinner++;
      $http.get(url + queryStrings.encodeObject(query))
          .success(function (data) {
            $scope.showChartSpinner--;
            if ($scope.showChartSpinner) {
              // ignore this response, another response has been stacked
              return;
            }
            $scope.chartNoData = !data.dataSeries.length;
            // reset axis in case user changed the date and then zoomed in/out to trigger this refresh
            chartState.plot.getAxes().xaxis.options.min = query.from;
            chartState.plot.getAxes().xaxis.options.max = query.to;
            chartState.plot.getAxes().xaxis.options.zoomRange = [
              date.getTime(),
              date.getTime() + 24 * 60 * 60 * 1000
            ];
            var newRollupLevel = rollupLevel(query.from, query.to);
            if (newRollupLevel === 0) {
              chartState.dataPointIntervalMillis = $scope.layout.fixedAggregateIntervalSeconds * 1000;
            } else {
              chartState.dataPointIntervalMillis = $scope.layout.fixedAggregateRollupSeconds * 1000;
            }
            var plotData = [];
            var labels = [];
            angular.forEach(data.dataSeries, function (dataSeries) {
              labels.push(dataSeries.name ? dataSeries.name : 'Other');
            });
            chartState.keyedColorPool.reset(labels);
            angular.forEach(data.dataSeries, function (dataSeries, index) {
              var label = labels[index];
              plotData.push({
                data: dataSeries.data,
                label: label,
                color: chartState.keyedColorPool.get(label)
              });
            });
            if (plotData.length) {
              chartState.plot.setData(plotData);
            } else {
              chartState.plot.setData([[]]);
            }
            chartState.plot.setupGrid();
            chartState.plot.draw();
            onRefreshData(data, query);
          })
          .error(function (data, status) {
            $scope.showChartSpinner--;
            httpErrors.handler($scope)(data, status);
          });
    }

    function updateRange($scope, plot, from, to, zoomingOut) {
      var dataPointIntervalMillis;
      var newRollupLevel = rollupLevel(from, to);
      if (newRollupLevel === 0) {
        dataPointIntervalMillis = $scope.layout.fixedAggregateIntervalSeconds * 1000;
      } else {
        dataPointIntervalMillis = $scope.layout.fixedAggregateRollupSeconds * 1000;
      }
      var revisedFrom;
      var revisedTo;
      if (zoomingOut) {
        revisedFrom = Math.floor(from / dataPointIntervalMillis) * dataPointIntervalMillis;
        revisedTo = Math.ceil(to / dataPointIntervalMillis) * dataPointIntervalMillis;
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
      if (revisedFrom === $scope.$parent.chartFrom && revisedTo === $scope.$parent.chartTo) {
        // no change, so chart won't be refreshed, so need to correct the range manually
        plot.getAxes().xaxis.options.min = revisedFrom;
        plot.getAxes().xaxis.options.max = revisedTo;
        plot.setupGrid();
      } else {
        $scope.$parent.chartFrom = revisedFrom;
        $scope.$parent.chartTo = revisedTo;
        $scope.$parent.chartFromToDefault = false;
      }
    }

    function rollupLevel(from, to) {
      if (to - from <= 2 * 3600 * 1000) {
        return 0;
      } else {
        return 1;
      }
    }

    function renderTooltipHtml(from, to, transactionCount, dataIndex, highlightSeriesIndex, chartState, display) {
      function smartFormat(millis) {
        if (millis % 60000 === 0) {
          return moment(millis).format('LT');
        } else {
          return moment(millis).format('LTS');
        }
      }

      var html = '<table><thead><tr><td colspan="3" class="legendLabel" style="font-weight: 700;">';
      html += smartFormat(from);
      html += ' to ';
      html += smartFormat(to);
      html += '</td></tr><tr><td colspan="3" class="legendLabel">';
      html += transactionCount;
      html += ' transactions';
      html += '</td></tr></thead><tbody>';
      var plotData = chartState.plot.getData();
      var seriesIndex;
      var dataSeries;
      var value;
      var total = 0;
      for (seriesIndex = 0; seriesIndex < plotData.length; seriesIndex++) {
        dataSeries = plotData[seriesIndex];
        value = dataSeries.data[dataIndex][1];
        total += value;
        html += '<tr';
        if (seriesIndex === highlightSeriesIndex) {
          html += ' style="background-color: #eee;"';
        }
        html += '>' +
        '<td class="legendColorBox">' +
        '<div style="border: 1px solid rgb(204, 204, 204); padding: 1px;">' +
        '<div style="width: 4px; height: 0px; border: 5px solid ' + dataSeries.color + '; overflow: hidden;">' +
        '</div></div></td>' +
        '<td class="legendLabel" style="padding-right: 10px;">' + dataSeries.label + '</td>' +
        '<td style="font-weight: 700;">' + display(value) + '</td>' +
        '</tr>';
      }
      if (total === 0) {
        return 'No data';
      }
      html += '</tbody></table>';
      return html;
    }

    return {
      createState: createState,
      init: init,
      plot: plot,
      refreshData: refreshData,
      renderTooltipHtml: renderTooltipHtml,
      rollupLevel: rollupLevel
    };
  }
]);
