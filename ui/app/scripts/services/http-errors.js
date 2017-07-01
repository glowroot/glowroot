/*
 * Copyright 2013-2017 the original author or authors.
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
  '$rootScope',
  function ($rootScope) {

    $rootScope.$on('$locationChangeSuccess', function () {
      // e.g. clear error on back button
      delete $rootScope.httpError;
    });

    function getHttpErrorsObject(response) {
      if (response.status === 0 || response.status === -1) {
        return {
          headline: 'Unable to connect to server'
        };
      } else {
        var data = response.data;
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
      handle: function (response, $scope, deferred) {
        if (deferred) {
          // all actions that need to handle HTTP Precondition Failed pass a deferred object
          if (response.status === 403) {
            deferred.reject('You don\'t have permission for this action');
          } else if (response.status === 412) {
            // HTTP Precondition Failed
            deferred.reject('Someone else has updated the data on this page, please reload and try again');
          } else {
            $rootScope.httpError = getHttpErrorsObject(response);
            deferred.reject($rootScope.httpError.headline);
          }
        } else {
          $rootScope.httpError = getHttpErrorsObject(response);
        }
      },
      clear: function () {
        delete $rootScope.httpError;
      }
    };
  }
]);
