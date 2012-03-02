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
  $(selector).each(function() {
    // handling crazy user clicking on the button
    if ($(this).data('timeout')) {
      clearTimeout($(this).data('timeout'))
    }
    $(this).stop().animate({opacity:'100'})
    $(this).css('display','inline-block')
    var outerThis = this
    $(this).data('timeout', setTimeout(function() { $(outerThis).fadeOut(1000) }, 1000))
  })
}
function showSpinner(selector) {
  $(selector).each(function() {
    var data = $(this).data()
    data.spinner = new Spinner({ lines: 10, width: 4, radius: 8 })
    var outerThis = this
    function displaySpinner() {
      // data.spinner may have been cleared already by hideSpinner() before setTimeout triggered
      if (data.spinner) {
        $(outerThis).css('display','inline-block')
        data.spinner.spin(outerThis)
      }
    }
    // small delay so that if there is an immediate page return it doesn't blink
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
    $(this).hide()
  })
}
