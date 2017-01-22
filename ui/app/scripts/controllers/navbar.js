/*
 * Copyright 2015-2017 the original author or authors.
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
      var onReportPage = $location.path().substr(0, '/report/'.length) === '/report/';
      if (preserveAgentSelection && !onReportPage) {
        query['agent-rollup-id'] = $location.search()['agent-rollup-id'];
        query['agent-id'] = $location.search()['agent-id'];
      }
      if (preserveTransactionType && !onReportPage) {
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
        // need floor/ceil when on trace point chart which allows second granularity
        query.from = Math.floor(from / 60000) * 60000;
        query.to = Math.ceil(to / 60000) * 60000;
      }
      return queryStrings.encodeObject(query);
    };

    $scope.configUrl = function () {
      if ($scope.layout.central && $scope.agentPermissions && $scope.agentPermissions.config.view) {
        // using query string instead of layout.agentRollups[agentRollupId].agent in case agentRollupId doesn't exist
        if ($location.search()['agent-rollup-id']) {
          return 'config/alert-list?agent-rollup-id=' + encodeURIComponent($scope.agentRollupId);
        } else {
          return 'config/transaction?agent-id=' + encodeURIComponent($scope.agentRollupId);
        }
      } else {
        return 'config/transaction';
      }
    };

    $scope.isAnonymous = function () {
      return $scope.layout && $scope.layout.username && $scope.layout.username.toLowerCase() === 'anonymous';
    };

    $scope.gearIconUrl = function () {
      if (!$scope.layout) {
        return '';
      }
      if ($scope.layout.central && $scope.layout.adminView) {
        return 'admin/agent-list';
      } else if (!$scope.layout.central
          && ($scope.agentPermissions && $scope.agentPermissions.config.view || $scope.layout.adminView)) {
        return 'config/transaction';
      } else {
        return 'change-password';
      }
    };

    $scope.gearIconNavbarTitle = function () {
      if (!$scope.layout) {
        return '';
      }
      if (!$scope.layout.central
          && ($scope.agentPermissions && $scope.agentPermissions.config.view || $scope.layout.adminView)) {
        return 'Configuration';
      } else if ($scope.layout.central && $scope.layout.adminView) {
        return 'Administration';
      } else {
        return 'Profile';
      }
    };
  }
]);
