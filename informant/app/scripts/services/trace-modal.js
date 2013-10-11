/*
 * Copyright 2012-2013 the original author or authors.
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

/* global informant, Informant, TraceRenderer, $, alert */

informant.factory('traceModal', [
  '$rootScope',
  '$http',
  function ($rootScope, $http) {

    var $body = $('body');
    var $chart = $('#chart');

    function displayModal(summaryTrace, initialFixedOffset, initialWidth, initialHeight, modalVanishPoint) {

      var savedShowExport = summaryTrace.showExport;
      summaryTrace.showExport = true;
      var summaryHtml = TraceRenderer.renderSummary(summaryTrace) +
          '<br><div class="indent2"><span class="button-spinner inline-block hide"' +
          ' id="detailSpinner" style="margin-left: 0px; margin-top: 30px;"></span></div>';
      summaryTrace.showExport = savedShowExport;

      var $modalContent = $('#modalContent');
      $modalContent.data('vanishPoint', modalVanishPoint);
      var $modal = $('#modal');
      $modalContent.html(summaryHtml);
      // need to focus on something inside the modal, otherwise keyboard events won't be captured,
      // in particular, page up / page down won't scroll the modal
      $modalContent.focus();
      $modal.css('position', 'fixed');
      $modal.css('top', initialFixedOffset.top);
      $modal.css('left', initialFixedOffset.left);
      $modal.width(initialWidth);
      $modal.height(initialHeight);
      $modal.css('margin', 0);
      $modal.css('background-color', '#eee');
      $modal.css('font-size', '12px');
      $modal.css('line-height', '16px');
      // this is needed to prevent the background from scrolling
      $body.css('overflow', 'hidden');
      // .modal-open-full-screen compensates for the missing scrollbar
      // to prevent the background page from shifting underneath the modal
      $body.addClass('modal-open-full-screen');
      $modal.modal({ 'show': true, 'keyboard': false, 'backdrop': false });
      // +15 is to account for scrollbar
      var width = $(window).width() - 50;
      var height = $(window).height() - 50;
      var detailLoaded = false;
      var spinner;
      $modal.animate({
        left: '25px',
        top: '25px',
        width: width + 'px',
        height: height + 'px',
        backgroundColor: '#fff',
        fontSize: '14px',
        lineHeight: '20px'
      }, 400, function () {
        if (!detailLoaded) {
          spinner = Informant.showSpinner('#detailSpinner');
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
      }, 400);
      $rootScope.$apply(function () {
        $http.get('backend/trace/detail/' + summaryTrace.id)
            .success(function (data) {
              detailLoaded = true;
              if (spinner) {
                spinner.stop();
              }
              if (data.expired) {
                $('#modalContent').html('expired');
              } else {
                data.showExport = true;
                TraceRenderer.renderDetail(data, '#modalContent');
              }
            })
            .error(function () {
              // TODO handle this better
              alert('Error occurred');
            });
      });
    }

    function hideModal() {
      var $modalContent = $('#modalContent');
      var modalVanishPoint = $modalContent.data('vanishPoint');

      // reset overflow so the background can scroll again
      $body.css('overflow', '');
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
        width: 0,
        height: 0,
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
