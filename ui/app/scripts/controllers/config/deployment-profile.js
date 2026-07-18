/*
 * Copyright 2026 the original author or authors.
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

glowroot.controller('ConfigDeploymentProfileCtrl', [
  '$scope',
  '$http',
  '$q',
  'confirmIfHasChanges',
  'httpErrors',
  'deploymentPresets',
  function ($scope, $http, $q, confirmIfHasChanges, httpErrors, deploymentPresets) {

    // Embedded-only page (central has different storage model)
    if ($scope.layout.central) {
      return;
    }

    if ($scope.hideMainContent()) {
      return;
    }

    $scope.page = {};
    $scope.profile = 'custom';
    $scope.loaded = false;

    var originalStorage;
    var originalTransaction;
    var originalAdvanced;
    var originalPage;

    $scope.canEdit = function () {
      return $scope.layout.adminEdit
          && $scope.agentRollup.permissions.config.edit.transaction
          && $scope.agentRollup.permissions.config.edit.advanced;
    };

    $scope.hasChanges = function () {
      return originalPage && !angular.equals($scope.page, originalPage);
    };
    $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    function hoursToDaysList(hoursList) {
      var days = [];
      angular.forEach(hoursList, function (hours) {
        days.push(hours / 24);
      });
      return days;
    }

    function daysToHoursList(daysList) {
      var hours = [];
      angular.forEach(daysList, function (days) {
        hours.push(days * 24);
      });
      return hours;
    }

    function cappedSizesUniform(sizesMb) {
      if (!sizesMb || !sizesMb.length) {
        return true;
      }
      var first = sizesMb[0];
      var i;
      for (i = 1; i < sizesMb.length; i++) {
        if (sizesMb[i] !== first) {
          return false;
        }
      }
      return true;
    }

    function pageFromLoaded(storage, transaction, advanced) {
      var sizesMb = storage.rollupCappedDatabaseSizesMb;
      return {
        rollupExpirationDays: hoursToDaysList(storage.rollupExpirationHours),
        traceExpirationDays: storage.traceExpirationHours / 24,
        fullQueryTextExpirationDays: storage.fullQueryTextExpirationHours / 24,
        rollupCappedDatabaseSizeMb: sizesMb && sizesMb.length ? sizesMb[0] : 500,
        // When per-level sizes differ, force Custom even if the first level matches a preset
        rollupCappedSizesNonUniform: !cappedSizesUniform(sizesMb),
        traceCappedDatabaseSizeMb: storage.traceCappedDatabaseSizeMb,
        slowThresholdMillis: transaction.slowThresholdMillis,
        profilingIntervalMillis: transaction.profilingIntervalMillis,
        captureThreadStats: transaction.captureThreadStats,
        maxTransactionAggregates: advanced.maxTransactionAggregates,
        maxQueryAggregates: advanced.maxQueryAggregates,
        maxServiceCallAggregates: advanced.maxServiceCallAggregates,
        maxTraceEntriesPerTransaction: advanced.maxTraceEntriesPerTransaction,
        maxProfileSamplesPerTransaction: advanced.maxProfileSamplesPerTransaction
      };
    }

    function matchedProfileName() {
      if ($scope.page.rollupCappedSizesNonUniform) {
        return 'custom';
      }
      return deploymentPresets.matchName($scope.page);
    }

    function syncProfileSelect() {
      $scope.profile = matchedProfileName();
    }

    $scope.onProfileChange = function () {
      if ($scope.profile === 'custom') {
        return;
      }
      // Populate only — does not persist until Save
      deploymentPresets.apply($scope.profile, $scope.page);
      $scope.page.rollupCappedSizesNonUniform = false;
    };

    $scope.$watch('page', function () {
      if (!$scope.loaded) {
        return;
      }
      // Keep dropdown in sync when user edits fields manually
      var matched = matchedProfileName();
      if ($scope.profile !== matched) {
        $scope.profile = matched;
      }
    }, true);

    function onLoaded(storage, transactionResponse, advanced) {
      originalStorage = storage;
      originalTransaction = transactionResponse.config;
      originalAdvanced = advanced;
      $scope.page = pageFromLoaded(storage, originalTransaction, advanced);
      originalPage = angular.copy($scope.page);
      syncProfileSelect();
      $scope.loaded = true;
    }

    $scope.save = function (deferred) {
      var rollupCapped = $scope.page.rollupCappedDatabaseSizeMb;
      var storagePayload = {
        rollupExpirationHours: daysToHoursList($scope.page.rollupExpirationDays),
        traceExpirationHours: $scope.page.traceExpirationDays * 24,
        fullQueryTextExpirationHours: $scope.page.fullQueryTextExpirationDays * 24,
        rollupCappedDatabaseSizesMb: [rollupCapped, rollupCapped, rollupCapped, rollupCapped],
        traceCappedDatabaseSizeMb: $scope.page.traceCappedDatabaseSizeMb,
        version: originalStorage.version
      };

      var transactionPayload = angular.copy(originalTransaction);
      transactionPayload.slowThresholdMillis = $scope.page.slowThresholdMillis;
      transactionPayload.profilingIntervalMillis = $scope.page.profilingIntervalMillis;
      transactionPayload.captureThreadStats = $scope.page.captureThreadStats;

      var advancedPayload = angular.copy(originalAdvanced);
      advancedPayload.maxTransactionAggregates = $scope.page.maxTransactionAggregates;
      advancedPayload.maxQueryAggregates = $scope.page.maxQueryAggregates;
      advancedPayload.maxServiceCallAggregates = $scope.page.maxServiceCallAggregates;
      advancedPayload.maxTraceEntriesPerTransaction = $scope.page.maxTraceEntriesPerTransaction;
      advancedPayload.maxProfileSamplesPerTransaction = $scope.page.maxProfileSamplesPerTransaction;

      // Sequential: stop on first failure so we never claim full success after a partial apply
      $http.post('backend/admin/storage', storagePayload)
          .then(function (storageResponse) {
            originalStorage = storageResponse.data;
            return $http.post('backend/config/transaction?agent-id='
                + encodeURIComponent($scope.agentId), transactionPayload);
          })
          .then(function (transactionResponse) {
            originalTransaction = transactionResponse.data.config;
            return $http.post('backend/config/advanced?agent-rollup-id='
                + encodeURIComponent($scope.agentRollupId), advancedPayload);
          })
          .then(function (advancedResponse) {
            originalAdvanced = advancedResponse.data;
            $scope.page = pageFromLoaded(originalStorage, originalTransaction, originalAdvanced);
            originalPage = angular.copy($scope.page);
            syncProfileSelect();
            deferred.resolve('Saved');
          }, function (response) {
            httpErrors.handle(response, deferred);
            // Reload versions / live values after a partial failure
            reloadAll();
          });
    };

    function reloadAll() {
      $q.all([
        $http.get('backend/admin/storage'),
        $http.get('backend/config/transaction?agent-id=' + encodeURIComponent($scope.agentId)),
        $http.get('backend/config/advanced?agent-rollup-id='
            + encodeURIComponent($scope.agentRollupId))
      ]).then(function (responses) {
        onLoaded(responses[0].data, responses[1].data, responses[2].data);
      }, function (response) {
        httpErrors.handle(response);
      });
    }

    reloadAll();
  }
]);
