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

/* global glowroot, angular, $ */

glowroot.factory('charts', [
  '$http',
  '$location',
  '$timeout',
  'queryStrings',
  'httpErrors',
  function ($http, $location, $timeout, queryStrings, httpErrors) {

    function refreshData(url, query, chartState, $scope, onSuccess) {
      var date = $scope.filterDate;
      if (!date) {
        // TODO display 'Missing date' message
        return;
      }
      $scope.showChartSpinner++;
      var refreshId = ++chartState.currentRefreshId;
      $http.get(url + '?' + queryStrings.encodeObject(query))
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
            onSuccess(data);
            // TODO display 'Success' message
          })
          .error(function (data, status) {
            $scope.showChartSpinner--;
            if (refreshId !== chartState.currentRefreshId) {
              return;
            }
            httpErrors.handler($scope)(data, status);
          });
    }

    function refreshButtonClick(chartState, $scope) {
      if (!$scope.filterDate) {
        // TODO display 'Missing date' message
        return;
      }
      var midnight = new Date($scope.chartFrom).setHours(0, 0, 0, 0);
      if (midnight !== $scope.filterDate.getTime()) {
        // filterDate has changed
        chartState.chartFromToDefault = false;
        $scope.chartFrom = $scope.filterDate.getTime() + ($scope.chartFrom - midnight);
        $scope.chartTo = $scope.filterDate.getTime() + ($scope.chartTo - midnight);
      }
      chartState.refreshData();
    }

    function initFilter(chartState, $scope) {
      $scope.chartFrom = Number($location.search().from);
      $scope.chartTo = Number($location.search().to);
      // both from and to must be supplied or neither will take effect
      if ($scope.chartFrom && $scope.chartTo) {
        $scope.chartFrom += chartState.fixedAggregateIntervalMillis;
        $scope.filterDate = new Date($scope.chartFrom);
        $scope.filterDate.setHours(0, 0, 0, 0);
      } else {
        chartState.chartFromToDefault = true;
        var today = new Date();
        today.setHours(0, 0, 0, 0);
        $scope.filterDate = today;
        // show 2 hour interval, but nothing prior to today (e.g. if 'now' is 1am) or after today
        // (e.g. if 'now' is 11:55pm)
        var now = new Date();
        now.setSeconds(0, 0);
        var fixedAggregateIntervalMinutes = chartState.fixedAggregateIntervalMillis / (60 * 1000);
        if (fixedAggregateIntervalMinutes > 1) {
          // this is the normal case since default aggregate interval is 5 min
          var minutesRoundedDownToNearestAggregationInterval =
              fixedAggregateIntervalMinutes * Math.floor(now.getMinutes() / fixedAggregateIntervalMinutes);
          now.setMinutes(minutesRoundedDownToNearestAggregationInterval);
        }
        $scope.chartFrom = Math.max(now.getTime() - 105 * 60 * 1000, today.getTime());
        $scope.chartTo = Math.min($scope.chartFrom + 120 * 60 * 1000, today.getTime() + 24 * 60 * 60 * 1000);
      }
    }

    function initChart($chart, chartState, $scope, chartOptions) {
      $scope.$watchCollection('[containerWidth, windowHeight]', function () {
        chartState.plot.resize();
        chartState.plot.setupGrid();
        chartState.plot.draw();
      });

      $scope.showChartSpinner = 0;

      $chart.bind('plotzoom', function (event, plot) {
        $scope.$apply(function () {
          // throw up spinner right away
          $scope.showChartSpinner++;
          $scope.showTableOverlay++;
          // need to call setupGrid on each zoom to handle rapid zooming
          plot.setupGrid();
          var zoomId = ++chartState.currentZoomId;
          // use 100 millisecond delay to handle rapid zooming
          $timeout(function () {
            if (zoomId === chartState.currentZoomId) {
              $scope.chartFrom = plot.getAxes().xaxis.min;
              $scope.chartTo = plot.getAxes().xaxis.max;
              chartState.chartFromToDefault = false;
              chartState.refreshData();
            }
            $scope.showChartSpinner--;
            $scope.showTableOverlay--;
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
          $scope.chartFrom = chartState.plot.getAxes().xaxis.min;
          $scope.chartTo = chartState.plot.getAxes().xaxis.max;
          chartState.chartFromToDefault = false;
          chartState.currentRefreshId++;
          chartState.refreshData();
        });
      });

      $scope.zoomOut = function () {
        // need to execute this outside of $apply since code assumes it needs to do its own $apply
        $timeout(function () {
          chartState.plot.zoomOut();
        });
      };

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
        },
        tooltip: true,
        tooltipOpts: {
          content: function (label, xval, yval, flotItem) {
            return renderTooltipHtml(flotItem.dataIndex, flotItem.seriesIndex, chartState);
          }
        }
      };
      // render chart with no data points
      chartState.plot = $.plot($chart, [[]], $.extend(true, options, chartOptions));
      chartState.plot.getAxes().xaxis.options.borderGridLock = chartState.fixedAggregateIntervalMillis;

      chartState.plot.getAxes().yaxis.options.max = undefined;
      chartState.refreshData();
    }

    function renderTooltipHtml(dataIndex, highlightSeriesIndex, chartState) {
      var html = '<table><tbody>';
      var total = 0;
      var plotData = chartState.plot.getData();
      for (var seriesIndex = 0; seriesIndex < plotData.length; seriesIndex++) {
        var dataSeries = plotData[seriesIndex];
        var value = dataSeries.data[dataIndex][1];
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
        '<td><strong>' + value.toFixed(3) + '</strong></td>' +
        '</tr>';
        total += value;
      }
      if (total === 0) {
        return 'No data';
      }
      html += '</tbody></table>';
      return html;
    }

    return {
      initFilter: initFilter,
      initChart: initChart,
      refreshData: refreshData,
      refreshButtonClick: refreshButtonClick
    };
  }
]);
