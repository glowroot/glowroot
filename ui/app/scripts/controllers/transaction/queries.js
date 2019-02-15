/*
 * Copyright 2015-2019 the original author or authors.
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

/* global glowroot, HandlebarsRendering, angular, $, gtClipboard, console */

glowroot.controller('TransactionQueriesCtrl', [
  '$scope',
  '$http',
  '$location',
  '$timeout',
  '$filter',
  'locationChanges',
  'charts',
  'modals',
  'queryStrings',
  'httpErrors',
  function ($scope, $http, $location, $timeout, $filter, locationChanges, charts, modals, queryStrings, httpErrors) {

    $scope.$parent.activeTabItem = 'queries';

    if ($scope.hideMainContent()) {
      return;
    }

    $scope.page = {};

    $scope.showQueries = false;
    $scope.showSpinner = 0;
    $scope.showModalSpinner = 0;

    $scope.$watchGroup(['range.chartFrom', 'range.chartTo', 'range.chartRefresh'], function () {
      refreshData();
    });

    $scope.$watch('queryType', function () {
      if ($scope.queryType) {
        $location.search('query-type', $scope.queryType);
      } else {
        $location.search('query-type', null);
      }
    });

    $scope.smallScreen = function () {
      // using innerWidth so it will match to screen media queries
      return window.innerWidth < 1200;
    };

    $scope.sortQueryString = function (attributeName) {
      var query = $scope.buildQueryObject();
      if (attributeName !== 'total-time' || ($scope.sortAttribute === 'total-time' && !$scope.sortAsc)) {
        query['sort-attribute'] = attributeName;
      }
      if ($scope.sortAttribute === attributeName && !$scope.sortAsc) {
        query['sort-direction'] = 'asc';
      }
      if ($scope.queryType) {
        query['query-type'] = $scope.queryType;
      }
      return queryStrings.encodeObject(query);
    };

    $scope.sortIconClass = function (attributeName) {
      if ($scope.sortAttribute !== attributeName) {
        return '';
      }
      if ($scope.sortAsc) {
        return 'gt-caret gt-caret-sort-ascending';
      } else {
        return 'gt-caret';
      }
    };

    $scope.ngAttrAriaSort = function (sortAttribute) {
      if (sortAttribute !== $scope.sortAttribute) {
        return undefined;
      }
      return $scope.sortAsc ? 'ascending' : 'descending';
    };

    var originalFrom = $location.search().from;
    var originalTo = $location.search().to;
    if (originalFrom !== undefined && originalTo !== undefined) {
      var dataPointIntervalMillis = charts.getDataPointIntervalMillis(originalFrom, originalTo,
          $scope.layout.queryAndServiceCallRollupExpirationMillis);
      var revisedFrom = Math.floor(originalFrom / dataPointIntervalMillis) * dataPointIntervalMillis;
      var revisedTo = Math.ceil(originalTo / dataPointIntervalMillis) * dataPointIntervalMillis;
      $location.search('from', revisedFrom);
      $location.search('to', revisedTo);
      $location.replace();
    }

    locationChanges.on($scope, function () {
      $scope.sortAttribute = $location.search()['sort-attribute'] || 'total-time';
      $scope.sortAsc = $location.search()['sort-direction'] === 'asc';
      if ($scope.sortAttribute === 'total-time') {
        $scope.sortAttr = '-totalDurationNanos';
      } else if ($scope.sortAttribute === 'execution-count') {
        $scope.sortAttr = '-executionCount';
      } else if ($scope.sortAttribute === 'time-per-execution') {
        $scope.sortAttr = '-timePerExecution';
      } else if ($scope.sortAttribute === 'rows-per-execution') {
        $scope.sortAttr = '-rowsPerExecution';
      }
      $scope.queryType = $location.search()['query-type'];

      var modalQueryType = $location.search()['modal-query-type'];
      var modalQueryText = $location.search()['modal-query-text'];
      var modalQueryTextSha1 = $location.search()['modal-query-text-sha1'];
      if (modalQueryText || modalQueryTextSha1) {
        $('#queryModal').data('location-query', [
          'modal-query-type',
          'modal-query-text',
          'modal-query-text-sha1'
        ]);
        displayModal(modalQueryType, modalQueryText, modalQueryTextSha1);
      } else {
        $('#queryModal').modal('hide');
      }
    });

    $scope.showQueryModal = function (query) {
      $location.search('modal-query-type', query.queryType);
      if (query.fullQueryTextSha1) {
        $location.search('modal-query-text-sha1', query.fullQueryTextSha1);
      } else {
        $location.search('modal-query-text', query.truncatedQueryText);
      }
    };

    function displayModal(modalQueryType, modalQueryText, modalQueryTextSha1) {
      // clear previous content
      var $unformattedQuery = $('#unformattedQuery');
      var $formattedQuery = $('#formattedQuery');
      $unformattedQuery.text('');
      $formattedQuery.html('');
      $scope.unformattedQuery = '';
      $scope.formattedQuery = '';
      $scope.showFormatted = false;
      $scope.queryExpired = false;
      $scope.queryError = false;

      var $modalDialog = $('#queryModal .modal-dialog');
      var $closeButton = $('#queryModal button.close');
      var $clipboardIcon = $('#queryModal .fa-clipboard');

      function clearCss() {
        $modalDialog.removeAttr('style');
        $closeButton.removeAttr('style');
        $clipboardIcon.removeAttr('style');
      }

      function applyCss() {
        var width = Math.max($formattedQuery.width() + 80, 500);
        // +141 is needed for IE9 (other browsers seemed ok at +140)
        if (width < $modalDialog.width()) {
          $modalDialog.css('width', width + 'px');
        }
      }

      // delay is to avoid flashing content when displaying blank modal briefly before full text has loaded
      var timer = $timeout(function () {
        clearCss();
        modals.display('#queryModal');
        applyCss();
      }, 200);

      function display(fullText) {
        if ($timeout.cancel(timer)) {
          modals.display('#queryModal');
        }
        clearCss();
        $scope.unformattedQuery = fullText;
        $scope.formattedQuery = '';
        $scope.showFormatted = false;
        $scope.queryExpired = false;
        $unformattedQuery.text($scope.unformattedQuery);
        $unformattedQuery.show();
        $formattedQuery.hide();
        $('#queryModal').find('.gt-clip').removeClass('d-none');

        gtClipboard($clipboardIcon, '#queryModal', function () {
          return $scope.showFormatted ? $scope.formattedQuery : $scope.unformattedQuery;
        });

        if (modalQueryType !== 'SQL') {
          applyCss();
          return;
        }

        var formatted = HandlebarsRendering.sqlPrettyPrint(fullText);
        if (typeof formatted === 'object') {
          console.log(formatted.message);
          console.log(fullText);
          applyCss();
          return;
        }
        $scope.formattedQuery = formatted;
        $scope.showFormatted = true;
        $formattedQuery.html($scope.formattedQuery);
        $unformattedQuery.hide();
        $formattedQuery.show();
        applyCss();
      }

      if (!modalQueryTextSha1) {
        display(modalQueryText);
        return;
      }

      var q = {
        agentRollupId: $scope.agentRollupId,
        fullTextSha1: modalQueryTextSha1
      };
      $scope.showModalSpinner++;
      $http.get('backend/transaction/full-query-text' + queryStrings.encodeObject(q))
          .then(function (response) {
            $scope.showModalSpinner--;
            if (response.data.expired) {
              $scope.queryExpired = true;
              return;
            }
            display(response.data.fullText);
          }, function () {
            $scope.showModalSpinner--;
            $scope.queryError = true;
          });
    }

    $scope.toggleFormatted = function () {
      $scope.showFormatted = !$scope.showFormatted;
      var $formattedQuery = $('#formattedQuery');
      var $unformattedQuery = $('#unformattedQuery');
      if ($scope.showFormatted) {
        $unformattedQuery.hide();
        $formattedQuery.show();
      } else {
        $unformattedQuery.show();
        $formattedQuery.hide();
      }
    };

    function refreshData() {
      var query = {
        agentRollupId: $scope.agentRollupId,
        transactionType: $scope.transactionType,
        transactionName: $scope.transactionName,
        from: $scope.range.chartFrom,
        to: $scope.range.chartTo
      };
      $scope.showSpinner++;
      $http.get('backend/transaction/queries' + queryStrings.encodeObject(query))
          .then(function (response) {
            $scope.showSpinner--;
            var data = response.data;
            if (data.overwritten) {
              $scope.showOverwrittenMessage = true;
              $scope.showQueries = false;
              $scope.queries = [];
              return;
            }
            $scope.showQueries = data.length;
            $scope.queries = data;
            var queryTypes = {};
            var maxTotalDurationNanos = 0;
            var maxExecutionCount = 0;
            var maxTimePerExecution = 0;
            var maxRowsPerExecution = 0;
            angular.forEach($scope.queries, function (query) {
              query.timePerExecution = query.totalDurationNanos / (1000000 * query.executionCount);
              if (query.totalRows === undefined) {
                query.rowsPerExecution = undefined;
              } else {
                query.rowsPerExecution = query.totalRows / query.executionCount;
              }
              if (queryTypes[query.queryType] === undefined) {
                queryTypes[query.queryType] = 0;
              }
              queryTypes[query.queryType] += query.totalDurationNanos;
              maxTotalDurationNanos = Math.max(maxTotalDurationNanos, query.totalDurationNanos);
              maxExecutionCount = Math.max(maxExecutionCount, query.executionCount);
              maxTimePerExecution = Math.max(maxTimePerExecution, query.timePerExecution);
              maxRowsPerExecution = Math.max(maxRowsPerExecution, query.rowsPerExecution);
            });
            var otherColumnsLength = HandlebarsRendering.formatMillis(maxTotalDurationNanos / 1000000).length
                + $filter('number')(maxExecutionCount).length
                + HandlebarsRendering.formatMillis(maxTimePerExecution).length
                + HandlebarsRendering.formatCount(maxRowsPerExecution).length;
            var maxQueryTextLength = 98 - otherColumnsLength * 0.6;
            angular.forEach($scope.queries, function (query) {
              if (query.truncatedQueryText.length > maxQueryTextLength) {
                query.text = query.truncatedQueryText.substring(0, maxQueryTextLength - 3) + '...';
              } else {
                query.text = query.truncatedQueryText;
              }
            });
            $scope.limitExceededBucket = undefined;
            for (var i = 0; i < $scope.queries.length; i++) {
              if ($scope.queries[i].text === 'LIMIT EXCEEDED BUCKET') {
                $scope.limitExceededBucket = $scope.queries[i];
                $scope.queries.splice(i, 1);
                break;
              }
            }
            $scope.queryTypes = Object.keys(queryTypes);
            $scope.queryTypes.sort(function (left, right) {
              return queryTypes[right] - queryTypes[left];
            });
            if ($scope.queryType && $scope.queryTypes.indexOf($scope.queryType) === -1) {
              $scope.queryTypes.push($scope.queryType);
            }
          }, function (response) {
            $scope.showSpinner--;
            httpErrors.handle(response);
          });
    }
  }
]);
