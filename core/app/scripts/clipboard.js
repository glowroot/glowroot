/*
 * Copyright 2015 the original author or authors.
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

/* global ZeroClipboard, $ */

// this is not angular service because it is included in exported traces as well
window.gtClipboard = function (clipboardSelector, textNodeFn, textFn) {
  var $clipboardIcon = $(clipboardSelector);
  if (!$clipboardIcon.data('gtZeroClip')) {
    if (typeof ZeroClipboard === 'undefined' || ZeroClipboard.isFlashUnusable()) {
      // ZeroClipboard does not work with local file urls
      // see https://github.com/JamesMGreene/zeroclipboard/blob/master/docs/instructions.md#file-protocol-limitations
      $clipboardIcon.tooltip({
        title: 'Select for clipboard',
        placement: 'bottom',
        container: 'body'
      });
      $clipboardIcon.click(function (event) {
        // need setTimeout(), otherwise in Chrome when click clipboard icon 2x, it will de-select the selected text
        setTimeout(function () {
          // this hack is to work around https://github.com/twbs/bootstrap/issues/10610
          $(document.body).on('focusin.glowroot.hack', false);
          var selection = window.getSelection();
          var range = document.createRange();
          range.selectNodeContents(textNodeFn());
          // removeAllRanges is needed (at least on instrumentation export) to avoid
          // 'Discontiguous selection is not supported' error
          selection.removeAllRanges();
          selection.addRange(range);
          // return focus back to nearest parent, otherwise esc key won't close modal in IE (tested 9-11)
          var $target = $(event.target);
          $target.closest('[tabindex="-1"]').focus();
          $clipboardIcon.attr('title', 'Now press Ctrl-C!')
              .tooltip('fixTitle')
              .tooltip('show');
          $clipboardIcon.attr('title', 'Select for clipboard')
              .tooltip('fixTitle');
          setTimeout(function () {
            $(document.body).off('focusin.glowroot.hack');
          });
        });
        // return false to prevent click from going through to expanded sections and toggling them closed
        return false;
      });
    } else {
      var client = new ZeroClipboard($clipboardIcon);
      $('#global-zeroclipboard-html-bridge').tooltip({
        title: 'Copy to clipboard',
        placement: 'bottom'
      });
      client.on('ready', function (readyEvent) {
        client.on('copy', function (event) {
          var text = textFn().trim();
          event.clipboardData.setData('text/plain', text + '\n');
          // return focus back to nearest parent, otherwise esc key won't close modal
          var $target = $(event.target);
          $target.closest('[tabindex="-1"]').focus();
          $('#global-zeroclipboard-html-bridge').attr('title', 'Copied!')
              .tooltip('fixTitle')
              .tooltip('show');
          $('#global-zeroclipboard-html-bridge').attr('title', 'Copy to clipboard')
              .tooltip('fixTitle');
        });
      });
    }
    $clipboardIcon.data('gtZeroClip', true);
  }
};
