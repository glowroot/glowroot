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

/* global glowroot, angular, $ */

glowroot.controller('JvmHeapHistogramCtrl', [
  '$scope',
  '$http',
  '$q',
  '$timeout',
  'httpErrors',
  function ($scope, $http, $q, $timeout, httpErrors) {
    var sortAttribute = 'bytes';
    var sortDesc = true;

    // this is used to calculate bar width under class name representing the proportion of total bytes
    var maxBytes;

    $scope.classNameBarWidth = function (bytes) {
      return (bytes / maxBytes) * 100 + '%';
    };

    function sort() {
      if (sortAttribute === 'className') {
        // text sort
        $scope.histogram.items.sort(function (item1, item2) {
          var s1 = item1.className.toLowerCase();
          var s2 = item2.className.toLowerCase();
          var compare = s1 < s2 ? -1 : s1 > s2 ? 1 : 0;
          return sortDesc ? -compare : compare;
        });
      } else {
        // numeric sort
        $scope.histogram.items.sort(function (item1, item2) {
          var compare = item1[sortAttribute] - item2[sortAttribute];
          return sortDesc ? -compare : compare;
        });
      }
    }

    $scope.sortOn = function (attributeName) {
      if (sortAttribute === attributeName) {
        sortDesc = !sortDesc;
      } else {
        sortAttribute = attributeName;
        sortDesc = true;
      }
      sort();
      applyFilter();
    };

    $scope.refresh = function (deferred) {
      $http.get('backend/jvm/heap-histogram')
          .success(function (data) {
            $scope.loaded = true;
            $scope.histogram = data;
            if (sortAttribute !== 'bytes' || !sortDesc) {
              // data needs to be sorted
              sort();
            }
            applyFilter();
            if (deferred) {
              deferred.resolve('Complete');
            }
          })
          .error(httpErrors.handler($scope, deferred));
    };

    $scope.exportAsCsv = function (deferred) {
      var csv = 'Class name,Bytes,Count<br>';
      angular.forEach($scope.histogram.items, function (item) {
        csv += item.className + ',' + item.bytes + ',' + item.count + '<br>';
      });
      var csvWindow = window.open();
      $(csvWindow.document.body).html(csv);
      deferred.resolve();
    };

    $scope.filterComparatorOptions = [
      {
        display: 'Contains',
        value: 'contains'
      },
      {
        display: 'Begins with',
        value: 'begins'
      },
      {
        display: 'Ends with',
        value: 'ends'
      }
    ];
    $scope.filterComparator = 'contains';
    $scope.filterValue = '';
    $scope.filterLimit = 200;

    function applyFilter() {
      if ($scope.displayedItems === undefined) {
        $scope.displayedItems = [];
      }
      $scope.displayedItems.length = 0;
      $scope.limitApplied = false;
      $scope.filteredTotalBytes = 0;
      $scope.filteredTotalCount = 0;
      if ($scope.filterValue === '') {
        // optimization
        $scope.displayedItems = $scope.histogram.items.slice(0, $scope.filterLimit);
        $scope.limitApplied = ($scope.histogram.items.length > $scope.filterLimit);
        $scope.filteredTotalBytes = $scope.histogram.totalBytes;
        $scope.filteredTotalCount = $scope.histogram.totalCount;
        maxBytes = 0;
        angular.forEach($scope.displayedItems, function (item) {
          maxBytes = Math.max(maxBytes, item.bytes);
        });
        return;
      }
      var items = $scope.histogram.items;
      maxBytes = 0;
      for (var i = 0; i < items.length; i++) {
        var item = items[i];
        if (matchesFilter(item.className)) {
          if ($scope.displayedItems.length < $scope.filterLimit) {
            $scope.displayedItems.push(item);
            maxBytes = Math.max(maxBytes, item.bytes);
          } else {
            $scope.limitApplied = true;
          }
          $scope.filteredTotalBytes += item.bytes;
          $scope.filteredTotalCount += item.count;
        }
      }
    }

    function matchesFilter(className) {
      if ($scope.filterComparator === 'begins') {
        return className.indexOf($scope.filterValue) === 0;
      }
      if ($scope.filterComparator === 'ends') {
        return className.indexOf($scope.filterValue, className.length - $scope.filterValue.length) !== -1;
      }
      // contains
      return className.indexOf($scope.filterValue) !== -1;
    }

    $scope.$watch('filterComparator', function (newValue) {
      if ($scope.displayedItems === undefined) {
        // histogram hasn't loaded yet
        return;
      }
      applyFilter();
    });

    $scope.$watch('filterValue', function (newValue) {
      if ($scope.displayedItems === undefined) {
        // histogram hasn't loaded yet
        return;
      }
      applyFilter();
    });

    $scope.$watch('filterLimit', function (newValue) {
      if ($scope.displayedItems === undefined) {
        // histogram hasn't loaded yet
        return;
      }
      applyFilter();
    });

    $scope.refresh();
  }
]);
