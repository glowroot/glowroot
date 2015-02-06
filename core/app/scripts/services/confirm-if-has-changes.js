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

/* global glowroot, $ */

glowroot.factory('confirmIfHasChanges', [
  '$rootScope',
  '$location',
  'modals',
  function ($rootScope, $location, modals) {
    return function ($scope) {
      var confirmed;
      return function (event, newUrl) {
        if ($rootScope.layout.adminPasswordEnabled && $rootScope.authenticatedUser !== 'admin') {
          // no point in confirming when user doesn't have permission to make the changes anyways
          return;
        }
        if (!$scope.httpError && !confirmed && $scope.hasChanges()) {
          event.preventDefault();

          modals.display('#unsavedChangesModal', true);

          $('#unsavedChangesConfirm').off('click');
          $('#unsavedChangesConfirm').on('click', function () {
            $scope.$apply(function () {
              confirmed = true;
              $('#unsavedChangesModal').modal('hide');
              $location.$$parse(newUrl);
            });
          });
        }
      };
    };
  }
]);
