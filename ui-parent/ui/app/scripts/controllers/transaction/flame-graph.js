/*
 * Copyright 2014-2015 the original author or authors.
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

/* global glowroot, gtParseIncludesExcludes, $, moment */

glowroot.controller('TransactionFlameGraphCtrl', [
  '$scope',
  '$location',
  '$http',
  'httpErrors',
  'queryStrings',
  function ($scope, $location, $http, httpErrors, queryStrings) {
    // \u00b7 is &middot;
    document.title = 'Transactions \u00b7 Glowroot';
    $scope.$parent.activeNavbarItem = 'transaction';

    $scope.from = Number($location.search().from);
    $scope.to = Number($location.search().to);
    $scope.last = Number($location.search().last);
    $scope.transactionType = $location.search()['transaction-type'] || $scope.layout.defaultTransactionType;
    $scope.transactionName = $location.search()['transaction-name'];
    $scope.filter = $location.search().filter;
    // larger truncate-branch-percentage compared to tree view
    // because svg flame graph is very slow with finer grained leafs
    // (especially removing it from the dom when going to another page)
    // plus it's pretty confusing visually (and very tall vertically) with very fine grained leafs
    $scope.truncateBranchPercentage = $location.search()['truncate-branch-percentage'] || 1.0;

    if (!$scope.last && (!$scope.from || !$scope.to)) {
      $scope.last = 4 * 60 * 60 * 1000;
    }

    if ($scope.last) {
      var now = moment().startOf('second').valueOf();
      $scope.from = now - $scope.last;
      $scope.to = now + $scope.last / 10;
    }

    var parseResult = gtParseIncludesExcludes($scope.filter);

    if (parseResult.error) {
      $scope.parsingError = parseResult.error;
      $scope.loaded = true;
    } else {
      var query = {
        serverGroup: $scope.serverGroup,
        transactionType: $scope.transactionType,
        transactionName: $scope.transactionName,
        from: $scope.from,
        to: $scope.to,
        include: parseResult.includes,
        exclude: parseResult.excludes,
        truncateBranchPercentage: $scope.truncateBranchPercentage
      };
      $http.get('backend/transaction/flame-graph' + queryStrings.encodeObject(query))
          .success(function (data) {
            $scope.loaded = true;
            if (data[''].svTotal === 0) {
              $scope.chartNoData = true;
            } else {
              window.svRawData = data;
              window.svInit();
            }
          })
          .error(httpErrors.handler($scope));
    }

    function escapeKeyHandler(e) {
      // esc key
      if (e.keyCode === 27) {
        window.svDetailClose();
      }
    }

    $(document).on('keydown', escapeKeyHandler);
    $scope.$on('$destroy', function () {
      $(document).off('keydown', escapeKeyHandler);
    });
  }
]);
