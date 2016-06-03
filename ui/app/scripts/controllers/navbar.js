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

/* global glowroot */

glowroot.controller('NavbarCtrl', [
  '$scope',
  '$location',
  'queryStrings',
  function ($scope, $location, queryStrings) {

    $scope.queryString = function (preserveAgentSelection, preserveTransactionType) {
      var query = {};
      if (preserveAgentSelection) {
        query['agent-rollup'] = $location.search()['agent-rollup'];
        query['agent-id'] = $location.search()['agent-id'];
      }
      if (preserveTransactionType) {
        var transactionType = $location.search()['transaction-type'];
        if (!transactionType) {
          transactionType = $scope.defaultTransactionType();
        }
        if (transactionType) {
          query['transaction-type'] = transactionType;
        }
      }
      var last = $location.search().last;
      if (last) {
        query.last = last;
      }
      var from = $location.search().from;
      var to = $location.search().to;
      if (from !== undefined && to !== undefined) {
        query.from = from;
        query.to = to;
      }
      return queryStrings.encodeObject(query);
    };

    $scope.configQueryString = function () {
      if ($scope.agentPermissions && $scope.agentPermissions.config.view) {
        return '?agent-id=' + encodeURIComponent($scope.agentId || $scope.agentRollup);
      } else {
        return '';
      }
    };

    $scope.isAnonymous = function () {
      return $scope.layout && $scope.layout.username && $scope.layout.username.toLowerCase() === 'anonymous';
    };

    $scope.gearIconUrl = function () {
      if (!$scope.layout) {
        return '';
      }
      if ($scope.layout.fat && ($scope.agentPermissions && $scope.agentPermissions.config.view || $scope.layout.admin)) {
        return 'config/transaction';
      } else if (!$scope.layout.fat && $scope.layout.admin) {
        return 'admin/user-list';
      } else {
        return 'change-password';
      }
    };

    $scope.gearIconNavbarTitle = function () {
      if (!$scope.layout) {
        return '';
      }
      if ($scope.layout.fat && ($scope.agentPermissions && $scope.agentPermissions.config.view || $scope.layout.admin)) {
        return 'Configuration';
      } else if (!$scope.layout.fat && $scope.layout.admin) {
        return 'Administration';
      } else {
        return 'Profile';
      }
    };
  }
]);
