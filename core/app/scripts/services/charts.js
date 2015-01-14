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
        currentRefreshId: 0,
        currentZoomId: 0,
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

      $chart.bind('plotzoom', function (event, plot) {
        $scope.$apply(function () {
          // throw up spinner right away
          $scope.showChartSpinner++;
          // need to call setupGrid on each zoom to handle rapid zooming
          plot.setupGrid();
          var zoomId = ++chartState.currentZoomId;
          // use 100 millisecond delay to handle rapid zooming
          $timeout(function () {
            if (zoomId === chartState.currentZoomId) {
              $scope.$parent.chartFrom = plot.getAxes().xaxis.min;
              $scope.$parent.chartTo = plot.getAxes().xaxis.max;
              $scope.$parent.chartFromToDefault = false;
              $location.search($scope.buildQueryObject()).replace();
            }
            $scope.showChartSpinner--;
          }, 100);
        });
      });

      $chart.bind('plotselected', function (event, ranges) {
        $scope.$apply(function () {
          chartState.plot.clearSelection();
          // perform the zoom
          chartState.plot.getAxes().xaxis.options.min = ranges.xaxis.from;
          chartState.plot.getAxes().xaxis.options.max = ranges.xaxis.to;
          chartState.plot.setupGrid();
          $scope.$parent.chartFrom = chartState.plot.getAxes().xaxis.min;
          $scope.$parent.chartTo = chartState.plot.getAxes().xaxis.max;
          $scope.$parent.chartFromToDefault = false;
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
      chartState.plot.getAxes().xaxis.options.borderGridLock = 1000 * $scope.layout.fixedAggregateIntervalSeconds;
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
      var refreshId = ++chartState.currentRefreshId;
      $http.get(url + queryStrings.encodeObject(query))
          .success(function (data) {
            $scope.showChartSpinner--;
            if (refreshId !== chartState.currentRefreshId) {
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

    function renderTooltipHtml(from, to, dataIndex, highlightSeriesIndex, chartState, display) {
      var html = '<table><thead><tr><td colspan="3" class="legendLabel" style="font-weight: 700;">';
      html += moment(from).format('LT');
      html += ' to ';
      html += moment(to).format('LT');
      html += '</td></tr></thead><tbody>';
      var plotData = chartState.plot.getData();
      var seriesIndex;
      var dataSeries;
      var value;
      for (seriesIndex = 0; seriesIndex < plotData.length; seriesIndex++) {
        dataSeries = plotData[seriesIndex];
        value = dataSeries.data[dataIndex][1];
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
      html += '</tbody></table>';
      return html;
    }

    return {
      createState: createState,
      init: init,
      plot: plot,
      refreshData: refreshData,
      renderTooltipHtml: renderTooltipHtml
    };
  }
]);
