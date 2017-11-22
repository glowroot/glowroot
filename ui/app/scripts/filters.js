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

/* global glowroot, moment, HandlebarsRendering */

glowroot.filter('gtBytes', function () {
  return HandlebarsRendering.formatBytes;
});

glowroot.filter('gtMillis', function () {
  return HandlebarsRendering.formatMillis;
});

glowroot.filter('gtCount', function () {
  return HandlebarsRendering.formatCount;
});

glowroot.filter('gtDuration', function () {
  return function (input) {
    if (input === undefined) {
      return '';
    }
    var duration = moment.duration(input);
    var parts = [];
    var days = duration.asDays();
    if (days >= 1) {
      parts.push(Math.floor(days) + 'd');
    }
    var hours = duration.hours();
    if (parts.length || hours >= 1) {
      parts.push(Math.floor(hours) + 'h');
    }
    var minutes = duration.minutes();
    if (parts.length || minutes >= 1) {
      parts.push(Math.floor(minutes) + 'm');
    }
    var seconds = duration.seconds();
    if (parts.length || seconds >= 1) {
      parts.push(Math.floor(seconds) + 's');
    }
    if (!parts.length) {
      return input + 'ms';
    }
    return parts.join(' ');
  };
});

glowroot.filter('gtGaugeValue', function () {
  return function (value) {
    var nonScaledValue;
    if (value < 1000000) {
      nonScaledValue = parseFloat(value.toPrecision(6));
    } else {
      nonScaledValue = Math.round(value);
    }
    return nonScaledValue.toLocaleString(undefined, {maximumFractionDigits: 20});
  };
});

glowroot.filter('gtOnOff', function () {
  return function (input) {
    return input ? 'ON' : 'OFF';
  };
});

glowroot.filter('gtTypeaheadClassHighlight', [
  '$sce',
  function ($sce) {
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
      return $sce.trustAsHtml(matchItem);
    };
  }
]);
