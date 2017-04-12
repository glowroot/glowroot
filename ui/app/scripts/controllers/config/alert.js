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

/* global glowroot, angular */

glowroot.controller('ConfigAlertCtrl', [
  '$scope',
  '$location',
  '$http',
  'confirmIfHasChanges',
  'httpErrors',
  function ($scope, $location, $http, confirmIfHasChanges, httpErrors) {

    // initialize page binding object
    $scope.page = {};

    var version = $location.search().v;

    function onNewData(data) {
      $scope.config = data.config;
      $scope.originalConfig = angular.copy(data.config);

      if (data.heading) {
        if (data.config.timePeriodSeconds === undefined) {
          $scope.page.timePeriodMinutes = undefined;
        } else {
          $scope.page.timePeriodMinutes = data.config.timePeriodSeconds / 60;
        }
        $scope.heading = data.heading;
        $scope.emailAddresses = data.config.emailAddresses.join(', ');
      } else {
        $scope.heading = '<New>';
      }
      $scope.gauges = data.gauges;
      $scope.syntheticMonitors = data.syntheticMonitors;
    }

    $scope.unit = function () {
      if (!$scope.gauges) {
        // list of gauges hasn't loaded yet
        return '';
      }
      var i;
      for (i = 0; i < $scope.gauges.length; i++) {
        if ($scope.gauges[i].name === $scope.config.gaugeName) {
          return $scope.gauges[i].unit;
        }
      }
      return '';
    };

    if (version) {
      $http.get('backend/config/alerts?agent-rollup-id=' + encodeURIComponent($scope.agentRollupId) + '&version=' + version)
          .then(function (response) {
            onNewData(response.data);
            $scope.loaded = true;
          }, function (response) {
            httpErrors.handle(response, $scope);
          });
    } else {
      var url = 'backend/config/alert-dropdowns?agent-rollup-id=' + encodeURIComponent($scope.agentRollupId)
          + '&version=' + version;
      $http.get(url)
          .then(function (response) {
            onNewData({
              config: {
                kind: 'transaction',
                transactionType: $scope.defaultTransactionType(),
                emailAddresses: []
              },
              gauges: response.data.gauges,
              syntheticMonitors: response.data.syntheticMonitors
            });
            $scope.loaded = true;
          }, function (response) {
            httpErrors.handle(response, $scope);
          });
    }

    $scope.$watch('config.kind', function (newValue, oldValue) {
      if (!$scope.config) {
        return;
      }
      if (oldValue === undefined) {
        return;
      }
      $scope.config.transactionType = undefined;
      $scope.config.transactionPercentile = undefined;
      $scope.config.minTransactionCount = undefined;
      $scope.config.gaugeName = undefined;
      $scope.config.gaugeThreshold = undefined;
      $scope.config.syntheticMonitorId = undefined;
      $scope.config.thresholdMillis = undefined;
      $scope.config.timePeriodSeconds = undefined;
      $scope.page.timePeriodMinutes = undefined;
      if (newValue === 'transaction') {
        $scope.config.transactionType = $scope.defaultTransactionType();
      }
    });

    $scope.$watch('page.timePeriodMinutes', function (newValue) {
      if (!$scope.config) {
        return;
      }
      if (newValue === undefined) {
        $scope.config.timePeriodSeconds = undefined;
      } else {
        $scope.config.timePeriodSeconds = newValue * 60;
      }
    });

    $scope.$watch('emailAddresses', function (newValue) {
      if (newValue) {
        var emailAddresses = [];
        angular.forEach(newValue.split(','), function (emailAddress) {
          emailAddress = emailAddress.trim();
          if (emailAddress.length) {
            emailAddresses.push(emailAddress);
          }
        });
        $scope.config.emailAddresses = emailAddresses;
      } else if ($scope.config) {
        $scope.config.emailAddresses = [];
      }
    });

    $scope.hasChanges = function () {
      return !angular.equals($scope.config, $scope.originalConfig);
    };
    var removeConfirmIfHasChangesListener = $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    $scope.save = function (deferred) {
      var postData = angular.copy($scope.config);
      var url;
      if (version) {
        url = 'backend/config/alerts/update';
      } else {
        url = 'backend/config/alerts/add';
      }
      var agentId = $scope.agentId;
      var agentRollupId = $scope.agentRollupId;
      $http.post(url + '?agent-rollup-id=' + encodeURIComponent(agentRollupId), postData)
          .then(function (response) {
            onNewData(response.data);
            deferred.resolve(version ? 'Saved' : 'Added');
            version = response.data.config.version;
            // fix current url (with updated version) before returning to list page in case back button is used later
            if (agentId) {
              $location.search({'agent-id': agentId, v: version}).replace();
            } else if (agentRollupId) {
              $location.search({'agent-rollup-id': agentRollupId, v: version}).replace();
            } else {
              $location.search({v: version}).replace();
            }
          }, function (response) {
            httpErrors.handle(response, $scope, deferred);
          });
    };

    $scope.delete = function (deferred) {
      var postData = {
        version: $scope.config.version
      };
      var agentId = $scope.agentId;
      var agentRollupId = $scope.agentRollupId;
      $http.post('backend/config/alerts/remove?agent-rollup-id=' + encodeURIComponent(agentRollupId), postData)
          .then(function () {
            removeConfirmIfHasChangesListener();
            if (agentId) {
              $location.url('config/alert-list?agent-id=' + encodeURIComponent(agentId)).replace();
            } else if (agentRollupId) {
              $location.url('config/alert-list?agent-rollup-id=' + encodeURIComponent(agentRollupId)).replace();
            } else {
              $location.url('config/alert-list').replace();
            }
          }, function (response) {
            httpErrors.handle(response, $scope, deferred);
          });
    };
  }
]);
