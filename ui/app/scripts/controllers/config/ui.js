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

/* global glowroot, angular */

glowroot.controller('ConfigUiCtrl', [
  '$scope',
  '$http',
  '$rootScope',
  '$location',
  'confirmIfHasChanges',
  'httpErrors',
  function ($scope, $http, $rootScope, $location, confirmIfHasChanges, httpErrors) {

    // initialize page binding object
    $scope.page = {};

    if ($scope.hideMainContent()) {
      return;
    }

    $scope.$watch('page.defaultPercentiles', function (newVal) {
      if ($scope.config) {
        var percentiles = [];
        if (newVal) {
          angular.forEach(newVal.split(','), function (percentile) {
            percentile = percentile.trim();
            if (percentile.length) {
              percentiles.push(Number(percentile));
            }
          });
        }
        $scope.config.defaultPercentiles = percentiles;
      }
    });

    $scope.hasChanges = function () {
      return $scope.originalConfig && !angular.equals($scope.config, $scope.originalConfig);
    };
    $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    function onNewData(data) {
      $scope.loaded = true;
      $scope.config = data.config;
      $scope.originalConfig = angular.copy(data.config);
      $scope.page = {};
      $scope.allGauges = data.allGauges;
      var allGaugeNames = [];
      angular.forEach(data.allGauges, function (gauge) {
        allGaugeNames.push(gauge.name);
      });
      angular.forEach(data.config.defaultGaugeNames, function (defaultGaugeName) {
        if (allGaugeNames.indexOf(defaultGaugeName) === -1) {
          $scope.allGauges.push({
            name: defaultGaugeName,
            display: defaultGaugeName + ' (not available)',
            disabled: true
          });
        }
      });

      if ($scope.config.defaultPercentiles) {
        $scope.page.defaultPercentiles = $scope.config.defaultPercentiles.join(', ');
      }
    }

    $scope.save = function (deferred) {
      var postData = angular.copy($scope.config);
      $http.post('backend/config/ui?agent-rollup-id=' + encodeURIComponent($scope.agentRollupId), postData)
          .then(function (response) {
            onNewData(response.data);
            deferred.resolve('Saved');
          }, function (response) {
            httpErrors.handle(response, $scope, deferred);
          });
    };

    $http.get('backend/config/ui?agent-rollup-id=' + encodeURIComponent($scope.agentRollupId))
        .then(function (response) {
          onNewData(response.data);
        }, function (response) {
          httpErrors.handle(response, $scope);
        });
  }
]);
