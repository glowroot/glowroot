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

/* global glowroot, HandlebarsRendering, $ */

glowroot.controller('PerformanceMetricsCtrl', [
  '$scope',
  '$location',
  '$filter',
  '$http',
  '$q',
  '$timeout',
  'charts',
  'httpErrors',
  'keyedColorPools',
  'queryStrings',
  function ($scope, $location, $filter, $http, $q, $timeout, charts, httpErrors, keyedColorPools, queryStrings) {
    // \u00b7 is &middot;
    document.title = 'Performance \u00b7 Glowroot';
    $scope.$parent.activeNavbarItem = 'performance';

    var chartState = {
      fixedAggregateIntervalMillis: $scope.layout.fixedAggregateIntervalSeconds * 1000,
      plot: undefined,
      currentRefreshId: 0,
      currentZoomId: 0,
      keyedColorPool: keyedColorPools.create(),
      chartFromToDefault: false,
      refreshData: refreshData
    };

    $scope.showTableOverlay = 0;

    $scope.transactionsQueryString = function () {
      var query = {
        transactionType: $scope.transactionType,
        from: $scope.chartFrom,
        to: $scope.chartTo
      };
      return queryStrings.encodeObject(query);
    };

    $scope.changeTransactionType = function (transactionType) {
      if (transactionType !== $scope.transactionType) {
        $scope.transactionType = transactionType;
        refreshData();
      }
    };

    $scope.tracesQueryString = function () {
      var query = {
        transactionType: $scope.transactionType,
        transactionName: $scope.transactionName,
        transactionNameComparator: 'equals',
        from: $scope.chartFrom,
        to: $scope.chartTo
      };
      return queryStrings.encodeObject(query);
    };

    function refreshData() {
      updateLocation();
      var query = {
        from: $scope.chartFrom,
        to: $scope.chartTo,
        transactionType: $scope.transactionType,
        transactionName: $scope.transactionName
      };
      charts.refreshData('backend/performance/metrics', query, chartState, $scope, updateMergedAggregate);
    }

    function updateLocation() {
      var query = {
        'transaction-type': $scope.transactionType,
        'transaction-name': $scope.transactionName
      };
      if (!chartState.chartFromToDefault) {
        query.from = $scope.chartFrom - chartState.fixedAggregateIntervalMillis;
        query.to = $scope.chartTo;
      }
      $location.search(query).replace();
    }

    $scope.refreshButtonClick = function () {
      charts.refreshButtonClick(chartState, $scope);
    };

    $scope.$watch('filterDate', function (newValue, oldValue) {
      if (newValue && newValue !== oldValue) {
        $scope.refreshButtonClick();
      }
    });

    function updateMergedAggregate(data) {
      $scope.mergedAggregate = data.mergedAggregate;
      if ($scope.mergedAggregate.count) {
        updateTreeMetrics();
        updateFlattenedMetrics();
        var $profileOuter = $('#profileOuter');
        if (!$profileOuter.hasClass('hide')) {
          $profileOuter.addClass('hide');
          $profileOuter.data('loaded', false);
        }
        var $profileFilter = $profileOuter.find('.profile-filter');
        var $profile = $profileOuter.find('.profile');
        $profileFilter.html('');
        $profile.html('');
      } else {
        $('#detail').html('No data');
      }
      $scope.traceCount = data.traceCount;
    }

    function updateTreeMetrics() {
      var treeMetrics = [];

      function traverse(metric, nestingLevel) {
        metric.nestingLevel = nestingLevel;
        treeMetrics.push(metric);
        if (metric.nestedMetrics) {
          metric.nestedMetrics.sort(function (a, b) {
            return b.totalMicros - a.totalMicros;
          });
          $.each(metric.nestedMetrics, function (index, nestedMetric) {
            traverse(nestedMetric, nestingLevel + 1);
          });
        }
      }

      traverse($scope.mergedAggregate.metrics, 0);

      $scope.treeMetrics = treeMetrics;
    }

    function updateFlattenedMetrics() {
      var flattenedMetricMap = {};
      var flattenedMetrics = [];

      function traverse(metric, parentMetricNames) {
        var flattenedMetric = flattenedMetricMap[metric.name];
        if (!flattenedMetric) {
          flattenedMetric = {
            name: metric.name,
            totalMicros: metric.totalMicros,
            count: metric.count
          };
          flattenedMetricMap[metric.name] = flattenedMetric;
          flattenedMetrics.push(flattenedMetric);
        } else if (parentMetricNames.indexOf(metric.name) === -1) {
          // only add to existing flattened metric if the aggregate metric isn't appearing under itself
          // (this is possible when they are separated by another aggregate metric)
          flattenedMetric.totalMicros += metric.totalMicros;
          flattenedMetric.count += metric.count;
        }
        if (metric.nestedMetrics) {
          $.each(metric.nestedMetrics, function (index, nestedMetric) {
            traverse(nestedMetric, parentMetricNames.concat(metric));
          });
        }
      }

      traverse($scope.mergedAggregate.metrics, []);

      flattenedMetrics.sort(function (a, b) {
        return b.totalMicros - a.totalMicros;
      });

      $scope.flattenedMetrics = flattenedMetrics;
    }

    $scope.filter = {};
    charts.initFilter(chartState, $scope);
    $scope.transactionType = $location.search()['transaction-type'] || $scope.layout.defaultTransactionType;
    $scope.transactionName = $location.search()['transaction-name'];

    $scope.toggleProfile = function (event) {
      var query = {
        from: $scope.chartFrom,
        to: $scope.chartTo,
        transactionType: $scope.transactionType,
        transactionName: $scope.transactionName,
        truncateLeafPercentage: 0.001
      };
      HandlebarsRendering.profileToggle($(event.target), '#profileOuter', null,
          'backend/performance/profile?' + queryStrings.encodeObject(query));
    };

    $scope.flameGraphQueryString = function () {
      var query = {
        from: $scope.chartFrom,
        to: $scope.chartTo,
        transactionType: $scope.transactionType,
        transactionName: $scope.transactionName
      };
      return queryStrings.encodeObject(query);
    };

    charts.initChart($('#chart'), chartState, $scope);
  }
]);
