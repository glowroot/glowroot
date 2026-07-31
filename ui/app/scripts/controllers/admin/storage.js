/*
 * Copyright 2012-2023 the original author or authors.
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

glowroot.controller('AdminStorageCtrl', [
  '$scope',
  '$http',
  '$location',
  'confirmIfHasChanges',
  'httpErrors',
  function ($scope, $http, $location, confirmIfHasChanges, httpErrors) {

    // initialize page binding object
    $scope.page = {};

    // Collapsible Storage sections — keep the page short; deep links open capped.db
    var hash = $location.hash();
    var openCapped = hash === 'rollup-capped-database-size'
        || hash === 'trace-capped-database-size';
    $scope.sectionOpen = {
      h2Data: true,
      capped: openCapped,
      h2Cache: true,
      maintenance: false,
      centralRollup: true,
      centralQuery: false,
      centralProfile: false,
      centralTrace: false
    };
    $scope.toggleSection = function (name) {
      $scope.sectionOpen[name] = !$scope.sectionOpen[name];
    };

    $scope.h2CacheModes = [
      { value: 'auto', label: 'Auto (128 MB target)' },
      { value: 'fixed', label: 'Fixed MB' },
      { value: 'percent', label: '% of -Xmx' }
    ];

    // close modal backdrop if open, this is needed if click on "see Configuration > Storage > Trace detail data" inside
    // of trace modal
    $('.modal-backdrop').remove();

    $scope.hasChanges = function () {
      return $scope.originalConfig && !angular.equals($scope.config, $scope.originalConfig);
    };
    $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    function refreshH2CacheWarning() {
      if (!$scope.config || $scope.layout.central) {
        $scope.h2CacheWarning = '';
        return;
      }
      if ($scope.config.h2CacheSystemPropertyOverride) {
        $scope.h2CacheWarning = '';
        return;
      }
      var mode = $scope.config.h2CacheMode;
      var value = $scope.config.h2CacheValue;
      var maxHeapMb = $scope.config.maxHeapMb || 0;
      var targetMb;
      if (mode === 'percent') {
        targetMb = Math.floor(maxHeapMb * value / 100);
      } else if (mode === 'fixed') {
        targetMb = value;
      } else {
        $scope.h2CacheWarning = '';
        return;
      }
      if (targetMb >= 256 || targetMb > ($scope.config.effectiveH2CacheMb || 0)) {
        $scope.h2CacheWarning = 'Requested size will be clamped for shared-JVM safety'
            + ' (effective ' + $scope.config.effectiveH2CacheMb + ' MB).'
            + ' On very large H2 files, prefer shorter retention and Compact over a huge cache.';
      } else {
        $scope.h2CacheWarning = '';
      }
    }

    $scope.$watchGroup(['config.h2CacheMode', 'config.h2CacheValue', 'config.effectiveH2CacheMb'],
        refreshH2CacheWarning);

    $scope.$watchCollection('page.rollupExpirationDays', function (newValue) {
      if ($scope.config) {
        $scope.config.rollupExpirationHours = [];
        angular.forEach(newValue, function (days) {
          $scope.config.rollupExpirationHours.push(days * 24);
        });
      }
    });

    if ($scope.layout.central) {
      $scope.$watchCollection('page.queryAndServiceCallRollupExpirationDays', function (newValue) {
        if ($scope.config) {
          $scope.config.queryAndServiceCallRollupExpirationHours = [];
          angular.forEach(newValue, function (days) {
            $scope.config.queryAndServiceCallRollupExpirationHours.push(days * 24);
          });
        }
      });

      $scope.$watchCollection('page.profileRollupExpirationDays', function (newValue) {
        if ($scope.config) {
          $scope.config.profileRollupExpirationHours = [];
          angular.forEach(newValue, function (days) {
            $scope.config.profileRollupExpirationHours.push(days * 24);
          });
        }
      });
    }

    $scope.$watchCollection('page.traceExpirationDays', function (newValue) {
      if ($scope.config) {
        $scope.config.traceExpirationHours = newValue * 24;
      }
    });

    if (!$scope.layout.central) {
      $scope.$watchCollection('page.fullQueryTextExpirationDays', function (newValue) {
        if ($scope.config) {
          $scope.config.fullQueryTextExpirationHours = newValue * 24;
        }
      });
    }

    function onNewData(data) {
      $scope.loaded = true;
      $scope.config = data;
      $scope.originalConfig = angular.copy(data);

      $scope.page.rollupExpirationDays = [];
      angular.forEach(data.rollupExpirationHours, function (hours) {
        $scope.page.rollupExpirationDays.push(hours / 24);
      });
      if ($scope.layout.central) {
        $scope.page.queryAndServiceCallRollupExpirationDays = [];
        angular.forEach(data.queryAndServiceCallRollupExpirationHours, function (hours) {
          $scope.page.queryAndServiceCallRollupExpirationDays.push(hours / 24);
        });
        $scope.page.profileRollupExpirationDays = [];
        angular.forEach(data.profileRollupExpirationHours, function (hours) {
          $scope.page.profileRollupExpirationDays.push(hours / 24);
        });
      }
      $scope.page.traceExpirationDays = data.traceExpirationHours / 24;
      if (!$scope.layout.central) {
        $scope.page.fullQueryTextExpirationDays = data.fullQueryTextExpirationHours / 24;
        if (!data.h2CacheMode) {
          data.h2CacheMode = 'auto';
        }
        if (data.h2CacheValue === undefined || data.h2CacheValue === null) {
          data.h2CacheValue = 128;
        }
        refreshH2CacheWarning();
      }
    }

    $scope.save = function (deferred) {
      $scope.showH2DiskSpaceAnalysis = false;
      $scope.showTraceCountAnalysis = false;
      $http.post('backend/admin/storage', $scope.config)
          .then(function (response) {
            onNewData(response.data);
            deferred.resolve('Saved');
          }, function (response) {
            httpErrors.handle(response, deferred);
          });
    };

    $scope.updateCassandraTwcsWindowSizes = function (deferred) {
      $http.post('backend/admin/update-cassandra-twcs-window-sizes')
          .then(function (response) {
            var updatedTableCount = response.data;
            deferred.resolve('Updated ' + updatedTableCount + ' table' + (updatedTableCount === 1 ? '' : 's'));
          }, function (response) {
            httpErrors.handle(response, deferred);
          });
    };

    $scope.deleteAllStoredData = function (deferred) {
      $scope.showH2DiskSpaceAnalysis = false;
      $scope.showTraceCountAnalysis = false;
      $http.post('backend/admin/delete-all-stored-data', {})
          .then(function () {
            deferred.resolve('Deleted');
          }, function (response) {
            httpErrors.handle(response, deferred);
          });
    };

    $scope.defragH2Data = function (deferred) {
      $scope.showH2DiskSpaceAnalysis = false;
      $scope.showTraceCountAnalysis = false;
      $http.post('backend/admin/defrag-h2-data', {})
          .then(function () {
            deferred.resolve('Defragmented');
          }, function (response) {
            httpErrors.handle(response, deferred);
          });
    };

    $scope.compactH2Data = function (deferred) {
      $scope.showH2DiskSpaceAnalysis = false;
      $scope.showTraceCountAnalysis = false;
      $http.post('backend/admin/compact-h2-data', {})
          .then(function () {
            deferred.resolve('Compacted');
          }, function (response) {
            httpErrors.handle(response, deferred);
          });
    };

    $scope.analyzeH2DiskSpace = function (deferred) {
      $scope.showH2DiskSpaceAnalysis = false;
      $scope.showTraceCountAnalysis = false;
      $http.post('backend/admin/analyze-h2-disk-space', {})
          .then(function (data) {
            $scope.h2DataFileSize = data.data.h2DataFileSize;
            $scope.h2LiveBytes = data.data.liveBytes || 0;
            $scope.h2ReclaimableBytes = data.data.reclaimableBytes || 0;
            $scope.analyzedH2Tables = data.data.tables;
            var file = $scope.h2DataFileSize || 0;
            if (file > 0) {
              $scope.h2LivePct = Math.min(100, 100 * $scope.h2LiveBytes / file);
              $scope.h2ReclaimablePct = Math.min(100 - $scope.h2LivePct,
                  100 * $scope.h2ReclaimableBytes / file);
            } else {
              $scope.h2LivePct = 0;
              $scope.h2ReclaimablePct = 0;
            }
            $scope.showCompactCta = $scope.h2ReclaimableBytes >= 64 * 1024 * 1024
                || (file > 0 && $scope.h2ReclaimableBytes / file >= 0.1);
            $scope.showH2DiskSpaceAnalysis = true;
            deferred.resolve('Analyzed');
          }, function (response) {
            httpErrors.handle(response, deferred);
          });
    };

    $scope.analyzeTraceCounts = function (deferred) {
      $scope.showH2DiskSpaceAnalysis = false;
      $scope.showTraceCountAnalysis = false;
      $http.post('backend/admin/analyze-trace-counts', {})
          .then(function (data) {
            $scope.analyzedTraceOverallCounts = data.data.overallCounts;
            $scope.analyzedTraceCounts = data.data.counts;
            $scope.showTraceCountAnalysis = true;
            deferred.resolve('Analyzed');
          }, function (response) {
            httpErrors.handle(response, deferred);
          });
    };

    $http.get('backend/admin/storage')
        .then(function (response) {
          onNewData(response.data);
        }, function (response) {
          httpErrors.handle(response);
        });

    // not using gt-form-autofocus-on-first-input in order to handle special case #rollup-capped-database-size and
    // #trace-capped-database-size urls
    var selector = 'input';
    if ($location.hash() === 'rollup-capped-database-size') {
      selector = '.gt-rollup-capped-database-size input';
    } else if ($location.hash() === 'trace-capped-database-size') {
      selector = '.gt-trace-capped-database-size input';
    }
    var $form = $('#storageConfigCard');
    var unregisterWatch = $scope.$watch(function () {
      return $form.find(selector).length && $form.find('input').first().is(':visible');
    }, function (newValue) {
      if (newValue) {
        $form.find(selector).first().focus();
        unregisterWatch();
      }
    });
  }
]);
