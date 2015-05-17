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

/* global glowroot, HandlebarsRendering, $ */

glowroot.controller('TransactionProfileCtrl', [
  '$scope',
  '$http',
  'queryStrings',
  'httpErrors',
  function ($scope, $http, queryStrings, httpErrors) {

    $scope.$parent.activeTabItem = 'profile';

    $scope.showProfile = false;
    $scope.showSpinner = 0;

    $scope.flameGraphQueryString = function () {
      var query = {};
      if ($scope.transactionType !== $scope.layout.defaultTransactionType) {
        query['transaction-type'] = $scope.transactionType;
      }
      query['transaction-name'] = $scope.transactionName;
      query.from = $scope.chartFrom;
      query.to = $scope.chartTo;
      return queryStrings.encodeObject(query);
    };

    $scope.$watchGroup(['chartFrom', 'chartTo', 'chartRefresh'], function (newValues, oldValues) {
      if (newValues !== oldValues) {
        refreshData();
      }
    });

    function refreshData() {
      var query = {
        from: $scope.chartFrom,
        to: $scope.chartTo,
        transactionType: $scope.transactionType,
        transactionName: $scope.transactionName,
        truncateLeafPercentage: 0.001
      };

      $scope.showSpinner++;
      $http.get('backend/transaction/profile' + queryStrings.encodeObject(query))
          .success(function (data) {
            $scope.showSpinner--;
            $scope.showProfile = data.sampleCount;
            if ($scope.showProfile) {
              $scope.sampleCount = data.sampleCount;
              $('#profileOuter').removeData('gtLoaded');
              HandlebarsRendering.profileToggle(undefined, '#profileOuter', data);
            }
          })
          .error(function (data, status) {
            $scope.showSpinner--;
            httpErrors.handler($scope)(data, status);
          });
    }

    refreshData();
  }
]);
