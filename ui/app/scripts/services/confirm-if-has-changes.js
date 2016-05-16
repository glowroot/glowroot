/*
 * Copyright 2013-2016 the original author or authors.
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
        if (!$scope.httpError && !confirmed && $scope.hasChanges()) {
          event.preventDefault();

          var $modal = $('#confirmationModal');
          $modal.find('.modal-header h3').text('You have unsaved changes');
          $modal.find('.modal-body p').text('Are you sure you want to navigate away from this page?');
          modals.display('#confirmationModal', true);

          $('#confirmationModalButton').off('click');
          $('#confirmationModalButton').on('click', function () {
            $rootScope.$apply(function () {
              confirmed = true;
              $('#confirmationModal').modal('hide');
              $location.$$parse(newUrl);
            });
          });
        }
      };
    };
  }
]);
