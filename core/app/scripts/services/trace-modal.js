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
  '$http',
  'modals',
  function ($http, modals) {

    function displayModal(serverId, traceId) {

      var spinner;
      var $modalContent = $('#traceModal .modal-body');

      modals.display('#traceModal');
      $http.get('backend/trace/header?server-id=' + serverId + '&trace-id=' + traceId)
          .success(function (data) {
            spinner.stop();
            if (data.expired) {
              $modalContent.html('expired');
            } else {
              data.showExport = true;
              HandlebarsRendering.renderTrace(data, serverId, $modalContent);
              $('#traceModal .modal-body button.download-trace').click(function () {
                var $traceParent = $(this).parents('.gt-trace-parent');
                var traceId = $traceParent.data('gtTraceId');
                window.location = document.getElementsByTagName('base')[0].href + 'export/trace?server-id=' + serverId
                    + '&trace-id=' + traceId;
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
    }

    return {
      displayModal: displayModal
    };
  }
]);
