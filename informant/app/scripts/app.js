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

/* global angular, $, Spinner */

var informant = angular.module('informant', [
  'ui.router',
  'ui.bootstrap.dropdownToggle',
  'ui.bootstrap.typeahead',
  'ui.bootstrap.bindHtml'
]);

var Informant;

informant.factory('httpInterceptor', [
  '$q',
  '$timeout',
  function ($q, $timeout) {
    return {
      'responseError': function (rejection) {
        if (rejection.status === 0) {
          // small timeout to prevent error message from flashing if this is a result of user hitting F5
          $timeout(function () {
            $q.reject(rejection);
          }, 100);
        } else {
          $q.reject(rejection);
        }
      }
    };
  }
]);

informant.config([
  '$httpProvider',
  function ($httpProvider) {
    $httpProvider.interceptors.push('httpInterceptor');
  }
]);

informant.run([
  '$rootScope',
  '$timeout',
  function ($rootScope, $timeout) {
    // qtip adds some code to the beginning of jquery's cleanData function which causes the trace
    // detail modal to close slowly when it has 5000 spans
    // this extra cleanup code is not needed anyways since cleanup is performed explicitly
    /* jshint -W106 */ // W106 is camelcase
    $.cleanData = $.cleanData_replacedByqTip;
    /* jshint +W106 */

    // with responsive design, container width doesn't change on every window resize event
    var $container = $('#container');
    var $window = $(window);
    $rootScope.containerWidth = $container.width();
    $rootScope.windowHeight = $window.height();
    $(window).resize(function () {
      var containerWidth = $container.width();
      var windowHeight = $window.height();
      if (containerWidth !== $rootScope.containerWidth || windowHeight !== $rootScope.windowHeight) {
        // one of the relevant dimensions has changed
        $rootScope.$apply(function () {
          $rootScope.containerWidth = containerWidth;
          $rootScope.windowHeight = windowHeight;
        });
      }
    });
  }
]);

informant.controller('FooterCtrl', [
  '$scope',
  '$http',
  function ($scope, $http) {
    $http.get('backend/version')
        .success(function (data) {
          $scope.version = data;
        })
        .error(function (error) {
          // TODO
        });
  }
]);

Informant = (function () {

  function showAndFadeMessage(selector, delay) {
    $(selector).each(function () {
      // handle crazy user clicking on the button
      var $this = $(this);
      if ($this.data('timeout')) {
        clearTimeout($this.data('timeout'));
      }
      $this.stop().animate({opacity: '100'});
      $this.removeClass('hide');
      var outerThis = this;
      $this.data('timeout', setTimeout(function () {
        fadeOut(outerThis, 1000);
      }, delay));
    });
  }

  function fadeOut(selector, duration) {
    // fade out and then override jquery behavior and use hide class instead of display: none
    var $selector = $(selector);
    $selector.fadeOut(duration, function () {
      $selector.addClass('hide');
      $selector.css('display', '');
    });
  }

  function showSpinner(selector, opts) {
    opts = opts || { lines: 10, width: 4, radius: 8 };
    var element = $(selector)[0];
    var spinner = new Spinner(opts);

    // small delay so that if there is an immediate response the spinner doesn't blink
    var timer = setTimeout(function () {
      $(element).removeClass('hide');
      spinner.spin(element);
    }, 100);

    return {
      stop: function () {
        clearTimeout(timer);
        $(element).addClass('hide');
        spinner.stop();
      }
    };
  }

  function configureAjaxError() {
    var modalDiv =
        '<div class="modal hide fade" id="ajaxErrorModal" tabindex="-1"' +
            '    style="width: 800px; margin: -300px 0 0 -400px; max-height: 600px;">' +
            '  <div class="modal-header">' +
            '    <button class="close" data-dismiss="modal">&times;</button>' +
            '    <h3>Ajax Error</h3>' +
            '  </div>' +
            '  <div class="modal-body" id="ajaxError"></div>' +
            '  <div class="modal-footer">' +
            '    <button class="btn" data-dismiss="modal">Close</button>' +
            '  </div>' +
            '</div>';
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
  }

  $(document).on('keypress', '.refresh-data-on-enter-key', function (event) {
    if (event.which === 13) {
      // trigger button so it will active spinner and success message
      $('.form-submit > button').click();
      // without preventDefault, enter triggers 'more filters' button
      event.preventDefault();
    }
  });

  return {
    showAndFadeSuccessMessage: function (selector) {
      showAndFadeMessage(selector, 0);
    },
    // TODO unused
    showAndFadeErrorMessage: function (selector) {
      showAndFadeMessage(selector, 1000);
    },
    fadeOut: fadeOut,
    showSpinner: showSpinner,
    configureAjaxError: configureAjaxError
  };
})();
