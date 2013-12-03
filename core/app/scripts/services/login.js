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

glowroot.factory('login', [
  '$location',
  function ($location) {
    var originalPath;
    var message;
    return {
      showLogin: function (msg) {
        message = msg;
        originalPath = $location.path();
        if (originalPath !== '/login') {
          $location.path('/login').replace();
        }
      },
      getMessage: function () {
        return message;
      },
      returnToOriginalPath: function() {
        // originalPath can be undefined if user hits login page directly
        if (originalPath && originalPath !== '/login') {
          $location.path(originalPath).replace();
        } else {
          $location.path('/').replace();
        }
      }
    };
  }
]);
