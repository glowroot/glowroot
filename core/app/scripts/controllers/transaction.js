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

/* global glowroot */

glowroot.controller('TransactionCtrl', [
  '$scope',
  '$location',
  'queryStrings',
  'headerDisplay',
  'shortName',
  'defaultTabUrl',
  'defaultSummarySortOrder',
  function ($scope, $location, queryStrings, headerDisplay, shortName, defaultTabUrl, defaultSummarySortOrder) {
    // \u00b7 is &middot;
    document.title = headerDisplay + ' \u00b7 Glowroot';
    $scope.$parent.activeNavbarItem = shortName;

    $scope.headerDisplay = headerDisplay;
    $scope.shortName = shortName;
    $scope.defaultTabUrl = defaultTabUrl;
    $scope.defaultSummarySortOrder = defaultSummarySortOrder;

    $scope.transactionType = $location.search()['transaction-type'] || $scope.layout.defaultTransactionType;
    $scope.transactionName = $location.search()['transaction-name'];

    $scope.tabQueryString = function () {
      return queryStrings.encodeObject($scope.buildQueryObject());
    };

    $scope.buildQueryObject = function () {
      var query = {};
      if ($scope.transactionType !== $scope.layout.defaultTransactionType) {
        query['transaction-type'] = $scope.transactionType;
      }
      query['transaction-name'] = $scope.transactionName;
      if (!$scope.chartFromToDefault) {
        query.from = $scope.chartFrom;
        query.to = $scope.chartTo;
      }
      if ($scope.summarySortOrder !== $scope.defaultSummarySortOrder) {
        query['summary-sort-order'] = $scope.summarySortOrder;
      }
      return query;
    };

    $scope.$watch('summarySortOrder', function (oldValues, newValues) {
      if (newValues !== oldValues) {
        $location.search($scope.buildQueryObject()).replace();
      }
    });
  }
]);
