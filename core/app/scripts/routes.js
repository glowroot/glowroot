/*
 * Copyright 2012-2014 the original author or authors.
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
    var waitForLayout;
    if (!window.layout) {
      // some controllers need to wait for layout when running under grunt serve
      waitForLayout = {
        dummy: ['$q', '$rootScope', function ($q, $rootScope) {
          var deferred = $q.defer();
          var unregisterWatch = $rootScope.$watch('layout', function (value) {
            if (value) {
              deferred.resolve();
              unregisterWatch();
            }
          });
          return deferred.promise;
        }]
      };
    }
    // overriding autoscroll=true behavior to scroll to the top of the page
    $provide.decorator('$uiViewScroll', [
      function () {
        return function () {
          window.scrollTo(0, 0);
        };
      }
    ]);
    $urlRouterProvider.otherwise('/home');
    $stateProvider.state('home', {
      url: '/home',
      templateUrl: 'views/home.html',
      controller: 'HomeCtrl',
      resolve: waitForLayout
    });
    $stateProvider.state('traces', {
      url: '/traces',
      templateUrl: 'views/traces.html',
      controller: 'TracesCtrl'
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
    $stateProvider.state('jvm.memoryOverview', {
      url: '/memory-overview',
      templateUrl: 'views/jvm/memory-overview.html',
      controller: 'JvmMemoryOverviewCtrl'
    });
    $stateProvider.state('jvm.heapHistogram', {
      url: '/heap-histogram',
      templateUrl: 'views/jvm/heap-histogram.html',
      controller: 'JvmHeapHistogramCtrl'
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
    $stateProvider.state('jvm.capabilities', {
      url: '/capabilities',
      templateUrl: 'views/jvm/capabilities.html',
      controller: 'JvmCapabilitiesCtrl'
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
    $stateProvider.state('config.userInterface', {
      url: '/user-interface',
      templateUrl: 'views/config/user-interface.html',
      controller: 'ConfigUserInterfaceCtrl'
    });
    $stateProvider.state('config.pointcuts', {
      url: '/pointcuts',
      templateUrl: 'views/config/pointcut-list.html',
      controller: 'PointcutListCtrl'
    });
    $stateProvider.state('config.advanced', {
      url: '/advanced',
      templateUrl: 'views/config/advanced.html',
      controller: 'ConfigAdvancedCtrl'
    });
    $stateProvider.state('plugin', {
      url: '/plugin',
      templateUrl: 'views/plugin.html',
      controller: 'PluginCtrl'
    });
    $stateProvider.state('plugin.x', {
      url: '/:id',
      templateUrl: 'views/plugin-x.html',
      controller: 'PluginXCtrl'
    });
    $stateProvider.state('login', {
      url: '/login',
      templateUrl: 'views/login.html',
      controller: 'LoginCtrl'
    });
  }
]);
