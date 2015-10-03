/*
 * Copyright 2012-2015 the original author or authors.
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

glowroot.controller('ConfigStorageCtrl', [
  '$scope',
  '$http',
  '$location',
  '$timeout',
  'confirmIfHasChanges',
  'httpErrors',
  function ($scope, $http, $location, $timeout, confirmIfHasChanges, httpErrors) {
    // initialize page binding object
    $scope.page = {};

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

    $scope.$watchCollection('page.traceExpirationDays', function (newValue) {
      if ($scope.config) {
        $scope.config.traceExpirationHours = newValue * 24;
      }
    });

    function onNewData(data) {
      $scope.loaded = true;
      $scope.config = data;
      $scope.originalConfig = angular.copy(data);

      $scope.page.rollupExpirationDays = [];
      angular.forEach(data.rollupExpirationHours, function (hours) {
        $scope.page.rollupExpirationDays.push(hours / 24);
      });
      $scope.page.traceExpirationDays = data.traceExpirationHours / 24;
    }

    $scope.save = function (deferred) {
      $http.post('backend/config/storage', $scope.config)
          .success(function (data) {
            onNewData(data);
            deferred.resolve('Saved');
          })
          .error(httpErrors.handler($scope, deferred));
    };

    $scope.defragData = function (deferred) {
      $http.post('backend/admin/defrag-data')
          .success(function () {
            deferred.resolve('Defragmented');
          })
          .error(httpErrors.handler($scope, deferred));
    };

    $scope.deleteAllData = function (deferred) {
      $http.post('backend/admin/delete-all-data', {serverGroup: $scope.serverGroup})
          .success(function () {
            deferred.resolve('Deleted');
          })
          .error(httpErrors.handler($scope, deferred));
    };

    $http.get('backend/config/storage')
        .success(onNewData)
        .error(httpErrors.handler($scope));

    // not using gt-form-autofocus-on-first-input in order to handle special case #rollup-capped-database-size and
    // #trace-capped-database-size urls
    var selector = 'input:not(.gt-autofocus-ignore)';
    if ($location.hash() === 'rollup-capped-database-size') {
      selector = '.gt-rollup-capped-database-size input';
    } else if ($location.hash() === 'trace-capped-database-size') {
      selector = '.gt-trace-capped-database-size input';
    }
    var $form = $('.form-horizontal');
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
