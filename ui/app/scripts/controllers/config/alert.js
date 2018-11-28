/*
 * Copyright 2015-2018 the original author or authors.
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

glowroot.controller('ConfigAlertCtrl', [
  '$scope',
  '$location',
  '$http',
  'confirmIfHasChanges',
  'httpErrors',
  function ($scope, $location, $http, confirmIfHasChanges, httpErrors) {

    // initialize page binding object
    $scope.page = {};

    var version = $location.search().v;

    var gaugeUnits = {};

    $scope.conditionThresholdByteUnits = [
      {
        display: 'bytes',
        value: 'bytes'
      },
      {
        display: 'KB',
        value: 'KB'
      },
      {
        display: 'MB',
        value: 'MB'
      },
      {
        display: 'GB',
        value: 'GB'
      }
    ];

    $scope.metrics = angular.copy($scope.METRICS);
    $scope.metrics.push({
      id: 'gauge:select',
      display: '(select one or more agents to see available gauges)',
      disabled: true
    });

    function showTransactionTypeAndName(metric) {
      return metric && (metric.lastIndexOf('transaction:', 0) === 0 || metric.lastIndexOf('error:', 0) === 0);
    }

    function showMinTransactionCount(metric) {
      return showTransactionTypeAndName(metric) && metric !== 'transaction:count' && metric !== 'error:count';
    }

    $scope.showTransactionTypeAndName = function () {
      return showTransactionTypeAndName($scope.config.condition.metric);
    };

    $scope.showMinTransactionCount = function () {
      return showMinTransactionCount($scope.config.condition.metric);
    };

    $scope.$watch('config.condition.metric', function (newValue, oldValue) {
      if (!$scope.config) {
        return;
      }
      if (showTransactionTypeAndName(newValue)) {
        if ($scope.config.condition.transactionType === undefined) {
          $scope.config.condition.transactionType = $scope.defaultTransactionType();
        }
        if ($scope.config.condition.transactionName === undefined) {
          $scope.config.condition.transactionName = '';
        }
      } else {
        delete $scope.config.condition.transactionType;
        delete $scope.config.condition.transactionName;
      }
      if (showMinTransactionCount(newValue)) {
        if ($scope.config.condition.minTransactionCount === undefined) {
          $scope.config.condition.minTransactionCount = '';
        }
      } else {
        delete $scope.config.condition.minTransactionCount;
      }
      if (newValue !== 'transaction:x-percentile') {
        delete $scope.config.condition.percentile;
      }
      // update unit
      if (newValue === 'transaction:average' || newValue === 'transaction:x-percentile') {
        $scope.unit = 'milliseconds';
      } else if (newValue === 'error:rate') {
        $scope.unit = 'percent';
      } else if (newValue && newValue.lastIndexOf('gauge:', 0) === 0) {
        var gaugeName = newValue.substring('gauge:'.length);
        $scope.unit = gaugeUnits[gaugeName];
      } else {
        // e.g. 'transaction:count'
        $scope.unit = '';
      }
      if (oldValue) {
        // clear existing threshold
        delete $scope.config.condition.threshold;
        delete $scope.page.conditionThreshold;
      }
      if ($scope.unit === ' bytes') {
        if ($scope.page.conditionThresholdByteUnit === undefined) {
          // e.g. open new alert, then switch to HeapMemoryUsage
          $scope.page.conditionThresholdByteUnit = 'MB';
        }
      } else {
        delete $scope.page.conditionThresholdByteUnit;
      }
    });

    $scope.phraseForValue = function () {
      var metric = $scope.config.condition.metric;
      if (metric === 'transaction:average') {
        return 'average response time';
      } else if (metric === 'transaction:x-percentile') {
        return 'X\u1d57\u02b0 percentile response time';
      } else if (metric === 'transaction:count') {
        return 'transaction count';
      } else if (metric === 'error:rate') {
        return 'error rate';
      } else if (metric === 'error:count') {
        return 'error count';
      } else if (metric && metric.lastIndexOf('gauge:', 0) === 0) {
        return 'average gauge value';
      } else {
        // unexpected metric
        return 'value';
      }
    };

    function onNewData(data) {
      // need to populate notification objects before making originalConfig copy
      if (!data.config.emailNotification) {
        data.config.emailNotification = {
          emailAddresses: []
        };
      }
      if (!data.config.pagerDutyNotification) {
        data.config.pagerDutyNotification = {
          pagerDutyIntegrationKey: ''
        };
      }
      if (!data.config.slackNotification) {
        data.config.slackNotification = {
          slackWebhookId: '',
          slackChannels: []
        };
      }
      $scope.config = data.config;
      $scope.originalConfig = angular.copy(data.config);

      if (data.heading) {
        if (data.config.condition.timePeriodSeconds === undefined) {
          delete $scope.page.timePeriodMinutes;
        } else {
          $scope.page.timePeriodMinutes = data.config.condition.timePeriodSeconds / 60;
        }
        $scope.heading = data.heading;
        $scope.page.emailAddresses = data.config.emailNotification.emailAddresses.join(', ');
        $scope.page.slackChannels = data.config.slackNotification.slackChannels.join(', ');
      } else {
        $scope.heading = '<New>';
      }
      $scope.metrics = angular.copy($scope.METRICS);
      gaugeUnits = {};
      angular.forEach(data.gauges, function (gauge) {
        $scope.metrics.push({
          id: 'gauge:' + gauge.name,
          display: gauge.display
        });
        if (gauge.unit) {
          gaugeUnits[gauge.name] = ' ' + gauge.unit;
        } else {
          gaugeUnits[gauge.name] = '';
        }
      });
      $scope.page.conditionThreshold = $scope.config.condition.threshold;
      delete $scope.page.conditionThresholdByteUnit;
      if ($scope.config.condition.conditionType === 'metric'
          && $scope.config.condition.metric.lastIndexOf('gauge:', 0) === 0) {
        var gaugeName = $scope.config.condition.metric.substring('gauge:'.length);
        var gaugeUnit = gaugeUnits[gaugeName];
        if (gaugeUnit === undefined) {
          $scope.metrics.push({
            id: '-empty9-',
            display: '',
            disabled: true
          });
          $scope.metrics.push({
            id: 'gauge:' + gaugeName,
            display: gaugeName + ' (not available)',
            disabled: true
          });
        } else if (gaugeUnit === ' bytes') {
          var threshold = $scope.config.condition.threshold;
          if (threshold === 0) {
            $scope.page.conditionThreshold = threshold;
            $scope.page.conditionThresholdByteUnit = 'bytes';
          } else if (threshold % (1024 * 1024 * 1024) === 0) {
            $scope.page.conditionThreshold = threshold / (1024 * 1024 * 1024);
            $scope.page.conditionThresholdByteUnit = 'GB';
          } else if (threshold % (1024 * 1024) === 0) {
            $scope.page.conditionThreshold = threshold / (1024 * 1024);
            $scope.page.conditionThresholdByteUnit = 'MB';
          } else if (threshold % 1024 === 0) {
            $scope.page.conditionThreshold = threshold / 1024;
            $scope.page.conditionThresholdByteUnit = 'KB';
          } else {
            $scope.page.conditionThreshold = threshold;
            $scope.page.conditionThresholdByteUnit = 'bytes';
          }
        }
      }
      $scope.syntheticMonitors = data.syntheticMonitors;
      $scope.pagerDutyIntegrationKeys = data.pagerDutyIntegrationKeys;
      $scope.slackWebhooks = data.slackWebhooks;
    }

    if (version) {
      $http.get('backend/config/alerts?agent-rollup-id=' + encodeURIComponent($scope.agentRollupId) + '&version=' + version)
          .then(function (response) {
            onNewData(response.data);
            $scope.loaded = true;
          }, function (response) {
            httpErrors.handle(response, $scope);
          });
    } else {
      var url = 'backend/config/alert-dropdowns?agent-rollup-id=' + encodeURIComponent($scope.agentRollupId)
          + '&version=' + version;
      $http.get(url)
          .then(function (response) {
            onNewData({
              config: {
                condition: {
                  conditionType: 'metric',
                  metric: ''
                }
              },
              gauges: response.data.gauges,
              syntheticMonitors: response.data.syntheticMonitors,
              pagerDutyIntegrationKeys: response.data.pagerDutyIntegrationKeys,
              slackWebhooks: response.data.slackWebhooks
            });
            $scope.loaded = true;
          }, function (response) {
            httpErrors.handle(response, $scope);
          });
    }

    $scope.$watchGroup(['page.conditionThreshold', 'page.conditionThresholdByteUnit'], function () {
      if ($scope.page.conditionThresholdByteUnit === 'KB') {
        $scope.config.condition.threshold = $scope.page.conditionThreshold * 1024;
      } else if ($scope.page.conditionThresholdByteUnit === 'MB') {
        $scope.config.condition.threshold = $scope.page.conditionThreshold * 1024 * 1024;
      } else if ($scope.page.conditionThresholdByteUnit === 'GB') {
        $scope.config.condition.threshold = $scope.page.conditionThreshold * 1024 * 1024 * 1024;
      } else if ($scope.config) {
        $scope.config.condition.threshold = $scope.page.conditionThreshold;
      }
    });

    $scope.$watch('config.condition.conditionType', function (newValue, oldValue) {
      if (!$scope.config) {
        return;
      }
      if (oldValue === undefined) {
        return;
      }
      $scope.config.condition = {
        conditionType: newValue
      };
      delete $scope.page.timePeriodMinutes;
      if ($scope.showTransactionTypeAndName()) {
        $scope.config.condition.transactionType = $scope.defaultTransactionType();
      }
    });

    $scope.$watch('page.timePeriodMinutes', function (newValue) {
      if (!$scope.config) {
        return;
      }
      if (newValue === undefined) {
        delete $scope.config.condition.timePeriodSeconds;
      } else {
        $scope.config.condition.timePeriodSeconds = newValue * 60;
      }
    });

    $scope.$watch('page.emailAddresses', function (newValue) {
      if (newValue) {
        var emailAddresses = [];
        angular.forEach(newValue.split(','), function (emailAddress) {
          emailAddress = emailAddress.trim();
          if (emailAddress.length) {
            emailAddresses.push(emailAddress);
          }
        });
        $scope.config.emailNotification.emailAddresses = emailAddresses;
      } else if ($scope.config) {
        $scope.config.emailNotification.emailAddresses = [];
      }
    });

    $scope.$watch('page.slackChannels', function (newValue) {
      if (newValue) {
        var slackChannels = [];
        angular.forEach(newValue.split(','), function (slackChannel) {
          slackChannel = slackChannel.trim();
          if (slackChannel.length) {
            slackChannels.push(slackChannel);
          }
        });
        $scope.config.slackNotification.slackChannels = slackChannels;
      } else if ($scope.config) {
        $scope.config.slackNotification.slackChannels = [];
      }
    });

    $scope.$watch('config.slackNotification.slackWebhookId', function (newValue) {
      if (!newValue) {
        $scope.page.slackChannels = '';
      }
    });

    $scope.displayUnavailablePagerDutyIntegrationKey = function () {
      if (!$scope.config.pagerDutyNotification.pagerDutyIntegrationKey) {
        return false;
      }
      var i;
      for (i = 0; i < $scope.pagerDutyIntegrationKeys.length; i++) {
        if ($scope.pagerDutyIntegrationKeys[i].key === $scope.config.pagerDutyNotification.pagerDutyIntegrationKey) {
          return false;
        }
      }
      return true;
    };

    $scope.displayUnavailableSlackWebhook = function () {
      if (!$scope.config.slackNotification.slackWebhookId) {
        return false;
      }
      var i;
      for (i = 0; i < $scope.slackWebhooks.length; i++) {
        if ($scope.slackWebhooks[i].id === $scope.config.slackNotification.slackWebhookId) {
          return false;
        }
      }
      return true;
    };

    $scope.hasChanges = function () {
      return !angular.equals($scope.config, $scope.originalConfig);
    };
    var removeConfirmIfHasChangesListener = $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    $scope.save = function (deferred) {
      var postData = angular.copy($scope.config);
      if (!postData.emailNotification.emailAddresses.length) {
        delete postData.emailNotification;
      }
      if (!postData.pagerDutyNotification.pagerDutyIntegrationKey) {
        delete postData.pagerDutyNotification;
      }
      var url;
      if (version) {
        url = 'backend/config/alerts/update';
      } else {
        url = 'backend/config/alerts/add';
      }
      var agentId = $scope.agentId;
      var agentRollupId = $scope.agentRollupId;
      $http.post(url + '?agent-rollup-id=' + encodeURIComponent(agentRollupId), postData)
          .then(function (response) {
            onNewData(response.data);
            deferred.resolve(version ? 'Saved' : 'Added');
            version = response.data.config.version;
            // fix current url (with updated version) before returning to list page in case back button is used later
            if (agentId) {
              $location.search({'agent-id': agentId, v: version}).replace();
            } else if (agentRollupId) {
              $location.search({'agent-rollup-id': agentRollupId, v: version}).replace();
            } else {
              $location.search({v: version}).replace();
            }
          }, function (response) {
            httpErrors.handle(response, $scope, deferred);
          });
    };

    $scope.delete = function (deferred) {
      var postData = {
        version: $scope.config.version
      };
      var agentId = $scope.agentId;
      var agentRollupId = $scope.agentRollupId;
      $http.post('backend/config/alerts/remove?agent-rollup-id=' + encodeURIComponent(agentRollupId), postData)
          .then(function () {
            removeConfirmIfHasChangesListener();
            if (agentId) {
              $location.url('config/alert-list?agent-id=' + encodeURIComponent(agentId)).replace();
            } else if (agentRollupId) {
              $location.url('config/alert-list?agent-rollup-id=' + encodeURIComponent(agentRollupId)).replace();
            } else {
              $location.url('config/alert-list').replace();
            }
          }, function (response) {
            httpErrors.handle(response, $scope, deferred);
          });
    };
  }
]);
