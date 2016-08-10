/*
 * Copyright 2012-2016 the original author or authors.
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

glowroot.config([
  '$provide',
  '$stateProvider',
  '$urlRouterProvider',
  function ($provide, $stateProvider, $urlRouterProvider) {
    var waitForLayout = function (needsTransactionType) {
      return ['$q', '$rootScope', '$location', function ($q, $rootScope, $location) {
        if (window.layout) {
          if ($location.path() === '/login') {
            // no need to add transaction-type to url
            return;
          }
          var hasAgent = $location.search()['agent-id'] || $location.search()['agent-rollup'] || $rootScope.layout.fat;
          if (hasAgent && needsTransactionType && !$location.search()['transaction-type']) {
            $location.search('transaction-type', $rootScope.defaultTransactionType());
            $location.replace();
          }
          return function () {
          };
        } else {
          var deferred = $q.defer();
          var unregisterWatch = $rootScope.$watch('layout', function (value) {
            if (!value) {
              return;
            }
            if ($location.path() === '/login') {
              // no need to add transaction-type to url
              deferred.resolve();
              unregisterWatch();
              return;
            }
            var hasAgent = $location.search()['agent-id'] || $location.search()['agent-rollup']
                || $rootScope.layout.fat;
            if (hasAgent && needsTransactionType && !$location.search()['transaction-type']) {
              $location.search('transaction-type', $rootScope.defaultTransactionType());
              $location.replace();
            }
            deferred.resolve();
            unregisterWatch();
          });
          return deferred.promise;
        }
      }];
    };
    $urlRouterProvider.otherwise(function () {
      return 'transaction/average';
    });
    $stateProvider.state('transaction', {
      abstract: true,
      url: '/transaction?agent-id&agent-rollup&transaction-type',
      templateUrl: 'views/transaction.html',
      controller: 'TransactionCtrl',
      resolve: {
        headerDisplay: function () {
          return 'Transactions';
        },
        shortName: function () {
          return 'transaction';
        },
        defaultSummarySortOrder: function () {
          return 'total-time';
        },
        waitForLayout: waitForLayout(true)
      }
    });
    $stateProvider.state('transaction.detail', {
      abstract: true,
      views: {
        sidebar: {
          templateUrl: 'views/transaction/sidebar.html',
          controller: 'TransactionSidebarCtrl',
          resolve: {
            summarySortOrders: function () {
              return {
                'total-time': 'By percent of total time',
                'average-time': 'By average time',
                'throughput': 'By throughput (per min)'
              };
            },
            summaryValueFn: [
              '$filter',
              function ($filter) {
                return function (summary, sortOrder, overallSummary, durationMillis) {
                  if (sortOrder === 'total-time') {
                    return (100 * summary.totalDurationNanos / overallSummary.totalDurationNanos).toFixed(1) + ' %';
                  } else if (sortOrder === 'average-time') {
                    return $filter('gtMillis')(summary.totalDurationNanos / (1000000 * summary.transactionCount)) + ' ms';
                  } else if (sortOrder === 'throughput') {
                    return (60 * 1000 * summary.transactionCount / durationMillis).toFixed(1) + '/min';
                  }
                };
              }
            ]
          }
        },
        tabs: {
          templateUrl: 'views/transaction/tabs.html',
          controller: 'TransactionTabCtrl'
        }
      }
    });
    $stateProvider.state('transaction.detail.average', {
      url: '/average?agent-id&transaction-type&transaction-name',
      views: {
        'main@transaction': {
          templateUrl: 'views/transaction/average.html',
          controller: 'TransactionAverageCtrl'
        }
      }
    });
    $stateProvider.state('transaction.detail.percentiles', {
      url: '/percentiles?agent-id&transaction-type&transaction-name',
      views: {
        'main@transaction': {
          templateUrl: 'views/transaction/percentiles.html',
          controller: 'TransactionPercentilesCtrl'
        }
      }
    });
    $stateProvider.state('transaction.detail.throughput', {
      url: '/throughput?agent-id&transaction-type&transaction-name',
      views: {
        'main@transaction': {
          templateUrl: 'views/transaction/throughput.html',
          controller: 'TransactionThroughputCtrl'
        }
      }
    });
    $stateProvider.state('transaction.detail.traces', {
      url: '/traces?agent-id&transaction-type&transaction-name',
      views: {
        'main@transaction': {
          templateUrl: 'views/transaction/traces.html',
          controller: 'TracesCtrl',
          resolve: {
            traceKind: function () {
              return 'transaction';
            }
          }
        }
      }
    });
    $stateProvider.state('transaction.detail.queries', {
      url: '/queries?agent-id&transaction-type&transaction-name',
      views: {
        'main@transaction': {
          templateUrl: 'views/transaction/queries.html',
          controller: 'TransactionQueriesCtrl'
        }
      }
    });
    $stateProvider.state('transaction.detail.services', {
      url: '/services?agent-id&transaction-type&transaction-name',
      views: {
        'main@transaction': {
          templateUrl: 'views/transaction/services.html',
          controller: 'TransactionServicesCtrl'
        }
      }
    });
    $stateProvider.state('transaction.detail.threadProfile', {
      url: '/thread-profile?agent-id&transaction-type&transaction-name',
      views: {
        'main@transaction': {
          templateUrl: 'views/transaction/profile.html',
          controller: 'TransactionProfileCtrl'
        }
      }
    });
    var waitForD3 = ['$q', '$timeout', function ($q, $timeout) {
      var deferred = $q.defer();

      function checkForD3() {
        if (window.d3) {
          deferred.resolve();
        } else {
          $timeout(checkForD3, 100);
        }
      }

      $timeout(checkForD3, 100);
      return deferred.promise;
    }];
    $stateProvider.state('transaction-thread-flame-graph', {
      url: '/transaction/thread-flame-graph',
      templateUrl: 'views/transaction/flame-graph.html',
      controller: 'TransactionFlameGraphCtrl',
      resolve: {
        waitForD3: waitForD3,
        waitForLayout: waitForLayout(true)
      }
    });
    $stateProvider.state('error', {
      abstract: true,
      url: '/error?agent-id&agent-rollup&transaction-type',
      templateUrl: 'views/transaction.html',
      controller: 'TransactionCtrl',
      resolve: {
        headerDisplay: function () {
          return 'Errors';
        },
        shortName: function () {
          return 'error';
        },
        defaultSummarySortOrder: function () {
          return 'error-count';
        },
        waitForLayout: waitForLayout(true)
      }
    });
    $stateProvider.state('error.detail', {
      abstract: true,
      views: {
        sidebar: {
          templateUrl: 'views/transaction/sidebar.html',
          controller: 'TransactionSidebarCtrl',
          resolve: {
            summarySortOrders: function () {
              return {
                'error-count': 'By error count',
                'error-rate': 'By error rate'
              };
            },
            summaryValueFn: function () {
              return function (summary, sortOrder) {
                if (sortOrder === 'error-count') {
                  return summary.errorCount;
                } else if (sortOrder === 'error-rate') {
                  return (100 * summary.errorCount / summary.transactionCount).toFixed(1) + ' %';
                }
              };
            }
          }
        },
        tabs: {
          // same controller, just different html
          templateUrl: 'views/transaction/error-tabs.html',
          controller: 'TransactionTabCtrl'
        }
      }
    });
    $stateProvider.state('error.detail.messages', {
      url: '/messages?agent-id&transaction-type&transaction-name',
      views: {
        'main@error': {
          templateUrl: 'views/transaction/error-messages.html',
          controller: 'ErrorMessagesCtrl'
        }
      }
    });
    $stateProvider.state('error.detail.traces', {
      url: '/traces?agent-id&transaction-type&transaction-name',
      views: {
        'main@error': {
          templateUrl: 'views/transaction/traces.html',
          controller: 'TracesCtrl',
          resolve: {
            traceKind: function () {
              return 'error';
            }
          }
        }
      }
    });
    $stateProvider.state('jvm', {
      url: '/jvm',
      templateUrl: 'views/jvm.html',
      controller: 'JvmCtrl',
      resolve: {
        waitForLayout: waitForLayout(false)
      }
    });
    $stateProvider.state('jvm.gauges', {
      url: '/gauges?agent-id&agent-rollup',
      templateUrl: 'views/jvm/gauge-values.html',
      controller: 'JvmGaugeValuesCtrl'
    });
    $stateProvider.state('jvm.environment', {
      url: '/environment?agent-id&agent-rollup',
      templateUrl: 'views/jvm/environment.html',
      controller: 'JvmEnvironmentCtrl'
    });
    $stateProvider.state('jvm.threadDump', {
      url: '/thread-dump?agent-id&agent-rollup',
      templateUrl: 'views/jvm/thread-dump.html',
      controller: 'JvmThreadDumpCtrl'
    });
    $stateProvider.state('jvm.jstack', {
      url: '/jstack?agent-id&agent-rollup',
      templateUrl: 'views/jvm/jstack.html',
      controller: 'JvmJstackCtrl'
    });
    $stateProvider.state('jvm.heapDump', {
      url: '/heap-dump?agent-id&agent-rollup',
      templateUrl: 'views/jvm/heap-dump.html',
      controller: 'JvmHeapDumpCtrl'
    });
    $stateProvider.state('jvm.heapHistogram', {
      url: '/heap-histogram?agent-id&agent-rollup',
      templateUrl: 'views/jvm/heap-histogram.html',
      controller: 'JvmHeapHistogramCtrl'
    });
    $stateProvider.state('jvm.gc', {
      url: '/gc?agent-id&agent-rollup',
      templateUrl: 'views/jvm/gc.html',
      controller: 'JvmGcCtrl'
    });
    $stateProvider.state('jvm.mbeanTree', {
      url: '/mbean-tree?agent-id&agent-rollup',
      templateUrl: 'views/jvm/mbean-tree.html',
      controller: 'JvmMBeanTreeCtrl'
    });
    $stateProvider.state('jvm.capabilities', {
      url: '/capabilities?agent-id&agent-rollup',
      templateUrl: 'views/jvm/capabilities.html',
      controller: 'JvmCapabilitiesCtrl'
    });
    $stateProvider.state('config', {
      url: '/config',
      templateUrl: 'views/config.html',
      controller: 'ConfigCtrl',
      resolve: {
        waitForLayout: waitForLayout(false)
      }
    });
    $stateProvider.state('config.transaction', {
      url: '/transaction?agent-id',
      templateUrl: 'views/config/transaction.html',
      controller: 'ConfigCommonCtrl',
      resolve: {
        backendUrl: function () {
          return 'backend/config/transaction';
        }
      }
    });
    $stateProvider.state('config.gaugeList', {
      url: '/gauge-list?agent-id',
      templateUrl: 'views/config/gauge-list.html',
      controller: 'ConfigGaugeListCtrl'
    });
    $stateProvider.state('config.gauge', {
      url: '/gauge?agent-id&v',
      templateUrl: 'views/config/gauge.html',
      controller: 'ConfigGaugeCtrl'
    });
    $stateProvider.state('config.alertList', {
      url: '/alert-list?agent-id',
      templateUrl: 'views/config/alert-list.html',
      controller: 'ConfigAlertListCtrl'
    });
    $stateProvider.state('config.alert', {
      url: '/alert?agent-id&v',
      templateUrl: 'views/config/alert.html',
      controller: 'ConfigAlertCtrl'
    });
    $stateProvider.state('config.ui', {
      url: '/ui',
      templateUrl: 'views/config/ui.html',
      controller: 'ConfigUiCtrl'
    });
    $stateProvider.state('config.pluginList', {
      url: '/plugin-list?agent-id',
      templateUrl: 'views/config/plugin-list.html',
      controller: 'ConfigPluginListCtrl'
    });
    $stateProvider.state('config.plugin', {
      url: '/plugin?agent-id&plugin-id',
      templateUrl: 'views/config/plugin.html',
      controller: 'ConfigPluginCtrl'
    });
    $stateProvider.state('config.instrumentationList', {
      url: '/instrumentation-list?agent-id',
      templateUrl: 'views/config/instrumentation-list.html',
      controller: 'ConfigInstrumentationListCtrl'
    });
    $stateProvider.state('config.instrumentation', {
      url: '/instrumentation?agent-id&v',
      templateUrl: 'views/config/instrumentation.html',
      controller: 'ConfigInstrumentationCtrl'
    });
    $stateProvider.state('config.userRecording', {
      url: '/user-recording',
      templateUrl: 'views/config/user-recording.html',
      controller: 'ConfigUserRecordingCtrl'
    });
    $stateProvider.state('config.advanced', {
      url: '/advanced?agent-id',
      templateUrl: 'views/config/advanced.html',
      controller: 'ConfigCommonCtrl',
      resolve: {
        backendUrl: function () {
          return 'backend/config/advanced';
        }
      }
    });
    $stateProvider.state('admin', {
      url: '/admin',
      templateUrl: 'views/config.html',
      controller: 'ConfigCtrl',
      resolve: {
        waitForLayout: waitForLayout(false)
      }
    });
    $stateProvider.state('admin.userList', {
      url: '/user-list',
      templateUrl: 'views/admin/user-list.html',
      controller: 'AdminUserListCtrl'
    });
    $stateProvider.state('admin.user', {
      url: '/user',
      templateUrl: 'views/admin/user.html',
      controller: 'AdminUserCtrl'
    });
    $stateProvider.state('admin.roleList', {
      url: '/role-list',
      templateUrl: 'views/admin/role-list.html',
      controller: 'AdminRoleListCtrl'
    });
    $stateProvider.state('admin.role', {
      url: '/role',
      templateUrl: 'views/admin/role.html',
      controller: 'AdminRoleCtrl'
    });
    $stateProvider.state('admin.web', {
      url: '/web',
      templateUrl: 'views/admin/web.html',
      controller: 'AdminWebCtrl'
    });
    $stateProvider.state('admin.storage', {
      url: '/storage',
      templateUrl: 'views/admin/storage.html',
      controller: 'AdminStorageCtrl'
    });
    $stateProvider.state('admin.smtp', {
      url: '/smtp',
      templateUrl: 'views/admin/smtp.html',
      controller: 'AdminSmtpCtrl'
    });
    $stateProvider.state('admin.ldap', {
      url: '/ldap',
      templateUrl: 'views/admin/ldap.html',
      controller: 'AdminLdapCtrl'
    });
    $stateProvider.state('admin.changePassword', {
      url: '^/change-password',
      templateUrl: 'views/change-password.html',
      controller: 'ChangePasswordCtrl'
    });
    $stateProvider.state('login', {
      url: '/login',
      templateUrl: 'views/login.html',
      controller: 'LoginCtrl',
      resolve: {
        waitForLayout: waitForLayout(false)
      }
    });
  }
]);
