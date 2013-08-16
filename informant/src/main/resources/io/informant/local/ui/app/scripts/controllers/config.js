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

/* global informant, Informant, $ */

informant.controller('ConfigCtrl', function ($scope, $http) {

  document.title = 'Configuration | Informant';
  $scope.$parent.title = 'Configuration';

  $scope.pattern = {
    // TODO allow comma as decimal separator (and check out html5 input type="number")
    // tolerant of missing whole (.2) and missing decimal (2.)
    percentage: '/^(0|[1-9][0-9]?|100)?(\\.[0-9]*)?$/',
    // tolerant of commas
    integer: '/^(0|[1-9][0-9]*)$/',
    // missing whole (.2) and missing decimal (2.)
    double: '/^(0|[1-9][0-9]*)?(\\.[0-9]*)?$/'
  };

  $scope.saveGeneralConfig = function (deferred) {
    $http.post('backend/config/general', $scope.config.generalConfig)
        .success(function (response) {
          $scope.config.generalConfig.version = response;
          $scope.generalEnabled = $scope.config.generalConfig.enabled;
          deferred.resolve('Saved');
        })
        .error(function (response) {
          //TODO
          deferred.reject('Error occurred');
        });
  };

  $scope.saveCoarseProfilingConfig = function (deferred) {
    $http.post('backend/config/coarse-profiling', $scope.config.coarseProfilingConfig)
        .success(function (response) {
          $scope.config.coarseProfilingConfig.version = response;
          $scope.coarseEnabled = $scope.config.coarseProfilingConfig.enabled;
          deferred.resolve('Saved');
        })
        .error(function (response) {
          //TODO
          deferred.reject('Error occurred');
        });
  };

  $scope.saveFineProfilingConfig = function (deferred) {
    $http.post('backend/config/fine-profiling', $scope.config.fineProfilingConfig)
        .success(function (response) {
          $scope.config.fineProfilingConfig.version = response;
          $scope.fineEnabled = $scope.config.fineProfilingConfig.enabled;
          deferred.resolve('Saved');
        })
        .error(function (response) {
          //TODO
          deferred.reject('Error occurred');
        });
  };

  $scope.saveUserOverridesConfig = function (deferred) {
    $http.post('backend/config/user-overrides', $scope.config.userOverridesConfig)
        .success(function (response) {
          $scope.config.userOverridesConfig.version = response;
          $scope.userEnabled = $scope.config.userOverridesConfig.enabled;
          deferred.resolve('Saved');
        })
        .error(function (response) {
          //TODO
          deferred.reject('Error occurred');
        });
  };

  $scope.saveStorageConfig = function (deferred) {
    $http.post('backend/config/storage', $scope.config.storageConfig)
        .success(function (response) {
          $scope.config.storageConfig.version = response;
          deferred.resolve('Saved');
        })
        .error(function (response) {
          //TODO
          deferred.reject('Error occurred');
        });
  };

  $scope.deleteAll = function (deferred) {
    $http.post('backend/admin/data/delete-all')
        .success(function (response) {
          deferred.resolve('Saved');
        })
        .error(function (response) {
          //TODO
          deferred.reject('Error occurred');
        });
  };

  $scope.savePluginConfig = function (deferred, pluginIndex) {
    var plugin = $scope.plugins[pluginIndex];
    var properties = {};
    var i;
    for (i = 0; i < plugin.descriptor.properties.length; i++) {
      var property = plugin.descriptor.properties[i];
      if (property.type === 'double') {
        properties[property.name] = parseFloat(property.value);
      } else {
        properties[property.name] = property.value;
      }
    }
    var config = {
      'enabled': plugin.config.enabled,
      'properties': properties,
      'version': plugin.config.version
    };
    $http.post('backend/config/plugin/' + plugin.id, config)
        .success(function (response) {
          plugin.config.version = response;
          plugin.enabled = plugin.config.enabled;
          deferred.resolve('Saved');
        })
        .error(function (response) {
          //TODO
          deferred.reject('Error occurred');
        });
  };

  // TODO CONVERT TO ANGULARJS, global $http error handler?
  Informant.configureAjaxError();
  // TODO fix initial load spinner
  Informant.showSpinner('#initialLoadSpinner');
  $http.get('backend/config')
      .success(function (config) {
        Informant.hideSpinner('#initialLoadSpinner');

        $scope.config = config;
        $scope.plugins = [];
        var i, j;
        for (i = 0; i < config.pluginDescriptors.length; i++) {
          var plugin = {};
          plugin.descriptor = config.pluginDescriptors[i];
          plugin.id = plugin.descriptor.groupId + ':' + plugin.descriptor.artifactId;
          plugin.config = config.pluginConfigs[plugin.id];
          for (j = 0; j < plugin.descriptor.properties.length; j++) {
            var property = plugin.descriptor.properties[j];
            property.value = plugin.config.properties[property.name];
          }
          $scope.plugins.push(plugin);
        }

        $scope.generalEnabled = $scope.config.generalConfig.enabled;
        $scope.coarseEnabled = $scope.config.coarseProfilingConfig.enabled;
        $scope.fineEnabled = $scope.config.fineProfilingConfig.enabled;
        $scope.userEnabled = $scope.config.userOverridesConfig.enabled;
        for (i = 0; i < $scope.plugins.length; i++) {
          $scope.plugins[i].enabled = $scope.plugins[i].config.enabled;
        }

        var $offscreenMeasure = $('#offscreenMeasure');
        $offscreenMeasure.text(config.dataDir);
        $scope.dataDirControlWidth = ($offscreenMeasure.width() + 50) + 'px';

        // set up calculated properties
        $scope.data = {};
        $scope.data.snapshotExpirationDays = config.storageConfig.snapshotExpirationHours / 24;
        $scope.$watch('data.snapshotExpirationDays', function (newValue) {
          config.storageConfig.snapshotExpirationHours = newValue * 24;
        });
        if (config.fineProfilingConfig.storeThresholdMillis !== -1) {
          $scope.data.fineStoreThresholdOverride = true;
          $scope.data.fineStoreThresholdMillis = config.fineProfilingConfig.storeThresholdMillis;
        } else {
          $scope.data.fineStoreThresholdOverride = false;
          $scope.data.fineStoreThresholdMillis = '';
        }
        $scope.$watch('[data.fineStoreThresholdOverride, data.fineStoreThresholdMillis]',
            function (newValue) {
              if (newValue[0]) {
                if ($scope.data.fineStoreThresholdMillis === '') {
                  $scope.data.fineStoreThresholdMillis =
                      $scope.config.generalConfig.storeThresholdMillis;
                }
                config.fineProfilingConfig.storeThresholdMillis = $scope.data.fineStoreThresholdMillis;
              } else {
                $scope.data.fineStoreThresholdMillis = '';
                config.fineProfilingConfig.storeThresholdMillis = -1;
              }
            }, true);
      })
      .error(function (error) {
        // TODO
      });
});

informant.directive('ixOnOff', function () {
  return {
    scope: {
      ixModel: '&',
      ixDisabled: '&'
    },
    templateUrl: 'template/ix-on-off.html'
  };
});
