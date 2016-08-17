/*
 * Copyright 2012-2016 the original author or authors.
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

/* global angular, $, Spinner, ZeroClipboard, alert */

var glowroot = angular.module('glowroot', [
  'ui.router',
  'ui.bootstrap.buttons',
  'ui.bootstrap.dropdown',
  'ui.bootstrap.popover',
  'ui.bootstrap.typeahead',
  'ui.bootstrap.modal',
  'ui.bootstrap.debounce'
]);

var Glowroot;

glowroot.config([
  '$locationProvider',
  '$httpProvider',
  function ($locationProvider, $httpProvider) {
    $locationProvider.html5Mode(true);
    var interceptor = [
      '$rootScope',
      '$injector',
      '$location',
      '$q',
      '$timeout',
      'login',
      function ($rootScope, $injector, $location, $q, $timeout, login) {
        return {
          response: function (response) {
            var layoutVersion = response.headers('Glowroot-Layout-Version');
            if (layoutVersion && $rootScope.layout && layoutVersion !== $rootScope.layout.version) {
              $injector.get('$http').get('backend/layout')
                  .success(function (data) {
                    $rootScope.setLayout(data);
                  });
              // TODO handle error() above
            }
            return response;
          },
          responseError: function (response) {
            if (response.status === 401) {
              var path = $location.path();
              // only act on the first 401 response in case more than one request was triggered
              if (path === '/login') {
                // return a never-resolving promise
                return $q.defer().promise;
              }
              if (response.data.timedOut) {
                login.goToLogin('Your session has timed out');
              } else {
                login.goToLogin();
              }
              // return a never-resolving promise
              return $q.defer().promise;
            }
            if (response.status === 0) {
              // this can be caused by the user hitting F5 refresh in the middle of an ajax request (which seems not
              // that uncommon if ajax response happens to be slow), so defer the rejection a bit so the error will not
              // be displayed in this case
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
  '$state',
  '$timeout',
  'login',
  'queryStrings',
  function ($rootScope, $http, $location, $state, $timeout, login, queryStrings) {

    $rootScope.agentId = '';

    $rootScope.$on('$locationChangeSuccess', function () {
      $rootScope.agentId = $location.search()['agent-id'] || '';
      $rootScope.agentRollup = $location.search()['agent-rollup'] || $rootScope.agentId;
      if ($rootScope.layout) {
        // layout doesn't exist on first page load when running under grunt serve
        if ($rootScope.layout.fat || $rootScope.agentRollup) {
          var agentRollup = $rootScope.layout.agentRollups[$rootScope.agentRollup];
          $rootScope.agentPermissions = agentRollup ? agentRollup.permissions : undefined;
        } else {
          $rootScope.agentPermissions = undefined;
        }
      }
    });

    $rootScope.agentQueryString = function () {
      if ($rootScope.layout.fat) {
        return '';
      }
      if ($rootScope.agentId) {
        return '?agent-id=' + encodeURIComponent($rootScope.agentId);
      } else if ($rootScope.agentRollup) {
        return '?agent-rollup=' + encodeURIComponent($rootScope.agentRollup);
      } else {
        return '';
      }
    };

    $rootScope.agentRollupUrl = function (agentRollup, leaf) {
      var url = $location.path().substring(1);
      // preserve query string
      var query = angular.copy($location.search());
      if (leaf) {
        query['agent-id'] = agentRollup;
      } else {
        query['agent-rollup'] = agentRollup;
      }
      url += queryStrings.encodeObject(query);
      return url;
    };

    $rootScope.transactionTypes = function () {
      if (!$rootScope.layout) {
        return [];
      }
      if (!$rootScope.layout.agentRollups) {
        return [];
      }
      var agentRollupObj = $rootScope.layout.agentRollups[$rootScope.agentRollup];
      if (!agentRollupObj) {
        return [];
      }
      return agentRollupObj.transactionTypes;
    };

    $rootScope.defaultTransactionType = function () {
      if (!$rootScope.layout) {
        return '';
      }
      if (!$rootScope.layout.agentRollups) {
        // login page, not yet authenticated
        return '';
      }
      // can't use $rootScope.agentRollup here because this function is called from waitForLayout() function in
      // routes.js before $rootScope.agentRollup is set (note for testing, this is only a problem when not under grunt
      // serve)
      var agentRollup = $location.search()['agent-rollup'] || $location.search()['agent-id'] || '';
      var agentRollupObj = $rootScope.layout.agentRollups[agentRollup];
      if (!agentRollupObj) {
        return '';
      }
      return agentRollupObj.defaultDisplayedTransactionType;
    };

    $rootScope.showSignIn = function () {
      return $rootScope.layout && !$rootScope.layout.loggedIn && !$rootScope.layout.hideLogin;
    };

    $rootScope.showSignOut = function () {
      return $rootScope.layout && $rootScope.layout.loggedIn;
    };

    $rootScope.goToLogin = function (event) {
      if (!event.ctrlKey) {
        login.goToLogin();
        // suppress normal hyperlink
        return false;
      }
    };

    $rootScope.signOut = function () {
      // need to collapse the navbar in mobile view
      var $navbarCollapse = $('.navbar-collapse');
      $navbarCollapse.removeClass('in');
      $navbarCollapse.addClass('collapse');
      $http.post('backend/sign-out')
          .success(function (data) {
            $rootScope.setLayout(data);
            if (!$rootScope.layout.redirectToLogin) {
              $rootScope.displaySignOutMessage = true;
              $timeout(function () {
                $rootScope.displaySignOutMessage = false;
              }, 2000);
            }
          })
          .error(function () {
            // there is not an obvious placement on the screen for this error message
            // since the action is triggered from navbar on any screen
            alert('An error occurred during log out');
          });
    };

    $rootScope.hideNavbar = function () {
      return $location.path() === '/login';
    };

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

    $rootScope.setLayout = function(data) {
      $rootScope.layout = data;
      if ($rootScope.layout.redirectToLogin) {
        login.goToLogin();
      } else if ($location.path() === '/login' && (data.loggedIn || data.hideLogin)) {
        // authentication is not needed
        $location.path('/').replace();
      } else if ($rootScope.layout.fat || $rootScope.agentRollup) {
        var agentRollup = $rootScope.layout.agentRollups[$rootScope.agentRollup];
        $rootScope.agentPermissions = agentRollup ? agentRollup.permissions : undefined;
      } else {
        $rootScope.agentPermissions = undefined;
      }
    };

    if (window.layout) {
      $rootScope.setLayout(window.layout);
    } else {
      // running in dev under 'grunt serve'
      $http.get('backend/layout')
          .success(function (data) {
            $rootScope.setLayout(data);
          });
    }

    $rootScope.$on('$stateChangeSuccess', function () {
      // google analytics is enabled on https://demo.glowroot.org using the
      // system property glowroot.internal.googleAnalyticsTrackingId
      if (window.ga) {
        window.ga('send', 'pageview', {page: $location.path()});
      }
    });

    // tolerant of missing whole (.2) and missing decimal (2.)
    var percentileRegexp = '([1-9]?[0-9]?(\\.[0-9]*)?|100(\\.0*)?)';
    $rootScope.pattern = {
      percentile: new RegExp('^' + percentileRegexp + '$'),
      percentileList: new RegExp('^(' + percentileRegexp + ' *, *)*' + percentileRegexp + '$'),
      integer: /^(0|[1-9][0-9]*)$/,
      // tolerant of missing whole (.2) and missing decimal (2.)
      double: /^(0|[1-9][0-9]*)?(\.[0-9]*)?$/
    };

    ZeroClipboard.config({
      bubbleEvents: false,
      // cache busting is not required since ZeroClipboard.swf is revved during grunt build
      cacheBust: false
    });
    // this is a workaround for "IE freezes when clicking a ZeroClipboard clipped element within a Bootstrap Modal"
    // see https://github.com/zeroclipboard/zeroclipboard/blob/master/docs/instructions.md#workaround-a
    $(document).on('focusin', '#global-zeroclipboard-html-bridge', false);
  }
]);

Glowroot = (function () {

  function showAndFadeMessage(selector, delay) {
    $(selector).each(function () {
      // handle crazy user clicking on the button
      var $this = $(this);
      if ($this.data('gtTimeout')) {
        clearTimeout($this.data('gtTimeout'));
      }
      $this.stop().animate({opacity: '100'});
      $this.removeClass('hide');
      var outerThis = this;
      $this.data('gtTimeout', setTimeout(function () {
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
    // z-index should be less than navbar (which is 1030)
    opts = opts || {lines: 9, radius: 8, width: 5, zIndex: 1020};
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

  return {
    showAndFadeSuccessMessage: function (selector) {
      showAndFadeMessage(selector, 1500);
    },
    fadeOut: fadeOut,
    showSpinner: showSpinner
  };
})();

// hack using some code from bootstrap's button.js until https://github.com/angular-ui/bootstrap/issues/3264
$(document)
    .on('focus.bs.button.data-api blur.bs.button.data-api', '[data-toggle^="button"]', function (e) {
      $(e.target).closest('.btn').toggleClass('focus', /^focus(in)?$/.test(e.type));
    });
