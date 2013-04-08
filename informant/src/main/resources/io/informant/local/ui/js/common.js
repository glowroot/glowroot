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
var Informant = (function () {
  'use strict';

  $(document).ready(function () {
    var onEnterClick = function (e) {
      if (e.keyCode === 13) {
        // enter key, for clicking when focus is on .clickable
        // pass extra arg to click handler in case click handler wants to treat keyboard clicks
        // differently from mouse clicks
        $(this).trigger('click', true);
      }
    };
    $(document).on('keydown', '.clickable, .lightbtn, .unexpanded-content, .expanded-content',
        onEnterClick);

    // bootstrap-dropdown already closes the menu on esc key, this is just adding handling for tab
    // key to close menu also, e.g. user is looking at menu and then tabs away
    var onTabCloseMenu = function (e) {
      if (e.keyCode === 9) { // tab key
        $('.header-see-also').removeClass('open');
      }
    };
    $('.header-see-also [data-toggle=dropdown], .header-see-also [role=menu]')
        .on('keydown touchstart', onTabCloseMenu);
  });

  var my = {};

  my.showAndFadeSuccessMessage = function (selector) {
    showAndFadeMessage(selector, 0);
  };

  // TODO unused
  my.showAndFadeErrorMessage = function (selector) {
    showAndFadeMessage(selector, 1000);
  };

  var showAndFadeMessage = function (selector, delay) {
    $(selector).each(function () {
      // handle crazy user clicking on the button
      var $this = $(this);
      if ($this.data('timeout')) {
        clearTimeout($this.data('timeout'));
      }
      $this.stop().animate({opacity: '100'});
      $this.removeClass('hide');
      var outerThis = this;
      $this.data('timeout', setTimeout(function () { fadeOut(outerThis, 1000); }, delay));
    });
  };

  var fadeOut = function (selector, duration) {
    // fade out and then override jquery behavior and use hide class instead of display: none
    var $selector = $(selector);
    $selector.fadeOut(duration, function () {
      $selector.addClass('hide');
      $selector.css('display', '');
    });
  };

  my.showSpinner = function (selector, opts) {
    opts = opts || { lines: 10, width: 4, radius: 8 };
    $(selector).each(function () {
      var data = $(this).data();
      data.spinner = new Spinner(opts);
      var outerThis = this;

      function displaySpinner() {
        // data.spinner may have been cleared already by hideSpinner() before setTimeout triggered
        if (data.spinner) {
          $(outerThis).removeClass('hide');
          data.spinner.spin(outerThis);
        }
      }

      // small delay so that if there is an immediate response the spinner doesn't blink
      setTimeout(displaySpinner, 100);
    });
  };

  my.hideSpinner = function (selector) {
    $(selector).each(function () {
      var $this = $(this);
      var data = $this.data();
      if (data.spinner) {
        data.spinner.stop();
        delete data.spinner;
      }
      $this.addClass('hide');
    });
  };

  my.configureAjaxError = function () {
    var modalDiv =
        '<div class="modal hide fade" id="ajaxErrorModal" tabindex="-1"'
            + '    style="width: 800px; margin: -300px 0 0 -400px; max-height: 600px;">'
            + '  <div class="modal-header">'
            + '    <button class="close" data-dismiss="modal">&times;</button>'
            + '    <h3>Ajax Error</h3>'
            + '  </div>'
            + '  <div class="modal-body" id="ajaxError"></div>'
            + '  <div class="modal-footer">'
            + '    <button class="btn" data-dismiss="modal">Close</button>'
            + '  </div>'
            + '</div>';
    $(document.body).append(modalDiv);
    $(document).ajaxError(function (e, jqxhr, settings, exception) {
      if (jqxhr.abort) {
        // intentional abort (currently happens in firefox if open trace detail modal, detail takes
        // long to load, hit escape key to close trace detail modal before detail request completes)
        return;
      }
      var $ajaxError = $('#ajaxError');
      if (jqxhr.status === 0) {
        $ajaxError.html('Can\'t connect to server');
      } else if (jqxhr.status === 200) {
        $ajaxError.html('Error parsing json: ' + exception);
        $ajaxError.append('<br><br>');
        $ajaxError.append(jqxhr.responseText);
      } else {
        $ajaxError.html('Error from server: ' + jqxhr.statusText);
      }
      $('#ajaxErrorModal').modal('show');
    });
  };

  my.addIntegerValidator = function (selector) {
    var $selector = $(selector);
    $selector.keyup(function () {
      if (my.isInteger($(this).val())) {
        $selector.closest('.control-group').removeClass('error');
      } else {
        $selector.closest('.control-group').addClass('error');
      }
    });
  };

  my.addIntegerOrEmptyValidator = function (selector) {
    var $selector = $(selector);
    $selector.keyup(function () {
      var $this = $(this);
      if ($this.val() === '' || my.isInteger($this.val())) {
        $selector.closest('.control-group').removeClass('error');
      } else {
        $selector.closest('.control-group').addClass('error');
      }
    });
  };

  my.isInteger = function (str) {
    // tolerant of commas and leading/trailing spaces
    return (/^(0|[1-9][0-9,]*)$/).test($.trim(str));
  };

  my.parseInteger = function (str) {
    // tolerant of commas and leading/trailing spaces
    return parseInt($.trim(str).replace(/,/g, ''), 10);
  };

  my.addDoubleValidator = function (selector) {
    var $selector = $(selector);
    $selector.keyup(function () {
      if (my.isDouble($(this).val())) {
        $selector.closest('.control-group').removeClass('error');
      } else {
        $selector.closest('.control-group').addClass('error');
      }
    });
  };

  my.isDouble = function (str) {
    // tolerant of leading/trailing spaces
    // tolerant of missing whole (.2) and missing decimal (2.)
    return (/^-?(0|[1-9][0-9,]*)?\.?[0-9]*$/).test($.trim(str));
  };

  my.parseDouble = function (str) {
    // tolerant of commas and leading/trailing spaces
    return parseFloat($.trim(str).replace(/,/g, ''));
  };

  my.addPercentageValidator = function (selector) {
    var $selector = $(selector);
    $selector.keyup(function () {
      if (my.isPercentage($(this).val())) {
        $selector.closest('.control-group').removeClass('error');
      } else {
        $selector.closest('.control-group').addClass('error');
      }
    });
  };

  my.isPercentage = function (str) {
    // tolerant of leading/trailing spaces
    // tolerant of missing whole (.2) and missing decimal (2.)
    if (/^(0|[1-9][0-9]*)?\.?[0-9]*$/.test($.trim(str))) {
      return parseFloat($.trim(str)) <= 100;
    }
    return false;
  };

  my.parsePercentage = function (str) {
    // tolerant of leading/trailing spaces
    return $.trim(str);
  };

  return my;
}());
