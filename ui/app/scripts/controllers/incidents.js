/*
 * Copyright 2017-2019 the original author or authors.
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

glowroot.controller('IncidentsCtrl', [
  '$scope',
  '$http',
  '$timeout',
  'queryStrings',
  'httpErrors',
  function ($scope, $http, $timeout, queryStrings, httpErrors) {
    // \u00b7 is &middot;
    document.title = 'Incidents \u00b7 Glowroot';
    $scope.$parent.activeNavbarItem = 'incident';

    function setHref(incident, resolved) {
      var query = {};
      if ($scope.layout.central) {
        if ($scope.isRollup(incident.agentRollupId)) {
          query.agentRollupId = incident.agentRollupId;
        } else {
          query.agentId = incident.agentRollupId;
        }
      }
      var halfHourInMillis = 1800000;
      var fiveMinutesInMillis = 300000;
      var fromTo = {};
      if (resolved) {
        fromTo.from = incident.openTime;
        fromTo.to = incident.resolveTime;
        if (incident.conditionType === 'metric' || incident.conditionType === 'heartbeat') {
          fromTo.from -= incident.timePeriodSeconds * 1000;
        } else if (incident.conditionType === 'synthetic-monitor') {
          fromTo.from -= incident.thresholdMillis;
        }
        // extra data for context
        fromTo.from -= fiveMinutesInMillis;
        fromTo.to += fiveMinutesInMillis;
        // rounding to nearest half hour instead of nearest hour so don't need to worry about timezone offset
        fromTo.from = Math.floor(fromTo.from / halfHourInMillis) * halfHourInMillis;
        fromTo.to = Math.ceil(fromTo.to / halfHourInMillis) * halfHourInMillis;
      } else {
        fromTo.last = new Date().getTime() - incident.openTime;
        if (incident.conditionType === 'metric' || incident.conditionType === 'heartbeat') {
          fromTo.last += incident.timePeriodSeconds * 1000;
        } else if (incident.conditionType === 'synthetic-monitor') {
          fromTo.last += incident.thresholdMillis;
        }
        // extra data for context
        fromTo.last += fiveMinutesInMillis;
        // rounding to nearest half hour instead of nearest hour so don't need to worry about timezone offset
        fromTo.last = Math.ceil(fromTo.last / halfHourInMillis) * halfHourInMillis;
      }
      if (incident.conditionType === 'metric') {
        var metric = incident.metric;
        if (metric === 'transaction:x-percentile') {
          query.transactionType = incident.transactionType;
          if (incident.transactionName) {
            query.transactionName = incident.transactionName;
          }
          angular.extend(query, fromTo);
          query.percentile = incident.percentile;
          incident.href = '/transaction/percentiles' + queryStrings.encodeObject(query);
        } else if (metric === 'transaction:average') {
          query.transactionType = incident.transactionType;
          if (incident.transactionName) {
            query.transactionName = incident.transactionName;
          }
          angular.extend(query, fromTo);
          incident.href = '/transaction/average' + queryStrings.encodeObject(query);
        } else if (metric === 'transaction:count') {
          query.transactionType = incident.transactionType;
          if (incident.transactionName) {
            query.transactionName = incident.transactionName;
          }
          angular.extend(query, fromTo);
          incident.href = '/transaction/throughput' + queryStrings.encodeObject(query);
        } else if (metric === 'error:rate') {
          query.transactionType = incident.transactionType;
          if (incident.transactionName) {
            query.transactionName = incident.transactionName;
          }
          angular.extend(query, fromTo);
          query['summary-sort-order'] = 'error-rate';
          incident.href = '/error/messages' + queryStrings.encodeObject(query);
        } else if (metric === 'error:count') {
          query.transactionType = incident.transactionType;
          if (incident.transactionName) {
            query.transactionName = incident.transactionName;
          }
          angular.extend(query, fromTo);
          incident.href = '/error/messages' + queryStrings.encodeObject(query);
        } else if (metric.lastIndexOf('gauge:', 0) === 0) {
          query.gaugeName = metric.substring('gauge:'.length);
          angular.extend(query, fromTo);
          incident.href = '/jvm/gauges' + queryStrings.encodeObject(query);
        }
      } else if (incident.conditionType === 'synthetic-monitor') {
        query.syntheticMonitorId = incident.syntheticMonitorId;
        angular.extend(query, fromTo);
        incident.href = '/synthetic-monitors' + queryStrings.encodeObject(query);
      } else if (incident.conditionType === 'heartbeat') {
        angular.extend(query, fromTo);
        incident.href = '/jvm/gauges'  + queryStrings.encodeObject(query);
      }
    }

    function setHrefs() {
      angular.forEach($scope.openIncidents, function (openIncident) {
        setHref(openIncident, false);
      });
      angular.forEach($scope.resolvedIncidents, function (resolvedIncident) {
        setHref(resolvedIncident, true);
      });
    }

    function refresh(autoRefresh) {
      var url = 'backend/incidents';
      if (autoRefresh) {
        url += '?auto-refresh=true';
      }
      $http.get(url)
          .then(function (response) {
            $scope.loaded = true;
            $scope.openIncidents = response.data.openIncidents;
            $scope.resolvedIncidents = response.data.resolvedIncidents;
            setHrefs();
          }, function (response) {
            httpErrors.handle(response);
          });
    }

    refresh(false);

    var timer;

    function onVisible() {
      $scope.$apply(function () {
        // intentionally not marking this autoRefresh
        refresh(false);
      });
      document.removeEventListener('visibilitychange', onVisible);
    }

    function scheduleNextRefresh() {
      timer = $timeout(function () {
        if (document.hidden) {
          document.addEventListener('visibilitychange', onVisible);
        } else {
          refresh(true);
        }
        scheduleNextRefresh();
      }, 30000);
    }

    scheduleNextRefresh();

    $scope.$on('$destroy', function () {
      $timeout.cancel(timer);
    });
  }
]);
