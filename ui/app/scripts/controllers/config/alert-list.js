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

glowroot.controller('ConfigAlertListCtrl', [
  '$scope',
  '$location',
  '$http',
  '$filter',
  'queryStrings',
  'httpErrors',
  function ($scope, $location, $http, $filter, queryStrings, httpErrors) {

    if ($scope.hideMainContent()) {
      return;
    }

    $scope.alertQueryString = function (alert) {
      var query = {};
      if ($scope.agentId) {
        query.agentId = $scope.agentId;
      } else if ($scope.agentRollupId) {
        query.agentRollupId = $scope.agentRollupId;
      }
      query.id = alert.id;
      return queryStrings.encodeObject(query);
    };

    $scope.alertText = function (alert) {
      if (alert.kind === 'transaction') {
        return alert.transactionType + ' - ' + alert.transactionPercentile
            + $scope.percentileSuffix(alert.transactionPercentile) + ' percentile over a '
            + alert.timePeriodSeconds / 60 + ' minute period exceeds ' + alert.thresholdMillis
            + ' milliseconds';
      } else if (alert.kind === 'gauge') {
        var threshold;
        if (alert.gaugeUnit === 'bytes') {
          threshold = $filter('gtBytes')(alert.gaugeThreshold);
        } else if (alert.gaugeUnit) {
          threshold = alert.gaugeThreshold + ' ' + alert.gaugeUnit;
        } else {
          threshold = alert.gaugeThreshold;
        }
        return 'Gauge - ' + alert.gaugeDisplay + ' - average over a ' + alert.timePeriodSeconds / 60
            + ' minute period exceeds ' + threshold;
      } else if (alert.kind === 'heartbeat') {
        return 'Heartbeat - no heartbeat received for ' + alert.timePeriodSeconds + ' seconds';
      } else if (alert.kind === 'ping') {
        return 'Ping - ' + alert.pingUrl;
      } else if (alert.kind === 'synthetic') {
        return 'Synthetic user test';
      } else {
        return 'Unknown alert kind ' + alert.kind;
      }
    };

    $scope.newQueryString = function () {
      if ($scope.agentRollupId) {
        if ($scope.layout.agentRollups[$scope.agentRollupId].agent) {
          return '?agent-id=' + encodeURIComponent($scope.agentRollupId) + '&new';
        } else {
          return '?agent-rollup-id=' + encodeURIComponent($scope.agentRollupId) + '&new';
        }
      }
      return '?new';
    };

    $http.get('backend/config/alerts?agent-rollup-id=' + encodeURIComponent($scope.agentRollupId))
        .then(function (response) {
          $scope.loaded = true;
          $scope.alerts = response.data;
        }, function (response) {
          httpErrors.handle(response, $scope);
        });
  }
]);
