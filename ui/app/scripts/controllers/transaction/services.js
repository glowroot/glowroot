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

/* global glowroot, angular */

glowroot.controller('TransactionServicesCtrl', [
  '$scope',
  '$http',
  '$location',
  '$timeout',
  'locationChanges',
  'modals',
  'queryStrings',
  'httpErrors',
  function ($scope, $http, $location, $timeout, locationChanges, modals, queryStrings, httpErrors) {

    $scope.$parent.activeTabItem = 'services';

    if ($scope.hideMainContent()) {
      return;
    }

    $scope.showServiceCalls = false;
    $scope.showSpinner = 0;

    $scope.$watchGroup(['range.chartFrom', 'range.chartTo', 'range.chartRefresh'], function () {
      refreshData();
    });

    $scope.$watch('type', function () {
      if ($scope.type) {
        $location.search('type', $scope.type);
      } else {
        $location.search('type', null);
      }
    });

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
      if ($scope.type) {
        query.type = $scope.type;
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
      }
      $scope.type = $location.search().type;
    });

    function refreshData() {
      var query = {
        agentRollup: $scope.agentRollup,
        transactionType: $scope.transactionType,
        transactionName: $scope.transactionName,
        from: $scope.range.chartFrom,
        to: $scope.range.chartTo
      };

      $scope.showSpinner++;
      $http.get('backend/transaction/service-calls' + queryStrings.encodeObject(query))
          .success(function (data) {
            $scope.showSpinner--;
            if (data.overwritten) {
              $scope.showOverwrittenMessage = true;
              $scope.showServiceCalls = false;
              $scope.serviceCalls = [];
              return;
            }
            $scope.showServiceCalls = data.length;
            $scope.serviceCalls = data;
            var types = {};
            angular.forEach($scope.serviceCalls, function (serviceCall) {
              serviceCall.timePerExecution = serviceCall.totalDurationNanos / (1000000 * serviceCall.executionCount);
              if (types[serviceCall.type] === undefined) {
                types[serviceCall.type] = 0;
              }
              types[serviceCall.type] += serviceCall.totalDurationNanos;
            });
            $scope.types = Object.keys(types);
            $scope.types.sort(function (left, right) {
              return types[right] - types[left];
            });
            if ($scope.type && $scope.types.indexOf($scope.type) === -1) {
              $scope.types.push($scope.type);
            }
          })
          .error(function (data, status) {
            $scope.showSpinner--;
            httpErrors.handler($scope)(data, status);
          });
    }
  }
]);
