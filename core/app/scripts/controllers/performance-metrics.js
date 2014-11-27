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

/* global glowroot, HandlebarsRendering, angular, $ */

glowroot.controller('PerformanceMetricsCtrl', [
  '$scope',
  '$location',
  '$filter',
  '$http',
  '$q',
  '$timeout',
  'httpErrors',
  'keyedColorPools',
  'queryStrings',
  function ($scope, $location, $filter, $http, $q, $timeout, httpErrors, keyedColorPools, queryStrings) {
    // \u00b7 is &middot;
    document.title = 'Performance \u00b7 Glowroot';
    $scope.$parent.activeNavbarItem = 'performance';

    var plot;

    var fixedAggregateIntervalMillis = $scope.layout.fixedAggregateIntervalSeconds * 1000;

    var currentRefreshId = 0;
    var currentZoomId = 0;

    var $chart = $('#chart');

    var keyedColorPool = keyedColorPools.create();

    var chartFromToDefault;

    $scope.$watchCollection('[containerWidth, windowHeight]', function () {
      plot.resize();
      plot.setupGrid();
      plot.draw();
    });

    $scope.showChartSpinner = 0;
    $scope.showTableOverlay = 0;

    $scope.transactionsQueryString = function () {
      var query = {
        transactionType: $scope.transactionType,
        from: $scope.chartFrom,
        to: $scope.chartTo
      };
      return queryStrings.encodeObject(query);
    };

    $scope.tracesQueryString = function () {
      var query = {
        transactionType: $scope.transactionType,
        transactionName: $scope.transactionName,
        transactionNameComparator: 'equals',
        from: $scope.chartFrom,
        to: $scope.chartTo
      };
      return queryStrings.encodeObject(query);
    };

    function refreshData() {
      var date = $scope.filterDate;
      var refreshId = ++currentRefreshId;
      var query = {
        from: $scope.chartFrom,
        to: $scope.chartTo,
        transactionType: $scope.transactionType,
        transactionName: $scope.transactionName
      };
      $scope.showChartSpinner++;
      $scope.showTableOverlay++;
      $http.get('backend/performance/metrics?' + queryStrings.encodeObject(query))
          .success(function (data) {
            $scope.showChartSpinner--;
            $scope.showTableOverlay--;
            if (refreshId !== currentRefreshId) {
              return;
            }
            $scope.chartNoData = !data.dataSeries.length;
            // reset axis in case user changed the date and then zoomed in/out to trigger this refresh
            plot.getAxes().xaxis.options.min = query.from;
            plot.getAxes().xaxis.options.max = query.to;
            plot.getAxes().xaxis.options.zoomRange = [
              date.getTime(),
                  date.getTime() + 24 * 60 * 60 * 1000
            ];
            var plotData = [];
            var labels = [];
            angular.forEach(data.dataSeries, function (dataSeries) {
              labels.push(dataSeries.name ? dataSeries.name : 'Other');
            });
            keyedColorPool.reset(labels);
            angular.forEach(data.dataSeries, function (dataSeries, index) {
              var label = labels[index];
              plotData.push({
                data: dataSeries.data,
                label: label,
                color: keyedColorPool.get(label)
              });
            });
            if (plotData.length) {
              plot.setData(plotData);
            } else {
              plot.setData([
                []
              ]);
            }
            plot.setupGrid();
            plot.draw();

            $scope.mergedAggregate = data.mergedAggregate;
            if ($scope.mergedAggregate.count) {
              updateTreeMetrics();
              updateFlattenedMetrics();
              var $profileOuter = $('#profileOuter');
              if (!$profileOuter.hasClass('hide')) {
                $profileOuter.addClass('hide');
                $profileOuter.data('loaded', false);
              }
              var $profileFilter = $profileOuter.find('.profile-filter');
              var $profile = $profileOuter.find('.profile');
              $profileFilter.html('');
              $profile.html('');
            } else {
              $('#detail').html('No data');
            }
            $scope.traceCount = data.traceCount;
          })
          .error(function (data, status) {
            $scope.showChartSpinner--;
            $scope.showTableOverlay--;
            if (refreshId !== currentRefreshId) {
              return;
            }
            httpErrors.handler($scope)(data, status);
          });
    }

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

    function updateTreeMetrics() {
      var treeMetrics = [];

      function traverse(metric, nestingLevel) {
        metric.nestingLevel = nestingLevel;
        treeMetrics.push(metric);
        if (metric.nestedMetrics) {
          metric.nestedMetrics.sort(function (a, b) {
            return b.totalMicros - a.totalMicros;
          });
          $.each(metric.nestedMetrics, function (index, nestedMetric) {
            traverse(nestedMetric, nestingLevel + 1);
          });
        }
      }

      traverse($scope.mergedAggregate.metrics, 0);

      $scope.treeMetrics = treeMetrics;
    }

    function updateFlattenedMetrics() {
      var flattenedMetricMap = {};
      var flattenedMetrics = [];

      function traverse(metric, parentMetricNames) {
        var flattenedMetric = flattenedMetricMap[metric.name];
        if (!flattenedMetric) {
          flattenedMetric = {
            name: metric.name,
            totalMicros: metric.totalMicros,
            count: metric.count
          };
          flattenedMetricMap[metric.name] = flattenedMetric;
          flattenedMetrics.push(flattenedMetric);
        } else if (parentMetricNames.indexOf(metric.name) === -1) {
          // only add to existing flattened metric if the aggregate metric isn't appearing under itself
          // (this is possible when they are separated by another aggregate metric)
          flattenedMetric.totalMicros += metric.totalMicros;
          flattenedMetric.count += metric.count;
        }
        if (metric.nestedMetrics) {
          $.each(metric.nestedMetrics, function (index, nestedMetric) {
            traverse(nestedMetric, parentMetricNames.concat(metric));
          });
        }
      }

      traverse($scope.mergedAggregate.metrics, []);

      flattenedMetrics.sort(function (a, b) {
        return b.totalMicros - a.totalMicros;
      });

      $scope.flattenedMetrics = flattenedMetrics;
    }

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

    function renderTooltipHtml(dataIndex, highlightSeriesIndex) {
      var html = '<table><tbody>';
      var total = 0;
      var plotData = plot.getData();
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

    $scope.toggleProfile = function (event) {
      var query = {
        from: $scope.chartFrom,
        to: $scope.chartTo,
        transactionType: $scope.transactionType,
        transactionName: $scope.transactionName
      };
      HandlebarsRendering.profileToggle($(event.target), '#profileOuter', null,
              'backend/performance/profile?' + queryStrings.encodeObject(query));
    };

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
            return renderTooltipHtml(flotItem.dataIndex, flotItem.seriesIndex);
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
