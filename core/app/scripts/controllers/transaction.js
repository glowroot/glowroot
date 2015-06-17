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

/* global glowroot, angular, moment */

glowroot.controller('TransactionCtrl', [
  '$scope',
  '$location',
  '$timeout',
  'queryStrings',
  'charts',
  'headerDisplay',
  'shortName',
  'defaultTabUrl',
  'defaultSummarySortOrder',
  function ($scope, $location, $timeout, queryStrings, charts, headerDisplay, shortName, defaultTabUrl,
            defaultSummarySortOrder) {
    // \u00b7 is &middot;
    document.title = headerDisplay + ' \u00b7 Glowroot';
    $scope.$parent.activeNavbarItem = shortName;

    $scope.headerDisplay = headerDisplay;
    $scope.shortName = shortName;
    $scope.defaultTabUrl = defaultTabUrl;
    $scope.defaultSummarySortOrder = defaultSummarySortOrder;

    $scope.applyLast = function () {
      if (!$scope.last) {
        return;
      }
      var dataPointIntervalMillis = charts.getDataPointIntervalMillis(0, 1.1 * $scope.last);
      var now = moment().startOf('second').valueOf();
      var from = now - $scope.last;
      var to = now + $scope.last / 10;
      var revisedFrom = Math.floor(from / dataPointIntervalMillis) * dataPointIntervalMillis;
      var revisedTo = Math.ceil(to / dataPointIntervalMillis) * dataPointIntervalMillis;
      var revisedDataPointIntervalMillis = charts.getDataPointIntervalMillis(revisedFrom, revisedTo);
      if (revisedDataPointIntervalMillis !== dataPointIntervalMillis) {
        // expanded out to larger rollup threshold so need to re-adjust
        // ok to use original from/to instead of revisedFrom/revisedTo
        revisedFrom = Math.floor(from / revisedDataPointIntervalMillis) * revisedDataPointIntervalMillis;
        revisedTo = Math.ceil(to / revisedDataPointIntervalMillis) * revisedDataPointIntervalMillis;
      }
      $scope.chartFrom = revisedFrom;
      $scope.chartTo = revisedTo;
    };

    var location;

    function onLocationChangeSuccess() {
      var priorLocation = location;
      location = {};
      location.transactionType = $location.search()['transaction-type'] || $scope.layout.defaultTransactionType;
      location.transactionName = $location.search()['transaction-name'];
      location.last = Number($location.search().last);
      location.chartFrom = Number($location.search().from);
      location.chartTo = Number($location.search().to);
      // both from and to must be supplied or neither will take effect
      if (location.chartFrom && location.chartTo) {
        location.last = 0;
      } else if (!location.last) {
        location.last = 4 * 60 * 60 * 1000;
      }
      location.summarySortOrder = $location.search()['summary-sort-order'] || $scope.defaultSummarySortOrder;

      if (!angular.equals(location, priorLocation)) {
        // only call applyLast if relevant change, e.g. do not call applyLast when opening trace modal
        // (applyLast can trigger new $scope chartFrom/chartTo which triggers chart refresh)
        angular.extend($scope, location);
        $scope.applyLast();
      }
    }

    // need to defer listener registration, otherwise captures initial location change sometimes
    $timeout(function () {
      $scope.$on('$locationChangeSuccess', onLocationChangeSuccess);
    });
    onLocationChangeSuccess();

    $scope.$watchGroup(['last', 'chartFrom', 'chartTo', 'summarySortOrder'], function (newValues, oldValues) {
      if (newValues !== oldValues) {
        $location.search($scope.buildQueryObject());
      }
    });

    $scope.tabQueryString = function () {
      return queryStrings.encodeObject($scope.buildQueryObject({}));
    };

    $scope.buildQueryObject = function (baseQuery) {
      var query = baseQuery || angular.copy($location.search());
      if ($scope.transactionType !== $scope.layout.defaultTransactionType) {
        query['transaction-type'] = $scope.transactionType;
      } else {
        delete query['transaction-type'];
      }
      query['transaction-name'] = $scope.transactionName;
      if (!$scope.last) {
        query.from = $scope.chartFrom;
        query.to = $scope.chartTo;
        delete query.last;
      } else if ($scope.last !== 4 * 60 * 60 * 1000) {
        query.last = $scope.last;
        delete query.from;
        delete query.to;
      }
      if ($scope.summarySortOrder !== $scope.defaultSummarySortOrder) {
        query['summary-sort-order'] = $scope.summarySortOrder;
      } else {
        delete query['summary-sort-order'];
      }
      return query;
    };

    $scope.currentTabUrl = function () {
      return $location.path().substring(1);
    };
  }
]);
