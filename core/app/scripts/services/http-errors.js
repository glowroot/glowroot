/*
 * Copyright 2013-2014 the original author or authors.
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

glowroot.factory('httpErrors', [
  function () {
    function getHttpErrorsObject(data, status) {
      if (status === 0) {
        return {
          headline: 'Unable to connect to server'
        };
      } else {
        var message = data.message;
        if (!message && !data.stackTrace) {
          message = data;
        }
        return {
          headline: 'An error occurred',
          message: message,
          stackTrace: data.stackTrace
        };
      }
    }

    return {
      get: getHttpErrorsObject,
      handler: function ($scope, deferred) {
        if (deferred) {
          return function (data, status) {
            // all actions that need to handle HTTP Precondition Failed pass a deferred object
            if (status === 412) {
              // HTTP Precondition Failed
              deferred.reject('Someone else has updated the data on this page, please reload and try again');
            } else {
              $scope.httpError = getHttpErrorsObject(data, status);
              deferred.reject($scope.httpError.headline);
            }
          };
        } else {
          return function (data, status) {
            $scope.httpError = getHttpErrorsObject(data, status);
          };
        }
      }
    };
  }
]);
