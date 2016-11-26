/*
 * Copyright 2015-2016 the original author or authors.
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
  function ($timeout, $location) {
    function display(selector, centerVertically) {
      var $selector = $(selector);
      if (centerVertically) {
        // see http://stackoverflow.com/questions/18053408/vertically-centering-bootstrap-modal-window/20444744#20444744
        $selector.off('show.bs.modal');
        $selector.on('show.bs.modal', function () {
          $(this).css('display', 'block');
          var $dialog = $(this).find('.modal-dialog');
          var offset = Math.max(($(window).height() - $dialog.height()) / 2, 0);
          $dialog.css('margin-top', offset);
        });
      }
      $selector.modal();
      var $body = $('body');
      $('.navbar-fixed-top').css('padding-right', $body.css('padding-right'));
      $('.navbar-fixed-bottom').css('padding-right', $body.css('padding-right'));
      $selector.off('hide.bs.modal');
      $selector.on('hide.bs.modal', function () {
        // using $timeout as this may be reached inside angular digest or not
        $timeout(function () {
          var query = $selector.data('location-query');
          if (angular.isArray(query)) {
            angular.forEach(query, function (q) {
              $location.search(q, null);
            });
          } else if (query) {
            $location.search(query, null);
          }
        });
        $('.navbar-fixed-top').css('padding-right', '');
        $('.navbar-fixed-bottom').css('padding-right', '');
        $('#chart canvas').show();
      });
      $timeout(function () {
        // need to focus on something inside the modal, otherwise keyboard events won't be captured,
        // in particular, page up / page down won't scroll the modal and escape won't close it
        $selector.find('.modal-body').attr('tabIndex', -1);
        $selector.find('.modal-body').css('outline', 'none');
        $selector.find('.modal-body').focus();
      });
    }

    return {
      display: display
    };
  }
]);
