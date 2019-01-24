/*
 * Copyright 2015-2019 the original author or authors.
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

glowroot.controller('ConfigAlertListCtrl', [
  '$scope',
  '$location',
  '$http',
  '$filter',
  '$interval',
  'modals',
  'queryStrings',
  'httpErrors',
  function ($scope, $location, $http, $filter, $interval, modals, queryStrings, httpErrors) {

    $scope.page = {};

    if ($scope.hideMainContent()) {
      return;
    }

    $scope.disableForNextUnits = [
      {
        display: 'minutes',
        value: 'minutes'
      },
      {
        display: 'hours',
        value: 'hours'
      },
      {
        display: 'days',
        value: 'days'
      }
    ];

    $scope.alertQueryString = function (alert) {
      var query = {};
      if ($scope.agentId) {
        query.agentId = $scope.agentId;
      } else if ($scope.agentRollupId) {
        query.agentRollupId = $scope.agentRollupId;
      }
      query.v = alert.version;
      return queryStrings.encodeObject(query);
    };

    $scope.newQueryString = function () {
      var queryString = $scope.agentQueryString();
      if (queryString === '') {
        return '?new';
      } else {
        return queryString + '&new';
      }
    };

    function onNewData(data) {
      $scope.alerts = data.alerts;
      $scope.disabledForNextMillis = data.disabledForNextMillis;
      if ($scope.disabledForNextMillis) {
        // this is needed so that gtDuration filter never displays milliseconds (on the very last countdown)
        $scope.disabledForNextMillis = Math.floor($scope.disabledForNextMillis / 1000) * 1000;
      }
    }

    $scope.displayDisableAlertingModal = function () {
      $scope.page.disableForNextUnit = 'hours';
      $scope.page.disableForNext = 1;
      $scope.changeAlertingDisabledTime = false;
      modals.display('#disableAlertingModal', true);
    };

    $scope.disableAlerting = function () {
      var disableForNextMillis;
      if ($scope.page.disableForNextUnit === 'minutes') {
        disableForNextMillis = $scope.page.disableForNext * 60 * 1000;
      } else if ($scope.page.disableForNextUnit === 'hours') {
        disableForNextMillis = $scope.page.disableForNext * 60 * 1000 * 60;
      } else if ($scope.page.disableForNextUnit === 'days') {
        disableForNextMillis = $scope.page.disableForNext * 60 * 1000 * 60 * 24;
      }
      var postData = {
        disableForNextMillis: disableForNextMillis
      };
      $scope.disablingAlerting = true;
      var url = 'backend/config/disable-alerting?agent-rollup-id=' + encodeURIComponent($scope.agentRollupId);
      $http.post(url, postData)
          .then(function (response) {
            $scope.disablingAlerting = false;
            onNewData(response.data);
            $('#disableAlertingModal').modal('hide');
          }, function (response) {
            $scope.disablingAlerting = false;
            httpErrors.handle(response);
          });
    };

    $scope.reEnableAlerting = function (deferred) {
      var postData = {
        disableForNextMillis: null,
      };
      var url = 'backend/config/re-enable-alerting?agent-rollup-id=' + encodeURIComponent($scope.agentRollupId);
      $http.post(url, postData)
          .then(function (response) {
            onNewData(response.data);
            deferred.resolve('Alerting enabled');
          }, function (response) {
            httpErrors.handle(response, deferred);
          });
    };

    $scope.changeAlertingDisabledTimeModal = function () {
      $scope.page.disableForNextUnit = 'hours';
      $scope.page.disableForNext = 1;
      $scope.changeAlertingDisabledTime = true;
      modals.display('#disableAlertingModal', true);
    };

    $http.get('backend/config/alerts?agent-rollup-id=' + encodeURIComponent($scope.agentRollupId))
        .then(function (response) {
          $scope.loaded = true;
          onNewData(response.data);
        }, function (response) {
          httpErrors.handle(response);
        });

    var promise = $interval(function () {
      if ($scope.disabledForNextMillis) {
        $scope.disabledForNextMillis = Math.max($scope.disabledForNextMillis - 1000, 0);
      }
    }, 1000);

    $scope.$on('$destroy', function () {
      $interval.cancel(promise);
    });
  }
]);
