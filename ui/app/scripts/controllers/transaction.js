/*
 * Copyright 2013-2023 the original author or authors.
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

/* global glowroot, angular, $ */

glowroot.controller('TransactionCtrl', [
  '$scope',
  '$location',
  '$http',
  '$timeout',
  'queryStrings',
  'charts',
  'headerDisplay',
  'shortName',
  'defaultSummarySortOrder',
  function ($scope, $location, $http, $timeout, queryStrings, charts, headerDisplay, shortName, defaultSummarySortOrder) {

    // \u00b7 is &middot;
    document.title = headerDisplay + ' \u00b7 Glowroot';
    $scope.$parent.activeNavbarItem = shortName;

    if (!$scope.layout.central && $scope.layout.embeddedAgentRollup.topLevelDisplay) {
      $scope.headerDisplay = $scope.layout.embeddedAgentRollup.topLevelDisplay;
    } else {
      $scope.headerDisplay = headerDisplay;
    }
    $scope.shortName = shortName;
    $scope.defaultSummarySortOrder = defaultSummarySortOrder;

    $scope.range = {};

    $scope.hideTransactionTypeDropdown = function () {
      var agentRollup = $scope.agentRollup;
      if (!agentRollup) {
        // show empty dropdown
        return false;
      }
      var transactionTypes = agentRollup.transactionTypes;
      if (!transactionTypes) {
        // show empty dropdown
        return false;
      }
      if (transactionTypes.length === 1 && transactionTypes[0] === $scope.transactionType) {
        return true;
      }
      return false;
    };

    $scope.hideMainContent = function () {
      return ($scope.layout.central && !$scope.agentRollupId) || !$scope.transactionType;
    };

    $scope.headerQueryString = function (agentRollupId, transactionType) {
      var query = {};
      if ($scope.layout.central) {
        if (agentRollupId) {
          // this is from agent dropdown
          if ($scope.isRollup(agentRollupId)) {
            query['agent-rollup-id'] = agentRollupId;
          } else {
            query['agent-id'] = agentRollupId;
          }
        } else {
          // this is from transaction dropdown
          var agentId = $location.search()['agent-id'];
          if (agentId) {
            query['agent-id'] = agentId;
          } else {
            query['agent-rollup-id'] = $location.search()['agent-rollup-id'];
          }
        }
      }
      if (transactionType) {
        query['transaction-type'] = transactionType;
      }
      if ($scope.range.last) {
        if ($scope.range.last !== 4 * 60 * 60 * 1000) {
          query.last = $scope.range.last;
        }
      } else {
        query.from = $scope.range.chartFrom;
        query.to = $scope.range.chartTo;
      }
      if ($location.search()['summary-sort-order']) {
        query['summary-sort-order'] = $location.search()['summary-sort-order'];
      }
      return queryStrings.encodeObject(query);
    };

    function onLocationChangeSuccess() {
      // can't use locationChanges service here because transaction.js covers multiple url paths
      if ($location.path().lastIndexOf('/' + shortName + '/', 0) === -1) {
        return;
      }
      $scope.transactionType = $location.search()['transaction-type'];
      $scope.transactionName = $location.search()['transaction-name'];
      $scope.range.last = Number($location.search().last);
      $scope.range.chartFrom = Number($location.search().from);
      $scope.range.chartTo = Number($location.search().to);
      // both from and to must be supplied or neither will take effect
      if (!isNaN($scope.range.chartFrom) && !isNaN($scope.range.chartTo)) {
        $scope.range.last = 0;
      } else if (!$scope.range.last) {
        $scope.range.last = 4 * 60 * 60 * 1000;
      }
      $scope.summarySortOrder = $location.search()['summary-sort-order'] || $scope.defaultSummarySortOrder;

      // always re-apply last in order to reflect the latest time
      charts.applyLast($scope);
    }

    // need to defer listener registration, otherwise captures initial location change sometimes
    $timeout(function () {
      $scope.$on('$locationChangeSuccess', onLocationChangeSuccess);
    });
    onLocationChangeSuccess();

    $scope.$watchGroup(['range.last', 'range.chartFrom', 'range.chartTo'],
        function (newValues, oldValues) {
          if (newValues !== oldValues) {
            if ($location.path() === '/transaction/traces') {
              $location.search($scope.buildQueryObjectForTraceTab(true));
            } else {
              $location.search($scope.buildQueryObject(true));
            }
          }
        });

    $scope.tabQueryString = function () {
      return queryStrings.encodeObject($scope.buildQueryObject());
    };

    $scope.buildQueryObject = function (overlayExtras) {
      return buildQueryObject(overlayExtras, $scope.range.last);
    };

    $scope.buildQueryObjectForChartRange = function (last) {
      var query = buildQueryObject(true, last);
      if ($location.path() === '/transaction/traces' || $location.path() === '/error/traces') {
        // clear duration filter on zooming out to avoid confusion
        delete query['duration-millis-low'];
        delete query['duration-millis-high'];
      }
      return query;
    };

    $scope.buildQueryObjectForTraceTab = function (overlayExtras) {
      return buildQueryObject(overlayExtras, $scope.range.last, true);
    };

    function buildQueryObject(overlayExtras, last, allowSeconds) {
      var query = {};
      if ($scope.layout.central) {
        var agentId = $location.search()['agent-id'];
        if (agentId) {
          query['agent-id'] = agentId;
        } else {
          query['agent-rollup-id'] = $location.search()['agent-rollup-id'];
        }
      }
      query['transaction-type'] = $scope.transactionType;
      query['transaction-name'] = $scope.transactionName;
      if (!last) {
        if (allowSeconds) {
          query.from = $scope.range.chartFrom;
          query.to = $scope.range.chartTo;
        } else {
          query.from = Math.floor($scope.range.chartFrom / 60000) * 60000;
          query.to = Math.ceil($scope.range.chartTo / 60000) * 60000;
        }
        delete query.last;
      } else if (last !== 4 * 60 * 60 * 1000) {
        query.last = last;
        delete query.from;
        delete query.to;
      }
      if ($scope.summarySortOrder !== $scope.defaultSummarySortOrder) {
        query['summary-sort-order'] = $scope.summarySortOrder;
      } else {
        delete query['summary-sort-order'];
      }
      if (overlayExtras) {
        var overlay = angular.copy($location.search());
        delete overlay['agent-id'];
        delete overlay['agent-rollup-id'];
        delete overlay['transaction-type'];
        delete overlay['transaction-name'];
        delete overlay.from;
        delete overlay.to;
        delete overlay.last;
        delete overlay['summary-sort-order'];
        angular.extend(query, overlay);
      }
      return query;
    }

    $scope.currentTabUrl = function () {
      return $location.path().substring(1);
    };

    if ($scope.layout.central) {

      $scope.$watchGroup(['range.chartFrom', 'range.chartTo'], function (newValue, oldValue) {
        if (newValue !== oldValue) {
          // need to refresh selectpicker in order to update hrefs of the items
          $timeout(function () {
            // timeout is needed so this runs after dom is updated
            $('#topLevelAgentRollupDropdown').selectpicker('refresh');
            $('#childAgentRollupDropdown').selectpicker('refresh');
          });
        }
      });

      var refreshTopLevelAgentRollups = function () {
        $scope.refreshTopLevelAgentRollups($scope.range.chartFrom, $scope.range.chartTo);
      };
      var refreshChildAgentRollups = function () {
        $scope.refreshChildAgentRollups($scope.range.chartFrom, $scope.range.chartTo);
      };

      $('#topLevelAgentRollupDropdown').on('show.bs.select', refreshTopLevelAgentRollups);
      $('#childAgentRollupDropdown').on('show.bs.select', refreshChildAgentRollups);

      if ($scope.topLevelAgentRollups === undefined) {
        refreshTopLevelAgentRollups();
        refreshChildAgentRollups();
      }
    }
  }
]);
