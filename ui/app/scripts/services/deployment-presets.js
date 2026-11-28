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

// Lean Prod Storage values. Populate-only — Save still uses the normal endpoint.
glowroot.factory('leanProdPresets', [
  function () {

    var EMBEDDED_STORAGE = {
      rollupExpirationDays: [1, 7, 30, 30],
      traceExpirationDays: 7,
      fullQueryTextExpirationDays: 7,
      rollupCappedDatabaseSizeMb: 100,
      traceCappedDatabaseSizeMb: 100
    };

    var CENTRAL_STORAGE = {
      rollupExpirationDays: [1, 7, 30, 90],
      queryAndServiceCallRollupExpirationDays: [1, 7, 14, 14],
      profileRollupExpirationDays: [1, 7, 14, 14],
      traceExpirationDays: 7
    };

    function applyEmbeddedStorage(page, config) {
      page.rollupExpirationDays = angular.copy(EMBEDDED_STORAGE.rollupExpirationDays);
      page.traceExpirationDays = EMBEDDED_STORAGE.traceExpirationDays;
      page.fullQueryTextExpirationDays = EMBEDDED_STORAGE.fullQueryTextExpirationDays;
      var capped = EMBEDDED_STORAGE.rollupCappedDatabaseSizeMb;
      config.rollupCappedDatabaseSizesMb = [capped, capped, capped, capped];
      config.traceCappedDatabaseSizeMb = EMBEDDED_STORAGE.traceCappedDatabaseSizeMb;
    }

    function applyCentralStorage(page) {
      page.rollupExpirationDays = angular.copy(CENTRAL_STORAGE.rollupExpirationDays);
      page.queryAndServiceCallRollupExpirationDays =
          angular.copy(CENTRAL_STORAGE.queryAndServiceCallRollupExpirationDays);
      page.profileRollupExpirationDays =
          angular.copy(CENTRAL_STORAGE.profileRollupExpirationDays);
      page.traceExpirationDays = CENTRAL_STORAGE.traceExpirationDays;
    }

    return {
      applyEmbeddedStorage: applyEmbeddedStorage,
      applyCentralStorage: applyCentralStorage
    };
  }
]);
