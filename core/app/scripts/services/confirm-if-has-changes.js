/*
 * Copyright 2013 the original author or authors.
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

/* global glowroot */

glowroot.factory('confirmIfHasChanges', [
  '$modal',
  '$location',
  function ($modal, $location) {
    return function ($scope) {
      var confirmed;
      return function (event, newUrl) {
        if (!$scope.httpError && !confirmed && $scope.hasChanges()) {
          event.preventDefault();
          var modal = $modal.open({
            templateUrl: 'template/gt-modal-has-changes.html',
            controller: ['$scope', '$modalInstance', function ($scope, $modalInstance) {
              $scope.close = $modalInstance.close;
            }]
          });
          modal.result.then(function (result) {
            if (result) {
              confirmed = true;
              $location.$$parse(newUrl);
            }
          });
        }
      };
    };
  }
]);
