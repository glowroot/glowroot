/*
 * Copyright 2013-2014 the original author or authors.
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

/* global glowroot, angular */

glowroot.controller('ErrorsCtrl', [
  '$scope',
  '$location',
  '$http',
  'queryStrings',
  function ($scope, $location, $http, queryStrings) {
    // \u00b7 is &middot;
    document.title = 'Errors \u00b7 Glowroot';
    $scope.$parent.title = 'Captured errors';
    $scope.$parent.activeNavbarItem = 'errors';

    $scope.showTableSpinner = 0;

    function updateAggregates(deferred) {
      var query = angular.copy($scope.filter);
      // +1 just to find out if there are more and to show "Show more" button, the extra (+1th) will not be displayed
      query.limit++;
      $scope.showTableSpinner++;
      $http.get('backend/error/aggregates?' + queryStrings.encodeObject(query))
          .success(function (data) {
            $scope.loaded = true;
            $scope.showTableSpinner--;
            $scope.error = false;
            if (data.length === query.limit) {
              data.pop();
              $scope.hasMoreAggregates = true;
            } else {
              $scope.hasMoreAggregates = false;
            }
            $scope.aggregates = data;
            if (deferred) {
              deferred.resolve();
            }
          })
          .error(function (data, status) {
            $scope.loaded = true;
            $scope.showTableSpinner--;
            if (status === 0) {
              $scope.error = 'Unable to connect to server';
            } else {
              $scope.error = 'An error occurred';
            }
            if (deferred) {
              deferred.reject($scope.error);
            }
          });
    }

    $scope.refreshButtonClick = function (deferred) {
      var midnight = new Date($scope.filter.from).setHours(0, 0, 0, 0);
      if (midnight !== $scope.filterDate.getTime()) {
        // filterDate has changed
        filterFromToDefault = false;
        $scope.filter.from = $scope.filterDate.getTime() + ($scope.filter.from - midnight);
        $scope.filter.to = $scope.filterDate.getTime() + ($scope.filter.to - midnight);
      }
      updateLocation();
      updateAggregates(deferred);
    };

    $scope.tracesQueryString = function (aggregate) {
      return queryStrings.encodeObject({
        // from is adjusted because aggregates are really aggregates of interval before aggregate timestamp
        from: $scope.filter.from,
        to: $scope.filter.to,
        transactionName: aggregate.transactionName,
        transactionNameComparator: 'equals',
        error: aggregate.error,
        errorComparator: 'equals'
      });
    };

    // TODO consolidate with same list in traces.js
    $scope.filterTextComparatorOptions = [
      {
        display: 'Begins with',
        value: 'begins'
      },
      {
        display: 'Equals',
        value: 'equals'
      },
      {
        display: 'Ends with',
        value: 'ends'
      },
      {
        display: 'Contains',
        value: 'contains'
      },
      {
        display: 'Does not contain',
        value: 'not_contains'
      }
    ];

    $scope.showMoreAggregates = function (deferred) {
      // double each time
      $scope.filter.limit *= 2;
      updateAggregates(deferred);
    };

    var filterFromToDefault;

    $scope.filter = {};
    $scope.filter.from = Number($location.search().from);
    $scope.filter.to = Number($location.search().to);
    // both from and to must be supplied or neither will take effect
    if ($scope.filter.from && $scope.filter.to) {
      $scope.filterDate = new Date($scope.filter.from);
      $scope.filterDate.setHours(0, 0, 0, 0);
    } else {
      filterFromToDefault = true;
      var today = new Date();
      today.setHours(0, 0, 0, 0);
      $scope.filterDate = today;
      $scope.filter.from = $scope.filterDate.getTime();
      $scope.filter.to = $scope.filter.from + 24 * 60 * 60 * 1000;
    }
    $scope.filter.errorComparator = $location.search()['error-comparator'] || 'contains';
    $scope.filter.error = $location.search().error || '';
    $scope.filter.limit = 25;

    $scope.$watch('filter.from', function (newValue, oldValue) {
      if (newValue !== oldValue) {
        filterFromToDefault = false;
      }
    });

    $scope.$watch('filter.to', function (newValue, oldValue) {
      if (newValue !== oldValue) {
        filterFromToDefault = false;
      }
    });

    function updateLocation() {
      var query = {};
      if (!filterFromToDefault) {
        query.from = $scope.filter.from;
        query.to = $scope.filter.to;
      }
      if ($scope.filter.error) {
        query['error-comparator'] = $scope.filter.errorComparator;
        query.error = $scope.filter.error;
      }
      $location.search(query).replace();
    }

    updateAggregates();
  }
]);
