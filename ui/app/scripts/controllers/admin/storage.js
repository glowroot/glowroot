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

    // close modal backdrop if open, this is needed if click on "see Configuration > Storage > Trace detail data" inside
    // of trace modal
    $('.modal-backdrop').remove();

    $scope.hasChanges = function () {
      return $scope.originalConfig && !angular.equals($scope.config, $scope.originalConfig);
    };
    $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

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
            $scope.analyzedH2Tables = data.data.tables;
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
