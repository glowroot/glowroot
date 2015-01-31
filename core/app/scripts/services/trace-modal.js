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

/* global glowroot, Glowroot, HandlebarsRendering, $ */

glowroot.factory('traceModal', [
  '$rootScope',
  '$http',
  function ($rootScope, $http) {

    var $body = $('body');

    function displayModal(traceId) {

      var spinner;
      var $modalContent = $('#traceModal .modal-body');

      $('#traceModal').modal();
      $('.navbar-fixed-top').css('padding-right', $body.css('padding-right'));
      $('.navbar-fixed-bottom').css('padding-right', $body.css('padding-right'));
      $('#traceModal').on('hide.bs.modal', function () {
        $('.navbar-fixed-top').css('padding-right', '');
        $('.navbar-fixed-bottom').css('padding-right', '');
      });
      $http.get('backend/trace/header/' + traceId)
          .success(function (data) {
            spinner.stop();
            if (data.expired) {
              $modalContent.html('expired');
            } else {
              data.showExport = true;
              HandlebarsRendering.renderTrace(data, $modalContent);
              // need to focus on something inside the modal, otherwise keyboard events won't be captured,
              // in particular, page up / page down won't scroll the modal and escape won't close it
              $('#traceModal .modal-body > div:first-of-type').attr('tabIndex', -1);
              $('#traceModal .modal-body > div:first-of-type').css('outline', 'none');
              $('#traceModal .modal-body > div:first-of-type').focus();
              $('#traceModal .modal-body button.download-trace').click(function () {
                var $traceParent = $(this).parents('.gt-trace-parent');
                var traceId = $traceParent.data('gtTraceId');
                window.location = 'export/trace/' + traceId;
              });
            }
          })
          .error(function () {
            $modalContent.html(
                '<div class="gt-red" style="padding: 1em;">An error occurred retrieving the trace</div>');
          });

      // padding is same as for trace once it loads
      $modalContent.html('<div style="position: relative; display: inline-block;' +
      ' padding-left: 40px; padding-top: 60px;"></div>');
      spinner = Glowroot.showSpinner($modalContent.children().first());
      $body.append('<div class="modal-backdrop" id="modalBackdrop"></div>');
    }

    return {
      displayModal: displayModal
    };
  }
]);
