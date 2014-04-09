/*
 * Copyright 2012-2014 the original author or authors.
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

/* global glowroot, Glowroot, TraceRenderer, $, alert */

glowroot.factory('traceModal', [
  '$rootScope',
  '$http',
  function ($rootScope, $http) {

    var $body = $('body');
    var $chart = $('#chart');

    function displayModal(traceId, modalVanishPoint) {

      var loaded;
      var spinner;
      var $modalContent = $('#modalContent');
      $modalContent.data('vanishPoint', modalVanishPoint);

      var tracePromise = $http.get('backend/trace/summary/' + traceId);

      var $modal = $('#modal');
      // need to focus on something inside the modal, otherwise keyboard events won't be captured,
      // in particular, page up / page down won't scroll the modal
      $modalContent.focus();
      $modal.css('position', 'fixed');
      $modal.css('left', modalVanishPoint[0]);
      $modal.css('top', modalVanishPoint[1]);
      $modal.css('right', $(window).width() - modalVanishPoint[0]);
      $modal.css('bottom', $(window).height() - modalVanishPoint[1]);
      $modal.css('margin', 0);
      $modal.css('background-color', '#fff');
      $modal.css('font-size', '14px');
      $modal.css('line-height', '20px');
      // .modal-open-full-screen compensates for the missing scrollbar
      // to prevent the background page from shifting underneath the modal
      $body.addClass('modal-open-full-screen');
      $modal.modal({ 'show': true, 'keyboard': false, 'backdrop': false });
      $modal.animate({
        left: '25px',
        top: '25px',
        right: '25px',
        bottom: '25px'
      }, 200, function () {
        tracePromise.then(function (result) {
          if (spinner) {
            spinner.stop();
          }
          loaded = true;
          if (result.data.expired) {
            $modalContent.html('expired');
          } else {
            result.data.showExport = true;
            TraceRenderer.render(result.data, $modalContent);
          }
        }, function () {
          // TODO handle this better
          alert('Error occurred');
        });
        if (!loaded) {
          // padding is same as for trace once it loads
          $modalContent.html('<div style="position: relative; display: inline-block;' +
              ' padding-left: 40px; padding-top: 60px;"></div>');
          spinner = Glowroot.showSpinner($modalContent.children().first());
        }

        // hiding the flot chart is needed to prevent a strange issue in chrome that occurs when
        // expanding a section of the details to trigger vertical scrollbar to be active, then
        // scroll a little bit down, leaving the section header visible, then click the section
        // header to collapse the section (while still scrolled down a bit from the top) and the
        // whole modal will shift down and to the right 25px in each direction (only in chrome)
        //
        // and without hiding flot chart there is another problem in chrome, in smaller browser
        // windows it causes the vertical scrollbar to get offset a bit left and upwards
        $chart.hide();
      });
      $body.append('<div class="modal-backdrop" id="modalBackdrop"></div>');
      var $modalBackdrop = $('#modalBackdrop');
      $modalBackdrop.css('background-color', '#ddd');
      $modalBackdrop.css('opacity', 0);
      $modalBackdrop.animate({
        'opacity': 0.8
      }, 200);
    }

    function hideModal() {
      var $modalContent = $('#modalContent');
      var modalVanishPoint = $modalContent.data('vanishPoint');

      $body.removeClass('modal-open-full-screen');
      // .modal-open will be removed by modal('hide'), but it needs to be removed at the same time
      // as the scrollbar is put back in order to prevent page from shifting
      $body.removeClass('modal-open');
      // re-display flot chart
      $chart.show();
      // remove large dom content first since it makes animation jerky at best
      // (and need to remove it afterwards anyways to clean up the dom)
      $modalContent.empty();
      var $modal = $('#modal');
      $modal.animate({
        left: (modalVanishPoint[0] - $(window).scrollLeft()) + 'px',
        top: (modalVanishPoint[1] - $(window).scrollTop()) + 'px',
        right: $(window).width() - modalVanishPoint[0],
        bottom: $(window).height() - modalVanishPoint[1],
        backgroundColor: '#eee'
      }, 200, function () {
        $modal.modal('hide');
      });
      var $modalBackdrop = $('#modalBackdrop');
      $modalBackdrop.animate({
        'opacity': 0
      }, 200, function () {
        $modalBackdrop.remove();
      });
    }

    $(document).keyup(function (e) {
      // esc key
      if (e.keyCode === 27 && $('#modal').is(':visible')) {
        hideModal();
      }
    });

    return {
      displayModal: displayModal,
      hideModal: hideModal
    };
  }
]);
