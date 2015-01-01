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

glowroot.controller('ErrorsMessagesCtrl', [
  '$scope',
  '$location',
  '$filter',
  '$http',
  '$q',
  '$timeout',
  'charts',
  'httpErrors',
  'queryStrings',
  function ($scope, $location, $filter, $http, $q, $timeout, charts, httpErrors, queryStrings) {
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

    var summaryLimit = 100;

    var dataSeriesExtra;

    $scope.showTableOverlay = 0;

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

    $scope.showMore = function (deferred) {
      // double each time
      summaryLimit *= 2;
      refreshData(deferred);
    };

    function refreshData(deferred) {
      updateLocation();
      var date = $scope.filterDate;
      var refreshId = ++chartState.currentRefreshId;
      var query = {
        from: $scope.chartFrom,
        to: $scope.chartTo,
        transactionType: $scope.transactionType,
        transactionName: $scope.transactionName,
        includes: $scope.errorFilterIncludes,
        excludes: $scope.errorFilterExcludes,
        limit: summaryLimit
      };
      $scope.showChartSpinner++;
      $http.get('backend/error/messages?' + queryStrings.encodeObject(query))
          .success(function (data) {
            $scope.showChartSpinner--;
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

            $scope.moreAvailable = data.moreAvailable;
            $scope.errorMessages = data.errorMessages;
            $scope.traceCount = data.traceCount;
            if (deferred) {
              deferred.resolve();
            }
          })
          .error(function (data, status) {
            $scope.showChartSpinner--;
            if (refreshId !== chartState.currentRefreshId) {
              return;
            }
            httpErrors.handler($scope, deferred)(data, status);
          });
    }

    function updateLocation() {
      var query = {
        'transaction-type': $scope.transactionType,
        'transaction-name': $scope.transactionName
      };
      if (!chartState.chartFromToDefault) {
        query.from = $scope.chartFrom - chartState.fixedAggregateIntervalMillis;
        query.to = $scope.chartTo;
      }
      $location.search(query).replace();
    }

    $scope.refreshButtonClick = function (deferred) {
      $scope.parsingError = undefined;
      parseQuery($scope.errorFilter || '');
      if ($scope.parsingError) {
        deferred.reject($scope.parsingError);
        return;
      }
      refreshData(deferred);
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

    $scope.filter = {};
    charts.initFilter(chartState, $scope);
    $scope.transactionType = $location.search()['transaction-type'];
    $scope.transactionName = $location.search()['transaction-name'];

    // 100% yaxis max just for initial empty chart rendering
    charts.initChart($('#chart'), chartState, $scope,
        {
          yaxis: {
            max: 100,
            label: 'error percentage'
          },
          tooltipOpts: {
            content: function (label, xval, yval, flotItem) {
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
