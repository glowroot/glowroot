/*
 * Copyright 2016 the original author or authors.
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

glowroot.factory('gauges', [
  function () {
    function createShortDataSeriesNames(gauges) {
      var splitGaugeNames = [];
      angular.forEach(gauges, function (gauge) {
        splitGaugeNames.push(gauge.display.split('/'));
      });
      var minRequiredForUniqueName;
      var i, j;
      for (i = 0; i < gauges.length; i++) {
        var splitGaugeName = splitGaugeNames[i];
        var gaugeName = gauges[i].name;
        var separator = gaugeName.lastIndexOf(':');
        // at least include the last step in the mbean object name
        minRequiredForUniqueName = gaugeName.substring(separator + 1).split('/').length + 1;
        for (j = 0; j < gauges.length; j++) {
          if (j === i) {
            continue;
          }
          var splitGaugeName2 = splitGaugeNames[j];
          minRequiredForUniqueName = Math.max(minRequiredForUniqueName,
              numSamePartsStartingAtEnd(splitGaugeName, splitGaugeName2) + 1);
        }
        gauges[i].shortDisplay = splitGaugeName.slice(-minRequiredForUniqueName).join('/');
      }
    }

    function numSamePartsStartingAtEnd(array1, array2) {
      var k = 0;
      var len1 = array1.length;
      var len2 = array2.length;
      while (k < Math.min(len1, len2) && array1[len1 - 1 - k] === array2[len2 - 1 - k]) {
        k++;
      }
      return k;
    }

    function unit(gaugeName, leadingSpace) {
      // TODO units should be configurable per gauge config
      var text = '';
      if (gaugeName.match(/java.lang:type=Memory:(Non)?HeapMemoryUsage\/(init|used|committed|max)/)) {
        text = 'bytes';
      }
      if (gaugeName.match(/java.lang:type=OperatingSystem:(Free|Total)(Physical|Swap)MemorySize/)) {
        text = 'bytes';
      }
      if (gaugeName.match(/java.lang:type=Runtime:Uptime/)) {
        text = 'milliseconds';
      }
      if (gaugeName.match(/java.lang:type=Threading:CurrentThread(Cpu|User)Time/)) {
        text = 'nanoseconds';
      }
      if (gaugeName.match(/java.lang:type=MemoryPool,name=[a-zA-Z0-9 ]+:(Peak)?Usage\/(init|used|committed|max)/)) {
        text = 'bytes';
      }
      if (gaugeName.match(/java.lang:type=GarbageCollector,name=[a-zA-Z0-9 ]+:LastGcInfo\/duration/)) {
        text = 'milliseconds';
      }
      if (gaugeName.match(/java.lang:type=GarbageCollector,name=[a-zA-Z0-9 ]+:CollectionTime/)) {
        text = 'milliseconds';
      }
      if (gaugeName.match(/java.lang:type=Compilation:TotalCompilationTime/)) {
        text = 'milliseconds';
      }
      if (gaugeName.match(/\[counter\]$/)) {
        text += ' per second';
      }
      if (text && leadingSpace) {
        text = ' ' + text;
      }
      return text;
    }

    return {
      createShortDataSeriesNames: createShortDataSeriesNames,
      unit: unit
    };
  }
]);
