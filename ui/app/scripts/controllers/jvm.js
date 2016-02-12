/*
 * Copyright 2013-2016 the original author or authors.
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

glowroot.controller('JvmCtrl', [
  '$scope',
  '$location',
  'queryStrings',
  function ($scope, $location, queryStrings) {
    // \u00b7 is &middot;
    document.title = 'JVM \u00b7 Glowroot';
    $scope.$parent.activeNavbarItem = 'jvm';

    $scope.hideAgentRollupDropdown = function () {
      if (!$scope.layout) {
        // this is ok, under grunt serve and layout hasn't loaded yet
        return true;
      }
      return $scope.layout.agentRollups.length === 1 || $scope.layout.fat;
    };

    $scope.hideMainContent = function () {
      return !$scope.agentRollup && !$scope.agentId && !$scope.layout.fat;
    };

    $scope.currentUrl = function () {
      return $location.path().substring(1);
    };

    $scope.$on('$stateChangeStart', function () {
      // don't let the active sidebar selection get out of sync (which can happen after using the back button)
      if (document.activeElement) {
        var gtUrl = document.activeElement.getAttribute('gt-url');
        if (gtUrl && gtUrl !== $location.path().substring(1)) {
          document.activeElement.blur();
        }
      }
    });
  }
]);
