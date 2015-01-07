/*
 * Copyright 2013-2015 the original author or authors.
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

/* global glowroot, swal */

glowroot.factory('confirmIfHasChanges', [
  '$location',
  function ($location) {
    return function ($scope) {
      var confirmed;
      return function (event, newUrl) {
        if (!$scope.httpError && !confirmed && $scope.hasChanges()) {
          event.preventDefault();
          swal({
            title: 'You have unsaved changes',
            text: 'Are you sure you want to navigate away from this page?',
            type: 'warning',
            showCancelButton: true,
            confirmButtonColor: '#DD6B55',
            confirmButtonText: 'Yes'
          }, function (isConfirm) {
            if (isConfirm) {
              $scope.$apply(function () {
                confirmed = true;
                $location.$$parse(newUrl);
              });
            }
          });
        }
      };
    };
  }
]);
