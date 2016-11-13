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

/* global glowroot, $ */

glowroot.factory('login', [
  '$location',
  function ($location) {
    var originalUrl;
    var message;
    return {
      goToLogin: function (msg, doNotSaveLocation) {
        message = msg;
        originalUrl = doNotSaveLocation ? '/' : $location.url();
        if (originalUrl !== '/login') {
          // close modal backdrop if open, this is needed if click on trace point results in 401 response
          $('.modal-backdrop').remove();
          // don't do .replace() here, since then back button doesn't work after clicking login
          // (this is relevant when anonymous access is enabled)
          $location.url('/login');
        }
      },
      getMessage: function () {
        return message;
      },
      returnToOriginalPath: function () {
        // originalPath can be undefined if user hits login page directly
        if (originalUrl && originalUrl !== '/login') {
          // don't do .replace() here, since then back button goes back to page right before /login, which is the same
          // page as right after /login, which is then confusing
          // (this is relevant when anonymous access is enabled)
          $location.url(originalUrl);
        } else {
          // don't do .replace() here for same reason as above
          $location.url('/');
        }
      }
    };
  }
]);

