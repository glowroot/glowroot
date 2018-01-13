/*
 * Copyright 2012-2018 the original author or authors.
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

glowroot.config([
  '$provide',
  '$stateProvider',
  '$urlRouterProvider',
  function ($provide, $stateProvider, $urlRouterProvider) {
    function waitForLayout($q, $rootScope) {
      var deferred = $q.defer();
      var unregisterWatch = $rootScope.$watch('layout', function (value) {
        if (value) {
          deferred.resolve();
          unregisterWatch();
        }
      });
      return deferred.promise;
    }
    var waitForLayoutOnly = function () {
      return ['$q', '$rootScope', function ($q, $rootScope) {
        return waitForLayout($q, $rootScope);
      }];
    };
    var onTransitionWithAgentRollup = function (needsTransactionType) {
      return ['$q', '$rootScope', '$location', '$http', function ($q, $rootScope, $location, $http) {

        return waitForLayout($q, $rootScope).then(function () {

          function addTransactionType() {
            if (needsTransactionType && !$location.search()['transaction-type']) {
              $location.search('transaction-type', $rootScope.defaultTransactionType());
              $location.replace();
            }
          }

          if (!$rootScope.layout.central) {
            addTransactionType();
            return;
          }

          var agentId = $location.search()['agent-id'] || '';
          var agentRollupId = $location.search()['agent-rollup-id'] || agentId;
          if (!agentRollupId || angular.isArray(agentRollupId)) {
            delete $rootScope.agentId;
            delete $rootScope.agentRollupId;
            delete $rootScope.agentRollup;
            return;
          }

          if ($rootScope.agentRollup && $rootScope.agentRollup.id === agentRollupId) {
            addTransactionType();
            return;
          }

          return $http.get('backend/agent-rollup?id=' + encodeURIComponent(agentRollupId))
              .then(function (response) {
                $rootScope.agentId = agentId;
                $rootScope.agentRollupId = agentRollupId;
                $rootScope.agentRollup = response.data;
                addTransactionType();
              }, function (response) {
                $rootScope.navbarErrorMessage = 'An error occurred getting agent rollup: ' + agentRollupId;
                if (response.data.message) {
                  $rootScope.navbarErrorMessage += ': ' + response.data.message;
                }
                var unregisterListener = $rootScope.$on('gtStateChangeSuccess', function () {
                  $rootScope.navbarErrorMessage = '';
                  unregisterListener();
                });
              });
        });
      }];
    };
    $urlRouterProvider.otherwise(function ($injector) {
      var $rootScope = $injector.get('$rootScope');
      // TODO revisit this, especially for server
      if (!$rootScope.layout) {
        // don't seem able to return promise for 'otherwise', oh well, this is only for grunt serve anyways
        return 'transaction/average';
      }
      if ($rootScope.layout.showNavbarTransaction) {
        return 'transaction/average';
      } else if ($rootScope.layout.showNavbarError) {
        return 'error/messages';
      } else if ($rootScope.layout.showNavbarJvm) {
        if (!$rootScope.layout.central) {
          var jvmPermissions = $rootScope.agentRollup.permissions.jvm;
          if (jvmPermissions.gauges) {
            return 'jvm/gauges';
          } else if (jvmPermissions.threadDump) {
            return 'jvm/thread-dump';
          } else if (jvmPermissions.heapDump) {
            return 'jvm/heap-dump';
          } else if (jvmPermissions.heapHistogram) {
            return 'jvm/heap-histogram';
          } else if (jvmPermissions.mbeanTree) {
            return 'jvm/mbean-tree';
          } else if (jvmPermissions.systemProperties) {
            return 'jvm/system-properties';
          } else {
            // only remaining option when showNavbarJvm is true
            return 'jvm/environment';
          }
        } else {
          // TODO this will not work if user has access to other JVM pages, but not gauges
          // (deal with this when revisiting entire 'otherwise', see comment above)
          return 'jvm/gauges';
        }
      } else if ($rootScope.layout.showNavbarConfig) {
        return $rootScope.layout.central ? 'config/general' : 'config/transaction';
      } else if ($rootScope.layout.adminView) {
        return $rootScope.layout.central ? 'admin/user-list' : 'admin/general';
      } else if ($rootScope.layout.loggedIn && !$rootScope.layout.ldap) {
        return 'profile/change-password';
      } else {
        // give up
        return 'transaction/average';
      }
    });
    $stateProvider.state('transaction', {
      abstract: true,
      url: '/transaction?agent-id&agent-rollup-id&transaction-type',
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
        waitForAgentRollup: onTransitionWithAgentRollup(true)
      }
    });
    $stateProvider.state('transaction.detail', {
      abstract: true,
      views: {
        sidebar: {
          templateUrl: 'views/transaction/sidebar.html',
          controller: 'TransactionSidebarCtrl'
        },
        tabs: {
          templateUrl: 'views/transaction/tabs.html',
          controller: 'TransactionTabCtrl'
        }
      },
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
                return $filter('gtMillis')(summary.totalDurationNanos / (1000000 * summary.transactionCount))
                    + ' ms';
              } else if (sortOrder === 'throughput') {
                return (60 * 1000 * summary.transactionCount / durationMillis).toFixed(1) + '/min';
              } else {
                // unexpected sort order
                return '';
              }
            };
          }
        ]
      }
    });
    $stateProvider.state('transaction.detail.average', {
      url: '/average?transaction-type&transaction-name',
      views: {
        'main@transaction': {
          templateUrl: 'views/transaction/average.html',
          controller: 'TransactionAverageCtrl'
        }
      }
    });
    $stateProvider.state('transaction.detail.percentiles', {
      url: '/percentiles?transaction-type&transaction-name',
      views: {
        'main@transaction': {
          templateUrl: 'views/transaction/percentiles.html',
          controller: 'TransactionPercentilesCtrl'
        }
      }
    });
    $stateProvider.state('transaction.detail.throughput', {
      url: '/throughput?transaction-type&transaction-name',
      views: {
        'main@transaction': {
          templateUrl: 'views/transaction/throughput.html',
          controller: 'TransactionThroughputCtrl'
        }
      }
    });
    $stateProvider.state('transaction.detail.traces', {
      url: '/traces?transaction-type&transaction-name',
      views: {
        'main@transaction': {
          templateUrl: 'views/transaction/traces.html',
          controller: 'TracesCtrl'
        }
      },
      resolve: {
        traceKind: function () {
          return 'transaction';
        }
      }
    });
    $stateProvider.state('transaction.detail.queries', {
      url: '/queries?transaction-type&transaction-name',
      views: {
        'main@transaction': {
          templateUrl: 'views/transaction/queries.html',
          controller: 'TransactionQueriesCtrl'
        }
      }
    });
    $stateProvider.state('transaction.detail.services', {
      url: '/services?transaction-type&transaction-name',
      views: {
        'main@transaction': {
          templateUrl: 'views/transaction/services.html',
          controller: 'TransactionServicesCtrl'
        }
      }
    });
    $stateProvider.state('transaction.detail.threadProfile', {
      url: '/thread-profile?transaction-type&transaction-name',
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
        waitForAgentRollup: onTransitionWithAgentRollup(true)
      }
    });
    $stateProvider.state('error', {
      abstract: true,
      url: '/error?agent-id&agent-rollup-id&transaction-type',
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
        waitForAgentRollup: onTransitionWithAgentRollup(true)
      }
    });
    $stateProvider.state('error.detail', {
      abstract: true,
      views: {
        sidebar: {
          templateUrl: 'views/transaction/sidebar.html',
          controller: 'TransactionSidebarCtrl'
        },
        tabs: {
          // same controller, just different html
          templateUrl: 'views/transaction/error-tabs.html',
          controller: 'TransactionTabCtrl'
        }
      },
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
            } else {
              // unexpected sort order
              return '';
            }
          };
        }
      }
    });
    $stateProvider.state('error.detail.messages', {
      url: '/messages?transaction-type&transaction-name',
      views: {
        'main@error': {
          templateUrl: 'views/transaction/error-messages.html',
          controller: 'ErrorMessagesCtrl'
        }
      }
    });
    $stateProvider.state('error.detail.traces', {
      url: '/traces?transaction-type&transaction-name',
      views: {
        'main@error': {
          templateUrl: 'views/transaction/traces.html',
          controller: 'TracesCtrl'
        }
      },
      resolve: {
        traceKind: function () {
          return 'error';
        }
      }
    });
    $stateProvider.state('jvm', {
      url: '/jvm?agent-id&agent-rollup-id',
      templateUrl: 'views/jvm.html',
      controller: 'JvmCtrl',
      resolve: {
        waitForAgentRollup: onTransitionWithAgentRollup(false)
      }
    });
    $stateProvider.state('jvm.gauges', {
      url: '/gauges',
      templateUrl: 'views/jvm/gauge-values.html',
      controller: 'JvmGaugeValuesCtrl'
    });
    $stateProvider.state('jvm.threadDump', {
      url: '/thread-dump',
      templateUrl: 'views/jvm/thread-dump.html',
      controller: 'JvmThreadDumpCtrl'
    });
    $stateProvider.state('jvm.jstack', {
      url: '/jstack',
      templateUrl: 'views/jvm/jstack.html',
      controller: 'JvmJstackCtrl'
    });
    $stateProvider.state('jvm.heapDump', {
      url: '/heap-dump',
      templateUrl: 'views/jvm/heap-dump.html',
      controller: 'JvmHeapDumpCtrl'
    });
    $stateProvider.state('jvm.heapHistogram', {
      url: '/heap-histogram',
      templateUrl: 'views/jvm/heap-histogram.html',
      controller: 'JvmHeapHistogramCtrl'
    });
    $stateProvider.state('jvm.gc', {
      url: '/gc',
      templateUrl: 'views/jvm/gc.html',
      controller: 'JvmGcCtrl'
    });
    $stateProvider.state('jvm.mbeanTree', {
      url: '/mbean-tree',
      templateUrl: 'views/jvm/mbean-tree.html',
      controller: 'JvmMBeanTreeCtrl'
    });
    $stateProvider.state('jvm.systemProperties', {
      url: '/system-properties',
      templateUrl: 'views/jvm/system-properties.html',
      controller: 'JvmSystemPropertiesCtrl'
    });
    $stateProvider.state('jvm.environment', {
      url: '/environment',
      templateUrl: 'views/jvm/environment.html',
      controller: 'JvmEnvironmentCtrl'
    });
    $stateProvider.state('jvm.capabilities', {
      url: '/capabilities',
      templateUrl: 'views/jvm/capabilities.html',
      controller: 'JvmCapabilitiesCtrl'
    });
    $stateProvider.state('syntheticMonitors', {
      url: '/synthetic-monitors?agent-id&agent-rollup-id',
      templateUrl: 'views/synthetic-monitors.html',
      controller: 'SyntheticMonitorsCtrl',
      resolve: {
        waitForAgentRollup: onTransitionWithAgentRollup(false)
      }
    });
    $stateProvider.state('incidents', {
      url: '/incidents',
      templateUrl: 'views/incidents.html',
      controller: 'IncidentsCtrl',
      resolve: {
        waitForAgentRollup: waitForLayoutOnly()
      }
    });
    $stateProvider.state('report', {
      url: '/report',
      templateUrl: 'views/report.html',
      controller: 'ReportCtrl',
      resolve: {
        waitForAgentRollup: waitForLayoutOnly()
      }
    });
    $stateProvider.state('report.adhoc', {
      url: '/ad-hoc',
      templateUrl: 'views/report/adhoc.html',
      controller: 'ReportAdhocCtrl'
    });
    $stateProvider.state('config', {
      url: '/config?agent-id&agent-rollup-id',
      templateUrl: 'views/config.html',
      controller: 'ConfigCtrl',
      resolve: {
        waitForAgentRollup: onTransitionWithAgentRollup(false)
      }
    });
    $stateProvider.state('config.general', {
      url: '/general',
      templateUrl: 'views/config/general.html',
      controller: 'ConfigGeneralCtrl'
    });
    $stateProvider.state('config.transaction', {
      url: '/transaction',
      templateUrl: 'views/config/transaction.html',
      controller: 'ConfigTransactionCtrl'
    });
    $stateProvider.state('config.gaugeList', {
      url: '/gauge-list',
      templateUrl: 'views/config/gauge-list.html',
      controller: 'ConfigGaugeListCtrl'
    });
    $stateProvider.state('config.gauge', {
      url: '/gauge?v',
      templateUrl: 'views/config/gauge.html',
      controller: 'ConfigGaugeCtrl'
    });
    $stateProvider.state('config.jvm', {
      url: '/jvm',
      templateUrl: 'views/config/jvm.html',
      controller: 'ConfigJvmCtrl'
    });
    $stateProvider.state('config.syntheticMonitorList', {
      url: '/synthetic-monitor-list',
      templateUrl: 'views/config/synthetic-monitor-list.html',
      controller: 'ConfigSyntheticMonitorListCtrl'
    });
    $stateProvider.state('config.syntheticMonitor', {
      url: '/synthetic-monitor?id',
      templateUrl: 'views/config/synthetic-monitor.html',
      controller: 'ConfigSyntheticMonitorCtrl'
    });
    $stateProvider.state('config.alertList', {
      url: '/alert-list',
      templateUrl: 'views/config/alert-list.html',
      controller: 'ConfigAlertListCtrl'
    });
    $stateProvider.state('config.alert', {
      url: '/alert?id',
      templateUrl: 'views/config/alert.html',
      controller: 'ConfigAlertCtrl'
    });
    $stateProvider.state('config.ui', {
      url: '/ui',
      templateUrl: 'views/config/ui.html',
      controller: 'ConfigUiCtrl'
    });
    $stateProvider.state('config.pluginList', {
      url: '/plugin-list',
      templateUrl: 'views/config/plugin-list.html',
      controller: 'ConfigPluginListCtrl'
    });
    $stateProvider.state('config.plugin', {
      url: '/plugin?plugin-id',
      templateUrl: 'views/config/plugin.html',
      controller: 'ConfigPluginCtrl'
    });
    $stateProvider.state('config.instrumentationList', {
      url: '/instrumentation-list',
      templateUrl: 'views/config/instrumentation-list.html',
      controller: 'ConfigInstrumentationListCtrl'
    });
    $stateProvider.state('config.instrumentation', {
      url: '/instrumentation?v',
      templateUrl: 'views/config/instrumentation.html',
      controller: 'ConfigInstrumentationCtrl'
    });
    $stateProvider.state('config.userRecording', {
      url: '/user-recording',
      templateUrl: 'views/config/user-recording.html',
      controller: 'ConfigUserRecordingCtrl'
    });
    $stateProvider.state('config.advanced', {
      url: '/advanced',
      templateUrl: 'views/config/advanced.html',
      controller: 'ConfigAdvancedCtrl'
    });
    $stateProvider.state('admin', {
      url: '/admin',
      templateUrl: 'views/admin.html',
      controller: 'AdminCtrl',
      resolve: {
        waitForLayout: waitForLayoutOnly()
      }
    });
    $stateProvider.state('admin.general', {
      url: '/general',
      templateUrl: 'views/admin/general.html',
      controller: 'AdminGeneralCtrl'
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
    $stateProvider.state('admin.httpProxy', {
      url: '/http-proxy',
      templateUrl: 'views/admin/http-proxy.html',
      controller: 'AdminHttpProxyCtrl'
    });
    $stateProvider.state('admin.integrationList', {
      url: '/integration-list',
      templateUrl: 'views/admin/integration-list.html'
    });
    $stateProvider.state('admin.ldap', {
      url: '/integration/ldap',
      templateUrl: 'views/admin/integration/ldap.html',
      controller: 'AdminLdapCtrl'
    });
    $stateProvider.state('admin.pagerDuty', {
      url: '/integration/pager-duty',
      templateUrl: 'views/admin/integration/pager-duty.html',
      controller: 'AdminPagerDutyCtrl'
    });
    $stateProvider.state('admin.healthchecksIo', {
      url: '/integration/healthchecks-io',
      templateUrl: 'views/admin/integration/healthchecks-io.html',
      controller: 'AdminHealthchecksIoCtrl'
    });
    $stateProvider.state('profile', {
      url: '/profile',
      templateUrl: 'views/profile.html',
      controller: 'ProfileCtrl',
      resolve: {
        waitForLayout: waitForLayoutOnly()
      }
    });
    $stateProvider.state('profile.changePassword', {
      url: '/change-password',
      templateUrl: 'views/profile/change-password.html',
      controller: 'ProfileChangePasswordCtrl'
    });
    $stateProvider.state('login', {
      url: '/login',
      templateUrl: 'views/login.html',
      controller: 'LoginCtrl',
      resolve: {
        waitForLayout: waitForLayoutOnly()
      }
    });
  }
]);
