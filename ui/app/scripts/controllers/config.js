/*
 * Copyright 2012-2017 the original author or authors.
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

/* global glowroot, $ */

glowroot.controller('ConfigCtrl', [
  '$scope',
  '$location',
  '$timeout',
  'queryStrings',
  function ($scope, $location, $timeout, queryStrings) {
    // \u00b7 is &middot;
    document.title = 'Configuration \u00b7 Glowroot';
    $scope.$parent.activeNavbarItem = 'gears';

    $scope.hideMainContent = function () {
      return $scope.layout.central && !$scope.agentRollupId && !$scope.agentId;
    };

    $scope.percentileSuffix = function (percentile) {
      var text = String(percentile);
      if (text === '11' || /\.11$/.test(text)) {
        return 'th';
      }
      if (text === '12' || /\.12$/.test(text)) {
        return 'th';
      }
      if (text === '13' || /\.13$/.test(text)) {
        return 'th';
      }
      var lastChar = text.charAt(text.length - 1);
      if (lastChar === '1') {
        return 'st';
      }
      if (lastChar === '2') {
        return 'nd';
      }
      if (lastChar === '3') {
        return 'rd';
      }
      return 'th';
    };

    $scope.currentUrl = function () {
      return $location.path().substring(1);
    };

    $scope.isAgentRollup = function () {
      // using query string instead of layout.agentRollups[agentRollupId].agent in case agentRollupId doesn't exist
      return $location.search()['agent-rollup-id'];
    };

    function agentRollupUrl(path, agentRollup) {
      var query = $scope.agentRollupQuery(agentRollup);
      return path + queryStrings.encodeObject(query);
    }

    $scope.agentRollupUrl = function (agentRollup) {
      var path = $location.path().substring(1);
      if (agentRollup.agent) {
        return agentRollupUrl(path, agentRollup);
      }
      if (path === 'config/synthetic-monitor-list'
          || path === 'config/synthetic-monitor'
          || path === 'config/alert-list'
          || path === 'config/alert'
          || path === 'config/ui'
          || path === 'config/advanced') {
        return agentRollupUrl(path, agentRollup);
      } else {
        return agentRollupUrl('config/synthetic-monitor-list', agentRollup);
      }
    };

    $scope.selectedAgentRollup = $scope.agentRollupId;

    $scope.$watch(function () {
      return $location.search();
    }, function (newValue, oldValue) {
      if (newValue !== oldValue) {
        // need to refresh selectpicker in order to update hrefs of the items
        $timeout(function () {
          // timeout is needed so this runs after dom is updated
          $('#agentRollupDropdown').selectpicker('refresh');
        });
      }
    }, true);
  }
]);
