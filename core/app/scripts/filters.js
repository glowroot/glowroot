/*
 * Copyright 2013-2015 the original author or authors.
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

/* global glowroot, moment */

glowroot.filter('gtBytes', function () {
  return function (bytes, precision) {
    if (isNaN(parseFloat(bytes)) || !isFinite(bytes)) {
      return '-';
    }
    if (bytes === 0) {
      // no unit needed
      return '0';
    }
    if (typeof precision === 'undefined') {
      precision = 1;
    }
    var units = ['bytes', 'KB', 'MB', 'GB', 'TB', 'PB'];
    var number = Math.floor(Math.log(bytes) / Math.log(1024));
    return (bytes / Math.pow(1024, Math.floor(number))).toFixed(precision) + ' ' + units[number];
  };
});

glowroot.filter('gtNumber', function () {
  return function (number, precision) {
    if (isNaN(parseFloat(number)) || !isFinite(number)) {
      return '-';
    }
    if (typeof precision === 'undefined') {
      precision = 1;
    }
    return number.toFixed(precision);
  };
});

glowroot.filter('gtDuration', function () {
  return function (input) {
    if (input === undefined) {
      return '';
    }
    var duration = moment.duration(input);
    var parts = [];
    if (duration.days() > 1) {
      parts.push(Math.floor(duration.days()) + 'd');
    }
    if (duration.hours() > 1) {
      parts.push(Math.floor(duration.hours()) + 'h');
    }
    if (duration.minutes() > 1) {
      parts.push(Math.floor(duration.minutes()) + 'm');
    }
    if (duration.seconds() > 1) {
      parts.push(Math.floor(duration.seconds()) + 's');
    }
    return parts.join(' ');
  };
});

glowroot.filter('gtOnOff', function () {
  return function (input) {
    return input ? 'ON' : 'OFF';
  };
});

glowroot.filter('gtTypeaheadClassHighlight', function () {

  function escapeRegexp(queryToEscape) {
    return queryToEscape.replace(/([.?*+^$[\]\\(){}|-])/g, '\\$1');
  }

  return function (matchItem, query) {
    if (!query) {
      return matchItem;
    }
    matchItem = matchItem.replace(new RegExp('^' + escapeRegexp(query), 'gi'), '<strong>$&</strong>');
    matchItem = matchItem.replace(new RegExp('\\.(' + escapeRegexp(query) + ')', 'gi'), '.<strong>$1</strong>');
    matchItem = matchItem.replace(new RegExp('\\$(' + escapeRegexp(query) + ')', 'gi'), '$<strong>$1</strong>');
    return matchItem;
  };
});
