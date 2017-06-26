/*
 * Copyright 2014-2017 the original author or authors.
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

/* global glowroot, gtParseIncludesExcludes, d3, moment */

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

    $scope.transactionType = $location.search()['transaction-type'];
    $scope.transactionName = $location.search()['transaction-name'];
    $scope.from = Number($location.search().from);
    $scope.to = Number($location.search().to);
    $scope.last = Number($location.search().last);
    $scope.auxiliary = $location.search().auxiliary || false;
    $scope.filter = $location.search().filter;
    // larger truncate-branch-percentage compared to tree view
    // because svg flame graph is very slow with finer grained leafs
    // (especially removing it from the dom when going to another page)
    // plus it's pretty confusing visually (and very tall vertically) with very fine grained leafs
    $scope.truncateBranchPercentage = $location.search()['truncate-branch-percentage'] || 1.0;

    if (!$scope.last && (isNaN($scope.from) || isNaN($scope.to))) {
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
        agentRollupId: $scope.agentRollupId,
        transactionType: $scope.transactionType,
        transactionName: $scope.transactionName,
        from: $scope.from,
        to: $scope.to,
        auxiliary: $scope.auxiliary,
        include: parseResult.includes,
        exclude: parseResult.excludes,
        truncateBranchPercentage: $scope.truncateBranchPercentage
      };
      $http.get('backend/transaction/flame-graph' + queryStrings.encodeObject(query))
          .then(function (response) {
            $scope.loaded = true;
            var data = response.data;
            if (data.rootNodes.length === 0) {
              $scope.chartNoData = true;
            } else {
              var chartData;
              var height = data.height;
              if (data.rootNodes.length === 1) {
                chartData = data.rootNodes[0];
              } else {
                chartData = {
                  name: '<multiple root nodes>',
                  value: data.totalSampleCount,
                  children: data.rootNodes
                };
                height++;
              }
              var flameGraph = d3.flameGraph()
                  .height(height * 18)
                  .width(960);
              d3.select('#chart')
                  .datum(chartData)
                  .call(flameGraph);
            }
          }, function (response) {
            httpErrors.handle(response, $scope);
          });
    }
  }
]);
