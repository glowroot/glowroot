/*
 * Copyright 2013 the original author or authors.
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

/* global glowroot */

glowroot.controller('PluginCtrl', [
  '$scope',
  '$state',
  '$http',
  'httpErrors',
  function ($scope, $state, $http, httpErrors) {
    // \u00b7 is &middot;
    document.title = 'Plugins \u00b7 Glowroot';
    $scope.$parent.title = 'Plugins';
    $scope.$parent.activeNavbarItem = 'plugin';

    $scope.isCurrentView = function (viewName) {
      return $state.params.id === viewName;
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
  }
]);
