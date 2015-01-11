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

/* global glowroot, moment */

glowroot.controller('TransactionHeaderCtrl', [
  '$scope',
  '$location',
  'queryStrings',
  function ($scope, $location, queryStrings) {

    $scope.$parent.chartFrom = Number($location.search().from);
    $scope.$parent.chartTo = Number($location.search().to);
    // both from and to must be supplied or neither will take effect
    if ($scope.chartFrom && $scope.chartTo) {
      $scope.$parent.filterDate = new Date($scope.chartFrom);
      $scope.$parent.filterDate.setHours(0, 0, 0, 0);
    } else {
      $scope.$parent.chartFromToDefault = true;
      var today = new Date();
      today.setHours(0, 0, 0, 0);
      $scope.$parent.filterDate = today;
      // show 2 hour interval, but nothing prior to today (e.g. if 'now' is 1am) or after today
      // (e.g. if 'now' is 11:55pm)
      var now = new Date();
      now.setSeconds(0, 0);
      var fixedAggregateIntervalMinutes = $scope.layout.fixedAggregateIntervalSeconds / 60;
      if (fixedAggregateIntervalMinutes > 1) {
        // this is the normal case since default aggregate interval is 5 min
        var minutesRoundedDownToNearestAggregationInterval =
            fixedAggregateIntervalMinutes * Math.floor(now.getMinutes() / fixedAggregateIntervalMinutes);
        now.setMinutes(minutesRoundedDownToNearestAggregationInterval);
      }
      $scope.$parent.chartFrom = Math.max(now.getTime() - 105 * 60 * 1000, today.getTime());
      $scope.$parent.chartTo = Math.min($scope.chartFrom + 120 * 60 * 1000, today.getTime() + 24 * 60 * 60 * 1000);
    }

    $scope.timeDisplay = function () {
      return moment($scope.chartFrom).format('LT') + ' to ' + moment($scope.chartTo).format('LT');
    };

    $scope.headerQueryString = function (transactionType) {
      var query = {};
      // add transaction-type first so it is first in url
      if (transactionType !== $scope.layout.defaultTransactionType) {
        query['transaction-type'] = transactionType;
      }
      if (!$scope.chartFromToDefault) {
        query.from = $scope.chartFrom;
        query.to = $scope.chartTo;
      }
      return queryStrings.encodeObject(query);
    };

    $scope.$watch('filterDate', function (newValue, oldValue) {
      if (newValue !== oldValue) {
        if (!$scope.filterDate) {
          // TODO display 'Missing date' message
          return;
        }
        $scope.$parent.filterDate = $scope.filterDate;
        var midnight = new Date($scope.chartFrom).setHours(0, 0, 0, 0);
        if (midnight !== $scope.filterDate.getTime()) {
          // filterDate has changed
          $scope.$parent.chartFromToDefault = false;
          $scope.$parent.chartFrom = $scope.filterDate.getTime() + ($scope.chartFrom - midnight);
          $scope.$parent.chartTo = $scope.filterDate.getTime() + ($scope.chartTo - midnight);
          $location.search($scope.buildQueryObject()).replace();
        }
      }
    });
  }
]);
