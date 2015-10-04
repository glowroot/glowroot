/*
 * Copyright 2012-2015 the original author or authors.
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

glowroot.controller('ConfigGaugeListCtrl', [
  '$scope',
  '$location',
  '$http',
  'httpErrors',
  function ($scope, $location, $http, httpErrors) {

    $scope.display = function (gauge) {
      return gauge.config.display.replace(/\//g, '\u200b/');
    };

    $http.get('backend/config/gauges?server=' + $scope.server)
        .success(function (data) {
          $scope.loaded = true;
          $scope.gauges = data;
        })
        .error(httpErrors.handler($scope));
  }
]);
