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

/* global angular, $, Spinner */

var glowroot = angular.module('glowroot', [
  'ui.router',
  'ui.bootstrap.dropdownToggle',
  'ui.bootstrap.typeahead',
  'ui.bootstrap.bindHtml',
  'ui.bootstrap.modal',
  'ui.bootstrap.collapse'
]);

var Glowroot;

glowroot.config([
  '$locationProvider',
  '$httpProvider',
  function ($locationProvider, $httpProvider) {
    $locationProvider.html5Mode(true);
    var interceptor = ['$location', '$q', '$timeout', 'login', function ($location, $q, $timeout, login) {
      return {
        responseError: function (response) {
          if (response.status === 401) {
            var path = $location.path();
            // only act on the first 401 response in case more than one request was triggered
            if (path === '/login') {
              // return a never-resolving promise
              return $q.defer().promise;
            }
            if (response.data.timedOut) {
              login.showLogin('Your session has timed out');
            } else {
              login.showLogin();
            }
            // return a never-resolving promise
            return $q.defer().promise;
          }
          if (response.status === 0) {
            // this can be caused by the user hitting F5 refresh in the middle of an ajax request (which seems not that
            // uncommon if ajax response happens to be slow), so defer the rejection a bit so the error will not be
            // displayed in this case
            //
            // the other common case for status === 0 is when the server is down altogether, and the message for this
            // case is generated downstream in http-errors (after the slight delay)
            var deferred = $q.defer();
            $timeout(function () {
              deferred.reject(response);
            }, 500);
            return deferred.promise;
          }
          return $q.reject(response);
        }
      };
    }];
    $httpProvider.interceptors.push(interceptor);
  }
]);

glowroot.run([
  '$rootScope',
  '$http',
  '$location',
  'login',
  function ($rootScope, $http, $location, login) {

    $rootScope.signOut = function () {
      $http.post('backend/sign-out')
          .success(function () {
            login.showLogin('You have been signed out');
          })
          .error(function (error) {
            // TODO
          });
    };

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

    function setInitialLayout(data) {
      $rootScope.layout = data;
      if ($rootScope.layout.needsAuthentication) {
        login.showLogin();
      } else if ($location.path() === '/login') {
        // authentication is not needed
        $location.path('/').replace();
      }
      if ($rootScope.layout.passwordEnabled) {
        // received authenticated layout and password is enabled, so show sign out button
        $rootScope.showSignOutButton = true;
      }
    }

    if (window.layout) {
      setInitialLayout(window.layout);
    } else {
      // running in dev under 'grunt server'
      $http.get('backend/layout')
          .success(function (data) {
            setInitialLayout(data);
          });
    }
  }
]);

Glowroot = (function () {

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

  return {
    showAndFadeSuccessMessage: function (selector) {
      showAndFadeMessage(selector, 1500);
    },
    fadeOut: fadeOut,
    showSpinner: showSpinner,
    configureAjaxError: configureAjaxError
  };
})();
