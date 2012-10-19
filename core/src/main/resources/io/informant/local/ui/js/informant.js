/*
 * Copyright 2012 the original author or authors.
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
function showAndFadeSuccessMessage(selector) {
  showAndFadeMessage(selector, 0)
}
function showAndFadeErrorMessage(selector) {
  showAndFadeMessage(selector, 1000)
}
function showAndFadeMessage(selector, delay) {
  $(selector).each(function() {
    // handle crazy user clicking on the button
    if ($(this).data('timeout')) {
      clearTimeout($(this).data('timeout'))
    }
    $(this).stop().animate({opacity:'100'})
    $(this).removeClass('hide')
    var outerThis = this
    $(this).data('timeout', setTimeout(function() { fadeOut(outerThis, 1000) }, delay))
  })
}
function fadeOut(selector, duration) {
  // fade out and then override jquery behavior and use hide class instead of display: none
  $(selector).fadeOut(duration, function() {
    $(selector).addClass('hide')
    $(selector).css('display', '')
  })
}
function showSpinner(selector, opts) {
  if (arguments.length == 1) {
    opts = { lines: 10, width: 4, radius: 8 }
  }
  $(selector).each(function() {
    var data = $(this).data()
    data.spinner = new Spinner(opts)
    var outerThis = this
    function displaySpinner() {
      // data.spinner may have been cleared already by hideSpinner() before setTimeout triggered
      if (data.spinner) {
        $(outerThis).removeClass('hide')
        data.spinner.spin(outerThis)
      }
    }
    // small delay so that if there is an immediate response the spinner doesn't blink
    setTimeout(displaySpinner, 100)
  })
}
function hideSpinner(selector) {
  $(selector).each(function() {
    var data = $(this).data()
    if (data.spinner) {
      data.spinner.stop()
      delete data.spinner
    }
    $(this).addClass('hide')
  })
}
function configureAjaxError() {
  var modalDiv = ''
  + '<div class="modal hide fade" id="ajaxErrorModal" tabindex="-1"'
  + '    style="width: 800px; margin: -300px 0 0 -400px; max-height: 600px">'
  + '  <div class="modal-header">'
  + '    <button class="close" data-dismiss="modal">&times;</button>'
  + '    <h3>Ajax Error</h3>'
  + '  </div>'
  + '  <div class="modal-body" id="ajaxError"></div>'
  + '  <div class="modal-footer">'
  + '    <button class="btn" data-dismiss="modal">Close</button>'
  + '  </div>'
  + '</div>'
  $(document.body).append(modalDiv)
  $(document).ajaxError(function(e, jqxhr, settings, exception) {
    if (jqxhr.status == 0) {
      $('#ajaxError').html('Can\'t connect to server')
      $('#ajaxErrorModal').modal('show')
    } else if (jqxhr.status == 200) {
      $('#ajaxError').html('Error parsing json: ' + exception)
      $('#ajaxError').append('<br><br>')
      $('#ajaxError').append(jqxhr.responseText)
      $('#ajaxErrorModal').modal('show')
    } else {
      $('#ajaxError').html('Error from server: ' + jqxhr.statusText)
      $('#ajaxErrorModal').modal('show')
    }
  })
}
function addIntegerValidator(selector) {
  $(selector).keyup(function() {
    if (isInteger($(this).val())) {
      $(selector).closest('.control-group').removeClass('error')
    } else {
      $(selector).closest('.control-group').addClass('error')
    }
  })
}
function addIntegerOrEmptyValidator(selector) {
  $(selector).keyup(function() {
    if ($(this).val() == '' || isInteger($(this).val())) {
      $(selector).closest('.control-group').removeClass('error')
    } else {
      $(selector).closest('.control-group').addClass('error')
    }
  })
}
function isInteger(str) {
  // tolerant of commas and leading/trailing spaces
  return /^(0|[1-9][0-9,]*)$/.test($.trim(str))
}
function parseInteger(str) {
  // tolerant of commas and leading/trailing spaces
  return parseInt($.trim(str).replace(/,/g, ''))
}
function addDoubleValidator(selector) {
  $(selector).keyup(function() {
    if (isDouble($(this).val())) {
      $(selector).closest('.control-group').removeClass('error')
    } else {
      $(selector).closest('.control-group').addClass('error')
    }
  })
}
function isDouble(str) {
  // tolerant of leading/trailing spaces
  // tolerant of missing whole (.2) and missing decimal (2.)
  return /^-?(0|[1-9][0-9,]*)?\.?[0-9]*$/.test($.trim(str))
}
function parseDouble(str) {
  // tolerant of commas and leading/trailing spaces
  return parseFloat($.trim(str).replace(/,/g, ''))
}
function addPercentageValidator(selector) {
  $(selector).keyup(function() {
    if (isPercentage($(this).val())) {
      $(selector).closest('.control-group').removeClass('error')
    } else {
      $(selector).closest('.control-group').addClass('error')
    }
  })
}
function isPercentage(str) {
  // tolerant of leading/trailing spaces
  // tolerant of missing whole (.2) and missing decimal (2.)
  if (/^(0|[1-9][0-9]*)?\.?[0-9]*$/.test($.trim(str))) {
    return parseFloat($.trim(str)) <= 100
  } else {
    return false
  }
}
function parsePercentage(str) {
  // tolerant of leading/trailing spaces
  return $.trim(str)
}
