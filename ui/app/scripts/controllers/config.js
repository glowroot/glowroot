/*
 * Copyright 2012-2018 the original author or authors.
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
  '$http',
  '$timeout',
  'queryStrings',
  function ($scope, $location, $http, $timeout) {
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

    function agentRollupUrl(path, agentRollupId) {
      if ($scope.isRollup(agentRollupId)) {
        return path + '?agent-rollup-id=' + encodeURIComponent(agentRollupId);
      } else {
        return path + '?agent-id=' + encodeURIComponent(agentRollupId);
      }
    }

    $scope.agentRollupUrl = function (agentRollupId) {
      var path = $location.path().substring(1);
      if (path === 'config/gauge') {
        path = 'config/gauge-list';
      } else if (path === 'config/synthetic-monitor') {
        path = 'config/synthetic-monitor-list';
      } else if (path === 'config/alert') {
        path = 'config/alert-list';
      } else if (path === 'config/plugin') {
        path = 'config/plugin-list';
      } else if (path === 'config/instrumentation') {
        path = 'config/instrumentation-list';
      }
      if ($scope.isRollup(agentRollupId)) {
        if (path !== 'config/general' && path !== 'config/synthetic-monitor-list' && path !== 'config/alert-list'
            && path !== 'config/ui-defaults' && path !== 'config/advanced') {
          path = 'config/general';
        }
      }
      return agentRollupUrl(path, agentRollupId);
    };

    if ($scope.layout.central) {

      $scope.$watch(function () {
        return $location.url();
      }, function (newValue, oldValue) {
        if (newValue !== oldValue) {
          // need to refresh selectpicker in order to update hrefs of the items
          $timeout(function () {
            // timeout is needed so this runs after dom is updated
            $('#agentRollupDropdown').selectpicker('refresh');
          });
        }
      }, true);

      var refreshAgentRollups = function () {
        var now = new Date().getTime();
        var from = now - 7 * 24 * 60 * 60 * 1000;
        // looking to the future just to be safe
        var to = now + 7 * 24 * 60 * 60 * 1000;
        $scope.refreshAgentRollups(from, to, $scope);
      };

      $('#agentRollupDropdown').on('show.bs.select', refreshAgentRollups);

      if ($scope.agentRollups === undefined) {
        refreshAgentRollups();
      }
    }
  }
]);
