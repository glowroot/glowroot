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

/* global informant */

informant.factory('httpErrors', [
  function () {
    return {
      get: function (data, status) {
        if (status === 0) {
          return {
            header: 'Unable to connect to server.'
          };
        } else {
          return {
            header: 'An error occurred loading the data for this page.',
            detail: data
          };
        }
      }
    };
  }
]);
