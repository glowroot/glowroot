/*
 * Copyright 2014 the original author or authors.
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

/* global glowroot, HandlebarsRendering, $ */

glowroot.controller('AggregateDetailCtrl', [
  '$scope',
  '$http',
  '$modalInstance',
  'queryStrings',
  'httpErrors',
  'aggregateQuery',
  function ($scope, $http, $modalInstance, queryStrings, httpErrors, aggregateQuery) {

    var queryString = queryStrings.encodeObject(aggregateQuery);
    $http.get('backend/aggregate/header?' + queryStrings.encodeObject(aggregateQuery))
        .success(function (data) {
          data.showExport = true;
          HandlebarsRendering.renderAggregate(data, $('#modalContent'), queryString);
          $scope.loaded = true;
        })
      // TODO need to implement httpErrors here in modal ui?
        .error(httpErrors.handler($scope));

    $scope.close = function () {
      $modalInstance.close();
    };
  }
]);
