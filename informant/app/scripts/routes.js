/*
 * Copyright 2012-2013 the original author or authors.
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

/* global informant */

informant.config([
  '$stateProvider',
  '$urlRouterProvider',
  function ($stateProvider, $urlRouterProvider) {
    $urlRouterProvider.otherwise('/traces');
    $stateProvider.state('traces', {
      url: '/traces',
      templateUrl: 'views/traces.html',
      controller: 'TracesCtrl'
    });
    $stateProvider.state('aggregates', {
      url: '/aggregates',
      templateUrl: 'views/aggregates.html',
      controller: 'AggregatesCtrl'
    });
    $stateProvider.state('jvm', {
      url: '/jvm',
      templateUrl: 'views/jvm.html',
      controller: 'JvmCtrl'
    });
    $stateProvider.state('jvm.general', {
      url: '/general',
      templateUrl: 'views/jvm/general.html',
      controller: 'JvmGeneralCtrl'
    });
    $stateProvider.state('jvm.systemProperties', {
      url: '/system-properties',
      templateUrl: 'views/jvm/system-properties.html',
      controller: 'JvmSystemPropertiesCtrl'
    });
    $stateProvider.state('jvm.threadDump', {
      url: '/thread-dump',
      templateUrl: 'views/jvm/thread-dump.html',
      controller: 'JvmThreadDumpCtrl'
    });
    $stateProvider.state('jvm.heapDump', {
      url: '/heap-dump',
      templateUrl: 'views/jvm/heap-dump.html',
      controller: 'JvmHeapDumpCtrl'
    });
    $stateProvider.state('jvm.diagnosticOptions', {
      url: '/diagnostic-options',
      templateUrl: 'views/jvm/diagnostic-options.html',
      controller: 'JvmDiagnosticOptionsCtrl'
    });
    $stateProvider.state('jvm.allOptions', {
      url: '/all-options',
      templateUrl: 'views/jvm/all-options.html',
      controller: 'JvmAllOptionsCtrl'
    });
    $stateProvider.state('config', {
      url: '/config',
      templateUrl: 'views/config.html',
      controller: 'ConfigCtrl'
    });
    $stateProvider.state('config.general', {
      url: '/general',
      templateUrl: 'views/config/general.html',
      controller: 'ConfigGeneralCtrl'
    });
    $stateProvider.state('config.coarseProfiling', {
      url: '/coarse-grained-profiling',
      templateUrl: 'views/config/coarse-profiling.html',
      controller: 'ConfigCoarseProfilingCtrl'
    });
    $stateProvider.state('config.fineProfiling', {
      url: '/fine-grained-profiling',
      templateUrl: 'views/config/fine-profiling.html',
      controller: 'ConfigFineProfilingCtrl'
    });
    $stateProvider.state('config.userOverrides', {
      url: '/user-specific-overrides',
      templateUrl: 'views/config/user-overrides.html',
      controller: 'ConfigUserOverridesCtrl'
    });
    $stateProvider.state('config.storage', {
      url: '/storage',
      templateUrl: 'views/config/storage.html',
      controller: 'ConfigStorageCtrl'
    });
    $stateProvider.state('config.plugins', {
      url: '/plugins',
      templateUrl: 'views/config/plugin-list.html',
      controller: 'ConfigPluginListCtrl'
    });
    $stateProvider.state('config.adhocPointcuts', {
      url: '/adhoc-pointcuts',
      templateUrl: 'views/config/adhoc-pointcut-list.html',
      controller: 'ConfigAdhocPointcutListCtrl'
    });
    $stateProvider.state('config.advanced', {
      url: '/advanced',
      templateUrl: 'views/config/advanced.html',
      controller: 'ConfigGeneralCtrl'
    });
  }
]);
