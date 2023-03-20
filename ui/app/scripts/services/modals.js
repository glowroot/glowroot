/*
 * Copyright 2015-2023 the original author or authors.
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

glowroot.factory('modals', [
  '$timeout',
  '$location',
  '$window',
  function ($timeout, $location, $window) {
    function display(selector) {
      var $selector = $(selector);
      if (!$selector.parents('#modalContent').length) {
        $selector.scope().$on('$destroy', function () {
          $selector.remove();
          // close modal backdrop if open
          $('.modal-backdrop').remove();
        });
        $selector.detach().appendTo($('#modalContent'));
      }

      $selector.modal();
      $selector.off('hide.bs.modal');
      $selector.on('hide.bs.modal', function () {
        // using $timeout as this may be reached inside angular digest or not
        $timeout(function () {
          var query = $selector.data('location-query');
          var goBack = false;
          if (angular.isArray(query)) {
            angular.forEach(query, function (q) {
              if ($location.search()[q]) {
                goBack = true;
              }
            });
          } else if (query) {
            if ($location.search()[query]) {
              goBack = true;
            }
          }
          if (goBack) {
            $window.history.back();
          }
        });
        $('#chart canvas').show();
        $('body > header').removeAttr('aria-hidden');
        $('body > main > :not(#modalContent)').removeAttr('aria-hidden');
        $('body > footer').removeAttr('aria-hidden');
      });
      $('body > header').attr('aria-hidden', 'true');
      $('body > main > :not(#modalContent)').attr('aria-hidden', 'true');
      $('body > footer').attr('aria-hidden', 'true');
    }

    return {
      display: display
    };
  }
]);
