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

    var appliedFilter;

    function updateAggregates(deferred) {
      var query = angular.copy(appliedFilter);
      delete query.error;
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
      appliedFilter.includes = includes;
      appliedFilter.excludes = excludes;
    }

    $scope.refreshButtonClick = function (deferred) {
      $scope.parsingError = undefined;
      parseQuery($scope.filter.error);
      if ($scope.parsingError) {
        deferred.reject($scope.parsingError);
        return;
      }
      var midnight = new Date(appliedFilter.from).setHours(0, 0, 0, 0);
      if (midnight !== $scope.filterDate.getTime()) {
        // filterDate has changed
        filterFromToDefault = false;
        appliedFilter.from = $scope.filterDate.getTime() + (appliedFilter.from - midnight);
        appliedFilter.to = $scope.filterDate.getTime() + (appliedFilter.to - midnight);
      }
      angular.extend(appliedFilter, $scope.filter);
      updateLocation();
      updateAggregates(deferred);
    };

    $scope.tracesQueryString = function (aggregate) {
      return queryStrings.encodeObject({
        // from is adjusted because aggregates are really aggregates of interval before aggregate timestamp
        from: appliedFilter.from,
        to: appliedFilter.to,
        transactionName: aggregate.transactionName,
        transactionNameComparator: 'equals',
        error: aggregate.error,
        errorComparator: 'equals'
      });
    };

    $scope.showMoreAggregates = function (deferred) {
      // double each time, but don't double $scope.filter.limit so that normal limit will be used on next search
      appliedFilter.limit *= 2;
      updateAggregates(deferred);
    };

    var filterFromToDefault;

    appliedFilter = {};
    appliedFilter.from = Number($location.search().from);
    appliedFilter.to = Number($location.search().to);
    // both from and to must be supplied or neither will take effect
    if (appliedFilter.from && appliedFilter.to) {
      $scope.filterDate = new Date(appliedFilter.from);
      $scope.filterDate.setHours(0, 0, 0, 0);
    } else {
      filterFromToDefault = true;
      var today = new Date();
      today.setHours(0, 0, 0, 0);
      $scope.filterDate = today;
      appliedFilter.from = $scope.filterDate.getTime();
      appliedFilter.to = appliedFilter.from + 24 * 60 * 60 * 1000;
    }
    appliedFilter.error = $location.search().error || '';
    appliedFilter.limit = 25;

    $scope.filter = angular.copy(appliedFilter);
    // need to remove from and to so they aren't copied back during angular.extend(appliedFilter, $scope.filter)
    delete $scope.filter.from;
    delete $scope.filter.to;

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
        query.from = appliedFilter.from;
        query.to = appliedFilter.to;
      }
      if (appliedFilter.error) {
        query.error = appliedFilter.error;
      }
      $location.search(query).replace();
    }

    parseQuery(appliedFilter.error);
    if (!$scope.parsingError) {
      updateAggregates();
    }
  }
]);
