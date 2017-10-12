/*
 * Copyright 2012-2017 the original author or authors.
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

/* global angular, moment, $, Spinner */

var glowroot = angular.module('glowroot', [
  'ui.router',
  'ui.bootstrap.buttons',
  'ui.bootstrap.dropdown',
  'ui.bootstrap.popover',
  'ui.bootstrap.typeahead',
  'ui.bootstrap.modal',
  'ui.bootstrap.debounce',
  'ui.codemirror',
  'ui.select',
  'ngSanitize'
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
                  .then(function (response) {
                    $rootScope.setLayout(response.data);
                  }, function (response) {
                    $rootScope.navbarErrorMessage = 'An error occurred getting layout';
                    if (response.data.message) {
                      $rootScope.navbarErrorMessage += ': ' + response.data.message;
                    }
                    var unregisterListener = $rootScope.$on('gtStateChangeSuccess', function () {
                      $rootScope.navbarErrorMessage = '';
                      unregisterListener();
                    });
                  });
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
  '$transitions',
  '$window',
  '$state',
  '$timeout',
  'login',
  'queryStrings',
  function ($rootScope, $http, $location, $transitions, $window, $state, $timeout, login, queryStrings) {

    $rootScope.agentId = '';

    $rootScope.$on('$locationChangeSuccess', function () {
      $rootScope.agentId = $location.search()['agent-id'] || '';
      $rootScope.agentRollupId = $location.search()['agent-rollup-id'] || $rootScope.agentId;
      if ($rootScope.layout) {
        // layout doesn't exist on first page load when running under grunt serve
        if (!$rootScope.layout.central || $rootScope.agentRollupId) {
          var agentRollup = $rootScope.layout.agentRollups[$rootScope.agentRollupId];
          $rootScope.agentPermissions = agentRollup ? agentRollup.permissions : undefined;
        } else {
          delete $rootScope.agentPermissions;
        }
      }
    });

    $transitions.onSuccess({}, function () {
      $rootScope.$broadcast('gtStateChangeSuccess');
    });

    $rootScope.agentQueryString = function () {
      if (!$rootScope.layout.central) {
        return '';
      }
      if ($rootScope.agentId) {
        return '?agent-id=' + encodeURIComponent($rootScope.agentId);
      } else if ($rootScope.agentRollupId) {
        return '?agent-rollup-id=' + encodeURIComponent($rootScope.agentRollupId);
      } else {
        return '';
      }
    };

    $rootScope.agentRollupUrl = function (agentRollup) {
      var query = $rootScope.agentRollupQuery(agentRollup);
      return $location.path().substring(1) + queryStrings.encodeObject(query);
    };

    $rootScope.agentRollupQuery = function (agentRollup) {
      // preserve existing query string
      var search = angular.copy($location.search());
      delete search['agent-rollup-id'];
      delete search['agent-id'];
      var query = {};
      if (agentRollup.agent) {
        query['agent-id'] = agentRollup.id;
      } else {
        query['agent-rollup-id'] = agentRollup.id;
      }
      angular.merge(query, search);
      return query;
    };

    $rootScope.transactionTypes = function () {
      if (!$rootScope.layout) {
        return [];
      }
      if (!$rootScope.layout.agentRollups) {
        return [];
      }
      var agentRollup = $rootScope.layout.agentRollups[$rootScope.agentRollupId];
      if (!agentRollup) {
        return [];
      }
      return agentRollup.transactionTypes;
    };

    $rootScope.defaultTransactionType = function () {
      if (!$rootScope.layout) {
        return '';
      }
      if (!$rootScope.layout.agentRollups) {
        // login page, not yet authenticated
        return '';
      }
      // can't use $rootScope.agentRollupId here because this function is called from waitForLayout() function in
      // routes.js before $rootScope.agentRollupId is set (note for testing, this is only a problem when not under grunt
      // serve)
      var agentRollupId = $location.search()['agent-rollup-id'] || $location.search()['agent-id'] || '';
      var agentRollup = $rootScope.layout.agentRollups[agentRollupId];
      if (!agentRollup) {
        return '';
      }
      return agentRollup.defaultDisplayedTransactionType;
    };

    $rootScope.goToLogin = function (event) {
      if (!event.ctrlKey) {
        login.goToLogin();
        // suppress normal hyperlink
        return false;
      }
      return true;
    };

    $rootScope.signOut = function () {
      // need to collapse the navbar in mobile view
      var $navbarCollapse = $('.navbar-collapse');
      $navbarCollapse.removeClass('in');
      $navbarCollapse.addClass('collapse');
      $http.post('backend/sign-out')
          .then(function (response) {
            $rootScope.setLayout(response.data);
            if (!$rootScope.layout.redirectToLogin) {
              $rootScope.displaySignOutMessage = true;
              $timeout(function () {
                $rootScope.displaySignOutMessage = false;
              }, 2000);
            }
          }, function (response) {
            $rootScope.navbarErrorMessage = 'An error occurred signing out';
            if (response.data.message) {
              $rootScope.navbarErrorMessage += ': ' + response.data.message;
            }
            var unregisterListener = $rootScope.$on('gtStateChangeSuccess', function () {
              $rootScope.navbarErrorMessage = '';
              unregisterListener();
            });
          });
    };

    $rootScope.hideNavbar = function () {
      return $location.path() === '/login';
    };

    // with responsive design, container width doesn't change on every window resize event
    var $container = $('#container');
    var $windowjq = $(window);
    $rootScope.containerWidth = $container.width();
    $rootScope.windowHeight = $windowjq.height();
    $(window).resize(function () {
      var containerWidth = $container.width();
      var windowHeight = $windowjq.height();
      if (containerWidth !== $rootScope.containerWidth || windowHeight !== $rootScope.windowHeight) {
        // one of the relevant dimensions has changed
        $rootScope.$apply(function () {
          $rootScope.containerWidth = containerWidth;
          $rootScope.windowHeight = windowHeight;
        });
      }
    });

    // check layout every 60 seconds, this will notice when session expires and sending user to /login
    function scheduleNextCheckLayout() {
      $timeout(function () {
        $http.get('backend/check-layout')
            .then(function () {
              // Glowroot-Layout-Version is returned and the http interceptor will notice and take appropriate action
              scheduleNextCheckLayout();
            }, function () {
              // ok to ignore, e.g. temporary network disconnect
              scheduleNextCheckLayout();
            });
      }, 60000);
    }

    scheduleNextCheckLayout();

    var glowrootVersion;

    $rootScope.initLayout = function () {
      var priorAgentRollupValues = $rootScope.layout.agentRollupValues;
      // agentRollupValues is needed when using angular ng-repeat over agentRollups in case there are
      // any agent rollup ids that start with '$', because angular silently ignores object keys starting with '$'
      // see https://docs.angularjs.org/api/ng/directive/ngRepeat
      $rootScope.layout.agentRollupValues = [];
      angular.forEach($rootScope.layout.agentRollups, function (agentRollup, agentRollupId) {
        var indent = '';
        for (var i = 0; i < agentRollup.depth; i++) {
          indent += '\u00a0\u00a0\u00a0\u00a0';
        }
        agentRollup.indentedDisplay = indent + agentRollup.display;
        agentRollup.id = agentRollupId;
        $rootScope.layout.agentRollupValues.push(agentRollup);
      });
      if (!angular.equals($rootScope.layout.agentRollupValues, priorAgentRollupValues)) {
        // this is kinda hacky
        var $agentRollupDropdown = $('#agentRollupDropdown');
        if ($agentRollupDropdown.length) {
          // need to delay a bit to give a chance for option ng-repeat to be updated
          $timeout(function () {
            $agentRollupDropdown.selectpicker('refresh');
          }, 1000);
        }
      }
      if (!$rootScope.layout.central || $rootScope.agentRollupId) {
        var agentRollup = $rootScope.layout.agentRollups[$rootScope.agentRollupId];
        $rootScope.agentPermissions = agentRollup ? agentRollup.permissions : undefined;
      } else {
        delete $rootScope.agentPermissions;
      }
      var timeZoneIdMap = {};
      angular.forEach(moment.tz.names(), function (timeZoneId) {
        timeZoneIdMap[timeZoneId] = true;
      });
      var timeZoneIds = [];
      angular.forEach($rootScope.layout.timeZoneIds, function (timeZoneId) {
        if (timeZoneIdMap[timeZoneId]) {
          timeZoneIds.push(timeZoneId);
        }
      });
      $rootScope.layout.timeZoneIds = timeZoneIds;

      function forceRefreshDueToNewVersion(remaining) {
        if (remaining === 0) {
          $window.location.reload();
        } else {
          $rootScope.navbarErrorMessage = 'New version of Glowroot UI has been deployed, refreshing browser in '
              + remaining + ' second' + (remaining === 1 ? '' : 's') + '...';
          $timeout(function () {
            forceRefreshDueToNewVersion(remaining - 1);
          }, 1000);
        }
      }

      if (glowrootVersion && glowrootVersion !== $rootScope.layout.glowrootVersion) {
        forceRefreshDueToNewVersion(10);
      }
      glowrootVersion = $rootScope.layout.glowrootVersion;
    };

    $rootScope.setLayout = function (data) {
      $rootScope.layout = data;
      $rootScope.initLayout();
      if ($rootScope.layout.redirectToLogin) {
        login.goToLogin();
      } else if ($location.path() === '/login' && (data.loggedIn || !data.loginEnabled)) {
        // authentication is not needed
        $location.path('/').replace();
      }
    };

    if (window.layout) {
      $rootScope.setLayout(window.layout);
    } else {
      // running in dev under 'grunt serve'
      $http.get('backend/layout')
          .then(function (response) {
            $rootScope.setLayout(response.data);
          }, function (response) {
            $rootScope.navbarErrorMessage = 'An error occurred getting layout';
            if (response.data.message) {
              $rootScope.navbarErrorMessage += ': ' + response.data.message;
            }
            var unregisterListener = $rootScope.$on('gtStateChangeSuccess', function () {
              $rootScope.navbarErrorMessage = '';
              unregisterListener();
            });
          });
    }

    $transitions.onSuccess({}, function () {
      // google analytics is enabled on https://demo.glowroot.org using the
      // system property glowroot.internal.googleAnalyticsTrackingId
      if (window.ga) {
        window.ga('send', 'pageview', {page: $location.path()});
      }
    });

    // tolerant of missing whole (.2) and missing decimal (2.)
    var percentileRegexp = '([1-9]?[0-9]?(\\.[0-9]*)?|100(\\.0*)?)';
    $rootScope.pattern = {
      percentage: new RegExp('^' + percentileRegexp + '$'),
      percentageList: new RegExp('^(' + percentileRegexp + ' *, *)*' + percentileRegexp + '$'),
      integer: /^(0|[1-9][0-9]*)$/,
      // tolerant of missing whole (.2) and missing decimal (2.)
      double: /^(0|[1-9][0-9]*)?(\.[0-9]*)?$/
    };

    // don't close dropdown menus on ctrl click (e.g. for ctrl clicking and opening multiple tabs)
    $(document).on('click', '.gt-header-page-name .dropdown-menu', function (event) {
      if (event.ctrlKey) {
        event.stopPropagation();
      }
    });
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

  function cancelFadeMessage(selector) {
    $(selector).each(function () {
      var $this = $(this);
      if ($this.data('gtTimeout')) {
        clearTimeout($this.data('gtTimeout'));
      }
      $this.stop().animate({opacity: '100'});
      $this.removeClass('hide');
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

  function showSpinner(selector, callbackOnStart) {
    var element = $(selector)[0];
    // z-index should be less than navbar (which is 1030)
    var spinner = new Spinner({lines: 9, radius: 8, width: 5, zIndex: 1020});

    // small delay so that if there is an immediate response the spinner doesn't blink
    var timer = setTimeout(function () {
      $(element).removeClass('hide');
      spinner.spin(element);
      if (callbackOnStart) {
        callbackOnStart();
      }
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
    cancelFadeSuccessMessage: cancelFadeMessage,
    fadeOut: fadeOut,
    showSpinner: showSpinner
  };
})();

// hack using some code from bootstrap's button.js until https://github.com/angular-ui/bootstrap/issues/3264
$(document)
    .on('focus.bs.button.data-api blur.bs.button.data-api', '[data-toggle^="button"]', function (e) {
      $(e.target).closest('.btn').toggleClass('focus', /^focus(in)?$/.test(e.type));
    });
