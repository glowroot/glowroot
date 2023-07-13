/*
 * Copyright 2015-2018 the original author or authors.
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

/* global $, ClipboardJS */

var nextClipboardId = 1;

// this is not angular service because it is included in exported traces as well
window.gtClipboard = function (clipboardSelector, clipboardContainer, textFn) {
  setTimeout(function () {
    var $clipboardIcon = $(clipboardSelector);
    if ($clipboardIcon.attr('id')) {
      return;
    }
    var clipboardId = 'gtClipboard' + nextClipboardId++;
    $clipboardIcon.attr('id', clipboardId);
    var clipboardOptions = {
      text: function () {
        return textFn() + '\n';
      },
      stopPropagation: true
    };
    var tooltipOptions = {
      title: 'Copy to clipboard',
      placement: 'bottom'
    };
    var $clipboardContainer = $(clipboardContainer);
    clipboardOptions.container = $clipboardContainer[0];
    tooltipOptions.container = $clipboardContainer;
    var clipboard = new ClipboardJS('#' + clipboardId, clipboardOptions);
    $clipboardIcon.tooltip(tooltipOptions);

    clipboard.on('success', function () {
      $clipboardIcon.attr('data-original-title', 'Copied!')
          .tooltip('show');
      setTimeout(function () {
          $clipboardIcon.tooltip('hide');
          $clipboardIcon.attr('data-original-title', 'Copy to clipboard');
        }, 1000);
    });

    $clipboardIcon.data('gtClipboard', true);
  });
};
