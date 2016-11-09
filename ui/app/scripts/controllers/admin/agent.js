/*
 * Copyright 2016 the original author or authors.
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

glowroot.controller('AdminAgentCtrl', [
  '$scope',
  '$location',
  '$http',
  '$timeout',
  'confirmIfHasChanges',
  'httpErrors',
  'modals',
  function ($scope, $location, $http, $timeout, confirmIfHasChanges, httpErrors, modals) {

    // initialize page binding object
    $scope.page = {};

    var agentRollupId = $location.search()['agent-rollup-id'];

    function onNewData(data) {
      $scope.config = data;
      $scope.originalConfig = angular.copy(data);
      $scope.heading = data.display || data.id;
    }

    $http.get('backend/admin/agent-rollups?agent-rollup-id=' + encodeURIComponent(agentRollupId))
        .success(function (data) {
          $scope.loaded = true;
          onNewData(data);
        })
        .error(httpErrors.handler($scope));

    $scope.hasChanges = function () {
      return !angular.equals($scope.config, $scope.originalConfig);
    };
    var removeConfirmIfHasChangesListener = $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    $scope.save = function (deferred) {
      var postData = angular.copy($scope.config);
      $http.post('backend/admin/agent-rollups/update', postData)
          .success(function (data) {
            onNewData(data);
            deferred.resolve('Saved');
          })
          .error(httpErrors.handler($scope, deferred));
    };

    $scope.displayDeleteConfirmationModal = function () {
      modals.display('#deleteConfirmationModal', true);
    };

    $scope.delete = function () {
      var postData = {
        agentRollupId: agentRollupId
      };
      $scope.deleting = true;
      $http.post('backend/admin/agent-rollups/remove', postData)
          .success(function () {
            $scope.deleting = false;
            $('#deleteConfirmationModal').modal('hide');
            removeConfirmIfHasChangesListener();
            $location.url('admin/agent-list').replace();
          })
          .error(function (data, status) {
            $scope.deleting = false;
            httpErrors.handler($scope)(data, status);
          });
    };
  }
]);
