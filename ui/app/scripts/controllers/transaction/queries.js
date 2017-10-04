/*
 * Copyright 2015-2017 the original author or authors.
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

    $scope.sortQueryString = function (attributeName) {
      var query = $scope.buildQueryObject({});
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
        return 'caret gt-caret-sort-ascending';
      } else {
        return 'caret';
      }
    };

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

      function applyCss(loading) {
        var width = Math.max($formattedQuery.width() + 80, 500);
        // +141 is needed for IE9 (other browsers seemed ok at +140)
        var height = $formattedQuery.height() + 141;
        if (loading) {
          height = 200;
        }
        var horizontalScrolling = width > $(window).width() - 50;
        if (horizontalScrolling) {
          height += 17;
        }
        var verticalScrolling = height > $(window).height() - 50;
        if (width < $modalDialog.width()) {
          $modalDialog.css('width', width + 'px');
          $modalDialog.css('left', '50%');
          $modalDialog.css('margin-left', -width / 2 + 'px');
          $closeButton.css('right', 'auto');
          $closeButton.css('left', '50%');
          var closeButtonLeftMargin = width / 2 - 46;
          if (!verticalScrolling) {
            closeButtonLeftMargin += 17;
          }
          $closeButton.css('margin-left', closeButtonLeftMargin + 'px');
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
          $closeButton.css('top', '50%');
          $closeButton.css('margin-top', -height / 2 + 10 + 'px');
          $clipboardIcon.css('top', '50%');
          $clipboardIcon.css('margin-top', -height / 2 + 34 + 'px');
        }
        if (horizontalScrolling) {
          $modalDialog.css('border-bottom-left-radius', 0);
          $modalDialog.css('border-bottom-right-radius', 0);
        }
      }

      // delay is to avoid flashing content when displaying blank modal briefly before full text has loaded
      var timer = $timeout(function () {
        clearCss();
        modals.display('#queryModal');
        applyCss(true);
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

        gtClipboard($clipboardIcon, '#queryModal', function () {
          return $scope.showFormatted ? $formattedQuery[0] : $unformattedQuery[0];
        }, function () {
          return $scope.showFormatted ? $scope.formattedQuery : $scope.unformattedQuery;
        });

        if (modalQueryType !== 'SQL') {
          return;
        }

        var comment = '';
        if (fullText.lastIndexOf('/*', 0) === 0) {
          var endOfComment = fullText.indexOf('*/') + 2;
          comment = fullText.substring(0, endOfComment) + '\n';
          fullText = fullText.substring(endOfComment).trim();
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
        if (comment.length) {
          var spaces = '';
          for (var i = 0; i < formatted.length; i++) {
            if (formatted[i] === ' ') {
              spaces += ' ';
            } else {
              break;
            }
          }
          $scope.formattedQuery = spaces + comment + formatted;
        } else {
          $scope.formattedQuery = formatted;
        }
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
            angular.forEach($scope.queries, function (query) {
              query.timePerExecution = query.totalDurationNanos / (1000000 * query.executionCount);
              if (query.totalRows === undefined) {
                query.rowsPerExecution = -1;
              } else {
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
          }, function (response) {
            $scope.showSpinner--;
            httpErrors.handle(response, $scope);
          });
    }
  }
]);
