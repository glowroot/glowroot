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

/* global informant, $ */

informant.controller('ConfigCtrl', [
  '$scope',
  '$http',
  '$state',
  function ($scope, $http, $state) {
    // \u00b7 is &middot;
    document.title = 'Configuration \u00b7 Informant';
    $scope.$parent.title = 'Configuration';
    $scope.$parent.activeNavbarItem = 'config';

    $scope.isCurrentView = function (viewName) {
      return $state.current.name === viewName;
    };

    $scope.pattern = {
      // TODO allow comma as decimal separator (and check out html5 input type="number")
      // tolerant of missing whole (.2) and missing decimal (2.)
      percentage: /^(0|[1-9][0-9]?|100)?(\\.[0-9]*)?$/,
      // tolerant of commas
      integer: /^(0|[1-9][0-9]*)$/,
      // missing whole (.2) and missing decimal (2.)
      double: /^(0|[1-9][0-9]*)?(\\.[0-9]*)?$/
    };

    function setUpSidebar() {
      // set up sidebar scrolling
      var $sidebar = $('.ix-sidebar');
      var offsetTop = $sidebar.offset().top;
      var sideBarTopMargin = 30;
      var navOuterHeight = $('.navbar').height();
      var offset = {
        top: offsetTop - navOuterHeight - sideBarTopMargin,
        // +20 is for padding between sidebar and footer
        // (although need a really short and wide screen for this to be necessary)
        bottom: $('#footer').outerHeight(true) + 20
      };
      $sidebar.affix({ offset: offset });
    }

    setTimeout(setUpSidebar, 100);
  }
]);
