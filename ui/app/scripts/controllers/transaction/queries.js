/*
 * Copyright 2015-2016 the original author or authors.
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

/* global glowroot, SqlPrettyPrinter, angular, $, gtClipboard, console */

glowroot.controller('TransactionQueriesCtrl', [
  '$scope',
  '$http',
  '$location',
  '$timeout',
  'locationChanges',
  'modals',
  'queryStrings',
  'httpErrors',
  function ($scope, $http, $location, $timeout, locationChanges, modals, queryStrings, httpErrors) {

    $scope.$parent.activeTabItem = 'queries';

    if ($scope.hideMainContent()) {
      return;
    }

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
      return window.innerWidth < 992;
    };

    $scope.sort = function () {
      $location.search('sort-attribute', null);
      $location.search('sort-direction', null);
    };

    $scope.sortQueryString = function (attributeName) {
      var query = $scope.buildQueryObject({});
      if (attributeName !== 'total-time') {
        query['sort-attribute'] = attributeName;
      }
      if ($scope.sortAttribute === attributeName && !$scope.sortReverse) {
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
      if ($scope.sortReverse) {
        return 'caret gt-caret-reversed';
      } else {
        return 'caret';
      }
    };

    locationChanges.on($scope, function () {
      $scope.sortAttribute = $location.search()['sort-attribute'] || 'total-time';
      $scope.sortReverse = $location.search()['sort-direction'] === 'asc';
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
    });

    $scope.showQueryModal = function (query) {
      // clear previous styling and content
      var $modalDialog = $('#queryModal .modal-dialog');
      $modalDialog.removeAttr('style');
      var closeButton = $('#queryModal button.close');
      closeButton.removeAttr('style');
      var $clipboardIcon = $('#queryModal .fa-clipboard');
      $clipboardIcon.removeAttr('style');
      var $unformattedQuery = $('#unformattedQuery');
      var $formattedQuery = $('#formattedQuery');
      $unformattedQuery.text('');
      $formattedQuery.html('');
      modals.display('#queryModal');

      function display(fullText) {
        $scope.unformattedQuery = fullText;
        $scope.formattedQuery = '';
        $scope.showFormatted = false;
        $scope.queryExpired = false;
        $unformattedQuery.text($scope.unformattedQuery);
        $unformattedQuery.show();
        $formattedQuery.hide();

        gtClipboard($clipboardIcon, function () {
          return $scope.showFormatted ? $formattedQuery[0] : $unformattedQuery[0];
        }, function () {
          return $scope.showFormatted ? $scope.formattedQuery : $scope.unformattedQuery;
        });

        if (query.queryType !== 'SQL') {
          return;
        }

        var formatted = SqlPrettyPrinter.format(fullText);
        if (typeof formatted === 'object') {
          // intentional console logging
          // need conditional since console does not exist in IE9 unless dev tools is open
          if (window.console) {
            console.log(formatted.message);
            console.log(fullText);
          }
          return;
        }
        $scope.formattedQuery = formatted;
        $scope.showFormatted = true;
        $formattedQuery.html($scope.formattedQuery);
        $unformattedQuery.hide();
        $formattedQuery.show();

        var width = Math.max($formattedQuery.width() + 80, 500);
        // +141 is needed for IE9 (other browsers seemed ok at +140)
        var height = $formattedQuery.height() + 141;
        var horizontalScrolling = width > $(window).width() - 50;
        if (horizontalScrolling) {
          height += 17;
        }
        var verticalScrolling = height > $(window).height() - 50;
        if (width < $modalDialog.width()) {
          $modalDialog.css('width', width + 'px');
          $modalDialog.css('left', '50%');
          $modalDialog.css('margin-left', -width / 2 + 'px');
          closeButton.css('right', 'auto');
          closeButton.css('left', '50%');
          var closeButtonLeftMargin = width / 2 - 46;
          if (!verticalScrolling) {
            closeButtonLeftMargin += 17;
          }
          closeButton.css('margin-left', closeButtonLeftMargin + 'px');
          $clipboardIcon.css('right', 'auto');
          $clipboardIcon.css('left', '50%');
          $clipboardIcon.css('margin-left', (closeButtonLeftMargin - 3) + 'px');
        }
        if (!verticalScrolling) {
          $modalDialog.css('overflow-y', 'auto');
          $modalDialog.css('height', height + 'px');
          $modalDialog.css('top', '50%');
          $modalDialog.css('margin-top', -height / 2 + 'px');
          $modalDialog.css('border-top-right-radius', '6px');
          $modalDialog.css('border-bottom-right-radius', '6px');
          closeButton.css('top', '50%');
          closeButton.css('margin-top', -height / 2 + 10 + 'px');
          $clipboardIcon.css('top', '50%');
          $clipboardIcon.css('margin-top', -height / 2 + 34 + 'px');
        }
        if (horizontalScrolling) {
          $modalDialog.css('border-bottom-left-radius', 0);
          $modalDialog.css('border-bottom-right-radius', 0);
        }
      }

      if (!query.fullQueryTextSha1) {
        display(query.truncatedQueryText);
        return;
      }

      var q = {
        agentRollup: $scope.agentRollup,
        fullTextSha1: query.fullQueryTextSha1
      };
      $scope.showModalSpinner++;
      $http.get('backend/transaction/full-query-text' + queryStrings.encodeObject(q))
          .success(function (data) {
            $scope.showModalSpinner--;
            if (data.expired) {
              $scope.queryExpired = true;
              return;
            }
            display(data.fullText);
          })
          .error(function (data, status) {
            $scope.loadingFullQueryText--;
            httpErrors.handler($scope)(data, status);
          });
    };

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
        agentRollup: $scope.agentRollup,
        transactionType: $scope.transactionType,
        transactionName: $scope.transactionName,
        from: $scope.range.chartFrom,
        to: $scope.range.chartTo
      };
      $scope.showSpinner++;
      $http.get('backend/transaction/queries' + queryStrings.encodeObject(query))
          .success(function (data) {
            $scope.showSpinner--;
            if (data.overwritten) {
              $scope.showOverwrittenMessage = true;
              $scope.showQueries = false;
              $scope.queries = [];
              return;
            }
            $scope.showQueries = data.length;
            $scope.queries = data;
            var queryTypes = {};
            angular.forEach($scope.queries, function (query) {
              query.timePerExecution = query.totalDurationNanos / (1000000 * query.executionCount);
              if (query.totalRows !== undefined) {
                query.rowsPerExecution = query.totalRows / query.executionCount;
              }
              if (queryTypes[query.queryType] === undefined) {
                queryTypes[query.queryType] = 0;
              }
              queryTypes[query.queryType] += query.totalDurationNanos;
            });
            $scope.queryTypes = Object.keys(queryTypes);
            $scope.queryTypes.sort(function (left, right) {
              return queryTypes[right] - queryTypes[left];
            });
            if ($scope.queryType && $scope.queryTypes.indexOf($scope.queryType) === -1) {
              $scope.queryTypes.push($scope.queryType);
            }
          })
          .error(function (data, status) {
            $scope.showSpinner--;
            httpErrors.handler($scope)(data, status);
          });
    }
  }
]);
