/*
 * Copyright 2013-2015 the original author or authors.
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

glowroot.controller('ErrorsCtrl', [
  '$scope',
  '$location',
  '$filter',
  '$http',
  '$q',
  '$timeout',
  'charts',
  'keyedColorPools',
  'queryStrings',
  'httpErrors',
  function ($scope, $location, $filter, $http, $q, $timeout, charts, keyedColorPools, queryStrings, httpErrors) {
    // \u00b7 is &middot;
    document.title = 'Errors \u00b7 Glowroot';
    $scope.$parent.activeNavbarItem = 'errors';

    var chartState = {
      fixedAggregateIntervalMillis: $scope.layout.fixedAggregateIntervalSeconds * 1000,
      plot: undefined,
      currentRefreshId: 0,
      currentZoomId: 0,
      chartFromToDefault: false,
      refreshData: refreshData
    };

    $scope.defaultSummarySortOrder = 'error-count';
    $scope.summaryLimit = 10;

    $scope.showMoreSummariesSpinner = 0;
    $scope.showChartSpinner = 0;
    $scope.showChartOverlay = 0;

    var errorMessageLimit = 25;
    var dataSeriesExtra;

    function refreshData(deferred) {
      charts.updateLocation(chartState, $scope);
      var date = $scope.filterDate;
      var refreshId = ++chartState.currentRefreshId;
      var query = {
        from: $scope.chartFrom,
        to: $scope.chartTo,
        transactionType: $scope.transactionType,
        transactionName: $scope.transactionName,
        includes: $scope.errorFilterIncludes,
        excludes: $scope.errorFilterExcludes,
        summarySortOrder: $scope.summarySortOrder,
        summaryLimit: $scope.summaryLimit,
        errorMessageLimit: errorMessageLimit
      };
      if (deferred) {
        $scope.showChartOverlay++;
      } else {
        $scope.showChartSpinner++;
      }
      $http.get('backend/error/data?' + queryStrings.encodeObject(query))
          .success(function (data) {
            if (deferred) {
              $scope.showChartOverlay--;
            } else {
              $scope.showChartSpinner--;
            }
            if (refreshId !== chartState.currentRefreshId) {
              return;
            }
            $scope.chartNoData = !data.dataSeries.data.length;
            // reset axis in case user changed the date and then zoomed in/out to trigger this refresh
            chartState.plot.getAxes().xaxis.options.min = query.from;
            chartState.plot.getAxes().xaxis.options.max = query.to;
            chartState.plot.getAxes().xaxis.options.zoomRange = [
              date.getTime(),
              date.getTime() + 24 * 60 * 60 * 1000
            ];
            if (data.dataSeries.data.length) {
              chartState.plot.setData([{data: data.dataSeries.data}]);
            } else {
              chartState.plot.setData([[]]);
            }
            chartState.plot.setupGrid();
            chartState.plot.draw();
            dataSeriesExtra = data.dataSeriesExtra;

            $scope.moreErrorMessagesAvailable = data.moreErrorMessagesAvailable;
            $scope.errorMessages = data.errorMessages;
            $scope.traceCount = data.traceCount;
            charts.onRefreshUpdateSummaries(data, query.from, query.to, query.summarySortOrder, $scope);
            if (deferred) {
              deferred.resolve();
            }
          })
          .error(function (data, status) {
            if (deferred) {
              $scope.showChartOverlay--;
            } else {
              $scope.showChartSpinner--;
            }
            if (refreshId !== chartState.currentRefreshId) {
              return;
            }
            httpErrors.handler($scope, deferred)(data, status);
          });
    }

    $scope.$watch('filterDate', function (newValue, oldValue) {
      if (newValue && newValue !== oldValue) {
        $scope.refreshButtonClick();
      }
    });

    $scope.overallSummaryValue = function () {
      if ($scope.lastSummarySortOrder === 'error-count') {
        return $scope.overallSummary.errorCount;
      } else if ($scope.lastSummarySortOrder === 'error-rate') {
        return (100 * $scope.overallSummary.errorCount / $scope.overallSummary.transactionCount).toFixed(1) + ' %';
      } else {
        // TODO handle this better
        return '???';
      }
    };

    $scope.transactionSummaryValue = function (transactionSummary) {
      if ($scope.lastSummarySortOrder === 'error-count') {
        return transactionSummary.errorCount;
      } else if ($scope.lastSummarySortOrder === 'error-rate') {
        return (100 * transactionSummary.errorCount / transactionSummary.transactionCount).toFixed(1) + ' %';
      } else {
        // TODO handle this better
        return '???';
      }
    };

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

    $scope.showMoreErrorMessages = function (deferred) {
      // double each time
      errorMessageLimit *= 2;
      refreshData(deferred);
    };

    $scope.refreshButtonClick = function () {
      $scope.parsingError = undefined;
      parseQuery($scope.errorFilter || '');
      if ($scope.parsingError) {
        return;
      }
      charts.refreshButtonClick(chartState, $scope);
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

    $scope.summarySortOrder = $location.search()['summary-sort-order'] || 'error-count';
    charts.initScope(chartState, 'error', $scope);
    // 100% yaxis max just for initial empty chart rendering
    charts.initChart($('#chart'), chartState, $scope,
        {
          yaxis: {
            max: 100,
            label: 'error percentage'
          },
          tooltipOpts: {
            content: function (label, xval, yval) {
              if (yval === 0 && !dataSeriesExtra[xval]) {
                // this is synthetic point for initial upslope, gap or final downslope
                return 'No errors';
              }
              return 'Error percentage: ' + yval.toFixed(1) + '<br>Error count: ' + dataSeriesExtra[xval][0] +
                  '<br>Transaction count: ' + dataSeriesExtra[xval][1];
            }
          }
        });
  }
]);
