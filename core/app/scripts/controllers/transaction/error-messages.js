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

/* global glowroot, moment, $ */

glowroot.controller('ErrorMessagesCtrl', [
  '$scope',
  '$http',
  '$location',
  'charts',
  'queryStrings',
  'httpErrors',
  function ($scope, $http, $location, charts, queryStrings, httpErrors) {

    $scope.$parent.activeTabItem = 'messages';

    var chartState = charts.createState();

    $scope.showChartOverlay = 0;

    var errorMessageLimit = 25;
    var dataSeriesExtra;

    $scope.$watchGroup(['chartFrom', 'chartTo'], function (oldValues, newValues) {
      if (newValues !== oldValues) {
        refreshData();
      }
    });

    function refreshData(deferred) {
      var date = $scope.filterDate;
      var refreshId = ++chartState.currentRefreshId;
      var query = {
        from: $scope.chartFrom,
        to: $scope.chartTo,
        transactionType: $scope.transactionType,
        transactionName: $scope.transactionName,
        includes: $scope.errorFilterIncludes,
        excludes: $scope.errorFilterExcludes,
        errorMessageLimit: errorMessageLimit
      };
      if (deferred) {
        $scope.showChartOverlay++;
      } else {
        $scope.showChartSpinner++;
      }
      $http.get('backend/error/messages' + queryStrings.encodeObject(query))
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
            httpErrors.handler($scope, deferred)(data, status);
          });
    }

    $scope.tracesQueryString = function (errorMessage) {
      var query = {};
      if ($scope.transactionType !== $scope.layout.defaultTransactionType) {
        query['transaction-type'] = $scope.transactionType;
      }
      query['transaction-name'] = $scope.transactionName;
      query.from = $scope.chartFrom;
      query.to = $scope.chartTo;
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
      refreshData();
    };

    function parseQuery(text) {
      var includes = [];
      var excludes = [];
      var i;
      var c;
      var currTerm;
      var inQuote;
      var inExclude;

      function pushCurrTerm(inExclude) {
        if (inExclude) {
          excludes.push(currTerm);
        } else {
          includes.push(currTerm);
        }
      }

      for (i = 0; i < text.length; i++) {
        c = text.charAt(i);
        if (currTerm !== undefined) {
          // inside quoted or non-quoted term
          if (c === inQuote || !inQuote && c === ' ') {
            // end of term (quoted or non-quoted)
            pushCurrTerm();
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
        pushCurrTerm();
      }
      $scope.errorFilterIncludes = includes;
      $scope.errorFilterExcludes = excludes;
    }

    // 100% yaxis max just for initial empty chart rendering
    var chartOptions = {
      yaxis: {
        max: 100,
        label: 'error percentage'
      },
      tooltip: true,
      tooltipOpts: {
        content: function (label, xval, yval) {
          if (yval === 0 && !dataSeriesExtra[xval]) {
            // this is synthetic point for initial upslope, gap or final downslope
            return 'No errors';
          }
          function smartFormat(millis) {
            if (millis % 60000 === 0) {
              return moment(millis).format('LT');
            } else {
              return moment(millis).format('LTS');
            }
          }
          var fixedAggregateIntervalMillis = 1000 * $scope.layout.fixedAggregateIntervalSeconds;
          var from = xval - fixedAggregateIntervalMillis;
          // this math is to deal with active aggregate
          from = Math.ceil(from / fixedAggregateIntervalMillis) * fixedAggregateIntervalMillis;
          var to = xval;
          var html = '<strong>' + smartFormat(from) + ' to ' + smartFormat(to) +
              '</strong><br>Error percentage: ' + yval.toFixed(1) +
              '<br>Error count: ' + dataSeriesExtra[xval][0] +
              '<br>Transaction count: ' + dataSeriesExtra[xval][1];
          return html;
        }
      }
    };

    charts.init(chartState, $('#chart'), $scope);
    charts.plot([[]], chartOptions, chartState, $('#chart'), $scope);
    refreshData();
  }
]);
