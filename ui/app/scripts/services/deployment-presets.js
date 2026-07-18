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

/* global glowroot, angular, console */

// Dev = Glowroot defaults. Prod = leaner retention/capped sizes and lighter profiling for
// embedded-on-prod (same JVM as the app). Presets only populate the form; Save persists.
glowroot.factory('deploymentPresets', [
  function () {

    // Matches EmbeddedStorageConfig / TransactionConfig / AdvancedConfig defaults
    var DEV = {
      rollupExpirationDays: [3, 14, 90, 90],
      traceExpirationDays: 14,
      fullQueryTextExpirationDays: 14,
      // Applied to all four rollup capped-database levels
      rollupCappedDatabaseSizeMb: 500,
      traceCappedDatabaseSizeMb: 500,
      slowThresholdMillis: 2000,
      profilingIntervalMillis: 1000,
      captureThreadStats: true,
      maxTransactionAggregates: 500,
      maxQueryAggregates: 500,
      maxServiceCallAggregates: 500,
      maxTraceEntriesPerTransaction: 2000,
      maxProfileSamplesPerTransaction: 50000
    };

    // Tighter disk/memory and less frequent stack sampling for shared-JVM embedded deploys
    var PROD = {
      rollupExpirationDays: [1, 7, 30, 30],
      traceExpirationDays: 7,
      fullQueryTextExpirationDays: 7,
      rollupCappedDatabaseSizeMb: 100,
      traceCappedDatabaseSizeMb: 100,
      slowThresholdMillis: 2000,
      profilingIntervalMillis: 5000,
      captureThreadStats: false,
      maxTransactionAggregates: 200,
      maxQueryAggregates: 200,
      maxServiceCallAggregates: 200,
      maxTraceEntriesPerTransaction: 500,
      maxProfileSamplesPerTransaction: 10000
    };

    function copyPreset(preset) {
      return angular.copy(preset);
    }

    function matches(page, preset) {
      if (!page || !preset) {
        return false;
      }
      return angular.equals(page.rollupExpirationDays, preset.rollupExpirationDays)
          && page.traceExpirationDays === preset.traceExpirationDays
          && page.fullQueryTextExpirationDays === preset.fullQueryTextExpirationDays
          && page.rollupCappedDatabaseSizeMb === preset.rollupCappedDatabaseSizeMb
          && page.traceCappedDatabaseSizeMb === preset.traceCappedDatabaseSizeMb
          && page.slowThresholdMillis === preset.slowThresholdMillis
          && page.profilingIntervalMillis === preset.profilingIntervalMillis
          && page.captureThreadStats === preset.captureThreadStats
          && page.maxTransactionAggregates === preset.maxTransactionAggregates
          && page.maxQueryAggregates === preset.maxQueryAggregates
          && page.maxServiceCallAggregates === preset.maxServiceCallAggregates
          && page.maxTraceEntriesPerTransaction === preset.maxTraceEntriesPerTransaction
          && page.maxProfileSamplesPerTransaction === preset.maxProfileSamplesPerTransaction;
    }

    function matchName(page) {
      if (matches(page, DEV)) {
        return 'dev';
      }
      if (matches(page, PROD)) {
        return 'prod';
      }
      return 'custom';
    }

    function apply(name, page) {
      var preset = name === 'prod' ? PROD : DEV;
      console.info('[Glowroot deployment profile] applying preset', name, angular.copy(preset));
      angular.extend(page, copyPreset(preset));
      return page;
    }

    return {
      DEV: DEV,
      PROD: PROD,
      copyPreset: copyPreset,
      matches: matches,
      matchName: matchName,
      apply: apply
    };
  }
]);
