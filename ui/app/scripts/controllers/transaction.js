/*
 * Copyright 2013-2018 the original author or authors.
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

/* global glowroot, angular, moment, $ */

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

    if (!$scope.layout.central && $scope.layout.embeddedAgentRollup.display) {
      $scope.headerDisplay = $scope.layout.embeddedAgentRollup.display;
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
      return queryStrings.encodeObject(query);
    };

    // TODO this is exact duplicate of same function in gauge-values.js
    $scope.applyLast = function () {
      if (!$scope.range.last) {
        return;
      }
      var now = moment().startOf('second').valueOf();
      var from = now - $scope.range.last;
      var to = now + $scope.range.last / 10;
      var dataPointIntervalMillis = charts.getDataPointIntervalMillis(from, to);
      var revisedFrom = Math.floor(from / dataPointIntervalMillis) * dataPointIntervalMillis;
      var revisedTo = Math.ceil(to / dataPointIntervalMillis) * dataPointIntervalMillis;
      var revisedDataPointIntervalMillis = charts.getDataPointIntervalMillis(revisedFrom, revisedTo);
      if (revisedDataPointIntervalMillis !== dataPointIntervalMillis) {
        // expanded out to larger rollup threshold so need to re-adjust
        // ok to use original from/to instead of revisedFrom/revisedTo
        revisedFrom = Math.floor(from / revisedDataPointIntervalMillis) * revisedDataPointIntervalMillis;
        revisedTo = Math.ceil(to / revisedDataPointIntervalMillis) * revisedDataPointIntervalMillis;
      }
      $scope.range.chartFrom = revisedFrom;
      $scope.range.chartTo = revisedTo;
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
      $scope.applyLast();
    }

    // need to defer listener registration, otherwise captures initial location change sometimes
    $timeout(function () {
      $scope.$on('$locationChangeSuccess', onLocationChangeSuccess);
    });
    onLocationChangeSuccess();

    $scope.$watchGroup(['range.last', 'range.chartFrom', 'range.chartTo'],
        function (newValues, oldValues) {
          if (newValues !== oldValues) {
            $location.search($scope.buildQueryObject());
          }
        });

    $scope.tabQueryString = function () {
      return queryStrings.encodeObject($scope.buildQueryObject({}));
    };

    $scope.buildQueryObject = function (baseQuery, allowSeconds) {
      var query = baseQuery || angular.copy($location.search());
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
      if (!$scope.range.last) {
        if (allowSeconds) {
          query.from = $scope.range.chartFrom;
          query.to = $scope.range.chartTo;
        } else {
          query.from = Math.floor($scope.range.chartFrom / 60000) * 60000;
          query.to = Math.ceil($scope.range.chartTo / 60000) * 60000;
        }
        delete query.last;
      } else if ($scope.range.last !== 4 * 60 * 60 * 1000) {
        query.last = $scope.range.last;
        delete query.from;
        delete query.to;
      }
      if ($scope.summarySortOrder !== $scope.defaultSummarySortOrder) {
        query['summary-sort-order'] = $scope.summarySortOrder;
      } else {
        delete query['summary-sort-order'];
      }
      return query;
    };

    $scope.currentTabUrl = function () {
      return $location.path().substring(1);
    };

    if ($scope.layout.central) {

      $scope.$watchGroup(['range.chartFrom', 'range.chartTo'], function (newValue, oldValue) {
        if (newValue !== oldValue) {
          // need to refresh selectpicker in order to update hrefs of the items
          $timeout(function () {
            // timeout is needed so this runs after dom is updated
            $('#agentRollupDropdown').selectpicker('refresh');
          });
        }
      });

      var refreshAgentRollups = function () {
        $scope.refreshAgentRollups($scope.range.chartFrom, $scope.range.chartTo, $scope);
      };

      $('#agentRollupDropdown').on('show.bs.select', refreshAgentRollups);

      if ($scope.agentRollups === undefined) {
        refreshAgentRollups();
      }
    }
  }
]);
