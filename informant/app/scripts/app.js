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

informant.factory('httpInterceptor', function ($q, $timeout) {
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
});

informant.config(function ($stateProvider, $urlRouterProvider, $httpProvider) {
  $urlRouterProvider.otherwise('/');
  $stateProvider.state('home', {
    url: '/',
    templateUrl: 'views/home.html',
    controller: 'HomeCtrl'
  });
  $stateProvider.state('search', {
    url: '/search.html',
    templateUrl: 'views/search.html',
    controller: 'SearchCtrl'
  });
  $stateProvider.state('config', {
    url: '/config',
    templateUrl: 'views/config.html',
    controller: 'ConfigCtrl'
  });
  $stateProvider.state('config.general', {
    url: '/general.html',
    templateUrl: 'views/config-general.html',
    controller: 'ConfigGeneralCtrl'
  });
  $stateProvider.state('config.coarseProfiling', {
    url: '/coarse-grained-profiling.html',
    templateUrl: 'views/config-coarse-profiling.html',
    controller: 'ConfigCoarseProfilingCtrl'
  });
  $stateProvider.state('config.fineProfiling', {
    url: '/fine-grained-profiling.html',
    templateUrl: 'views/config-fine-profiling.html',
    controller: 'ConfigFineProfilingCtrl'
  });
  $stateProvider.state('config.userOverrides', {
    url: '/user-specific-overrides.html',
    templateUrl: 'views/config-user-overrides.html',
    controller: 'ConfigUserOverridesCtrl'
  });
  $stateProvider.state('config.storage', {
    url: '/storage.html',
    templateUrl: 'views/config-storage.html',
    controller: 'ConfigStorageCtrl'
  });
  $stateProvider.state('config.plugins', {
    url: '/plugins.html',
    templateUrl: 'views/config-plugins.html',
    controller: 'ConfigPluginsCtrl'
  });
  $stateProvider.state('config.adhocPointcuts', {
    url: '/adhoc-pointcuts.html',
    templateUrl: 'views/config-adhoc-pointcuts.html',
    controller: 'ConfigAdhocPointcutsCtrl'
  });
  $stateProvider.state('threadDump', {
    url: '/thread-dump.html',
    templateUrl: 'views/thread-dump.html',
    controller: 'ThreadDumpCtrl'
  });

  $httpProvider.interceptors.push('httpInterceptor');
});

informant.run(function ($rootScope, $timeout) {
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
      $rootScope.$apply(function() {
        $rootScope.containerWidth = containerWidth;
        $rootScope.windowHeight = windowHeight;
      });
    }
  });
});

informant.controller('FooterCtrl', function ($scope, $http) {
  $http.get('backend/version')
      .success(function (data) {
        $scope.version = data;
      })
      .error(function (error) {
        // TODO
      });
});

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
  }

  function hideSpinner(selector) {
    $(selector).each(function () {
      var $this = $(this);
      var data = $this.data();
      if (data.spinner) {
        data.spinner.stop();
        delete data.spinner;
      }
      $this.addClass('hide');
    });
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
    hideSpinner: hideSpinner,
    configureAjaxError: configureAjaxError
  };
})();
