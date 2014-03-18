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

/* global glowroot */

glowroot.factory('conversions', [
  function () {
    function toNumber(value) {
      // map to number
      if (value === '') {
        // map empty string to null number
        return null;
      }
      // try to convert to number
      var float = parseFloat(value);
      if (isNaN(float)) {
        return value;
      }
      return float;
    }

    return {
      toNumber: toNumber
    };
  }
]);
