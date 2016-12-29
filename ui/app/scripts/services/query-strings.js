/*
 * Copyright 2014-2016 the original author or authors.
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

/* global glowroot, angular */

glowroot.factory('queryStrings', [
  function () {
    function encodeObject(object) {
      var keys = Object.keys(object);
      var ordered = [];
      function addKeyToOrdered(name) {
        var index = keys.indexOf(name);
        if (index !== -1) {
          ordered.push(name);
          keys.splice(index, 1);
        }
      }
      addKeyToOrdered('agent-rollup-id');
      addKeyToOrdered('agent-id');
      addKeyToOrdered('transaction-type');
      addKeyToOrdered('transaction-name');
      Array.prototype.push.apply(ordered, keys);
      var queryString = '';
      angular.forEach(ordered, function (key) {
        var value = object[key];
        // don't want to exclude values that are 0, so only exclude undefined and null
        if (value !== undefined && value !== null) {
          key = key.replace(/([a-z])([A-Z])/g, '$1-$2').toLowerCase();
          if (angular.isArray(value)) {
            angular.forEach(value, function (val) {
              if (queryString) {
                queryString += '&';
              }
              queryString += encodeURIComponent(key) + '=' + encodeURIComponent(val);
            });
          } else {
            if (queryString) {
              queryString += '&';
            }
            queryString += encodeURIComponent(key) + '=' + encodeURIComponent(value);
          }
        }
      });
      if (queryString) {
        return '?' + queryString;
      } else {
        return '';
      }
    }

    return {
      encodeObject: encodeObject
    };
  }
]);
