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
$(document).ready(function () {
  'use strict';

  var pluginTemplate = Handlebars.compile($('#pluginTemplate').html());

  Handlebars.registerHelper('ifString', function (type, options) {
    if (type === 'string') {
      return options.fn(this);
    }
    return options.inverse(this);
  });

  Handlebars.registerHelper('ifBoolean', function (type, options) {
    if (type === 'boolean') {
      return options.fn(this);
    }
    return options.inverse(this);
  });

  Handlebars.registerHelper('ifDouble', function (type, options) {
    if (type === 'double') {
      return options.fn(this);
    }
    return options.inverse(this);
  });

  Handlebars.registerHelper('escapeSelector', escapeSelector);

  var generalConfigVersion;
  var coarseConfigVersion;
  var fineConfigVersion;
  var userConfigVersion;
  var storageConfigVersion;
  var pluginConfigVersions = {};
  var pluginDescriptors;

  function read() {
    Informant.showSpinner('#initialLoadSpinner');
    $.getJSON('config/read', function (config) {
      Informant.hideSpinner('#initialLoadSpinner');
      var generalConfig = config.generalConfig;
      setStatus('#generalStatus', generalConfig.enabled, true);
      $('#generalEnabled').attr('checked', generalConfig.enabled);
      $('#storeThresholdMillis').val(generalConfig.storeThresholdMillis);
      Informant.addIntegerValidator('#storeThresholdMillis');
      $('#stuckThresholdSeconds').val(generalConfig.stuckThresholdSeconds);
      Informant.addIntegerValidator('#stuckThresholdSeconds');
      $('#maxSpans').val(generalConfig.maxSpans);
      Informant.addIntegerValidator('#maxSpans');
      generalConfigVersion = generalConfig.version;
      var coarseConfig = config.coarseProfilingConfig;
      setStatus('#coarseStatus', coarseConfig.enabled);
      $('#coarseEnabled').attr('checked', coarseConfig.enabled);
      $('#coarseInitialDelayMillis').val(coarseConfig.initialDelayMillis);
      Informant.addIntegerValidator('#coarseInitialDelayMillis');
      $('#coarseIntervalMillis').val(coarseConfig.intervalMillis);
      Informant.addIntegerValidator('#coarseIntervalMillis');
      $('#coarseTotalSeconds').val(coarseConfig.totalSeconds);
      Informant.addIntegerValidator('#coarseTotalSeconds');
      coarseConfigVersion = coarseConfig.version;
      var fineConfig = config.fineProfilingConfig;
      setStatus('#fineStatus', fineConfig.enabled);
      $('#fineEnabled').attr('checked', fineConfig.enabled);
      $('#fineTracePercentage').val(fineConfig.tracePercentage);
      Informant.addPercentageValidator('#fineTracePercentage');
      $('#fineIntervalMillis').val(fineConfig.intervalMillis);
      Informant.addIntegerValidator('#fineIntervalMillis');
      $('#fineTotalSeconds').val(fineConfig.totalSeconds);
      Informant.addIntegerValidator('#fineTotalSeconds');
      if (fineConfig.storeThresholdMillis === -1) {
        $('#fineStoreThresholdMillis').attr('readonly', 'readonly');
      } else {
        $('#fineStoreThresholdOverride').attr('checked', true);
        $('#fineStoreThresholdMillis').val(fineConfig.storeThresholdMillis);
      }
      Informant.addIntegerOrEmptyValidator('#fineStoreThresholdMillis');
      fineConfigVersion = fineConfig.version;
      var userConfig = config.userConfig;
      setStatus('#userStatus', userConfig.enabled);
      $('#userEnabled').attr('checked', userConfig.enabled);
      $('#userUserId').val(userConfig.userId);
      $('#userStoreThresholdMillis').val(userConfig.storeThresholdMillis);
      Informant.addIntegerValidator('#userStoreThresholdMillis');
      $('#userFineProfiling').attr('checked', userConfig.fineProfiling);
      userConfigVersion = userConfig.version;
      var storageConfig = config.storageConfig;
      $('#storageSnapshotExpirationDays').val(storageConfig.snapshotExpirationHours / 24);
      Informant.addIntegerValidator('#storageSnapshotExpirationDays');
      $('#storageRollingSizeMb').val(storageConfig.rollingSizeMb);
      Informant.addIntegerValidator('#storageRollingSizeMb');
      var $offscreenMeasure = $('#offscreenMeasure');
      $offscreenMeasure.text(config.dataDir);
      var $storageDataDir = $('#storageDataDir');
      $storageDataDir.text(config.dataDir);
      $storageDataDir.css('width', ($offscreenMeasure.width() + 50) + 'px');
      storageConfigVersion = storageConfig.version;
      pluginDescriptors = config.pluginDescriptors;
      var i, j;
      for (i = 0; i < pluginDescriptors.length; i++) {
        var pluginDescriptor = pluginDescriptors[i];
        pluginDescriptor.id = pluginDescriptor.groupId + ':' + pluginDescriptor.artifactId;
        var propertyDescriptors = [];
        for (j = 0; j < pluginDescriptor.properties.length; j++) {
          if (!pluginDescriptor.properties[j].hidden) {
            propertyDescriptors.push(pluginDescriptor.properties[j]);
          }
        }
        pluginDescriptor.properties = propertyDescriptors;
        $('#plugins').append(pluginTemplate(pluginDescriptor));
        var pluginConfig = config.pluginConfigs[pluginDescriptor.id];
        pluginConfigVersions[pluginDescriptor.id] = pluginConfig.version;
        var enabled = pluginConfig.enabled;
        setStatus('#' + escapeSelector(pluginDescriptor.id) + '_status', enabled);
        $('#' + escapeSelector(pluginDescriptor.id) + '_enabled').attr('checked', enabled);
        for (j = 0; j < pluginDescriptor.properties.length; j++) {
          var propertyDescriptor = pluginDescriptor.properties[j];
          var value = pluginConfig.properties[propertyDescriptor.name];
          var $selector =
              $('#' + escapeSelector(pluginDescriptor.id + '_' + propertyDescriptor.name));
          if (propertyDescriptor.type === 'boolean') {
            $selector.attr('checked', value);
          } else if (propertyDescriptor.type === 'double') {
            Informant.addDoubleValidator($selector);
            $selector.val(value);
          } else {
            $selector.val(value);
          }
        }
      }
    });
  }

  function saveGeneralConfig() {
    var enabled = $('#generalEnabled').is(':checked');
    var storeThresholdMillis = $('#storeThresholdMillis').val();
    var stuckThresholdSeconds = $('#stuckThresholdSeconds').val();
    var maxSpans = $('#maxSpans').val();
    // handle validation
    if (!Informant.isInteger(storeThresholdMillis) || !Informant.isInteger(stuckThresholdSeconds)
        || !Informant.isInteger(maxSpans)) {
      return;
    }
    var config = {
      'enabled': enabled,
      'storeThresholdMillis': Informant.parseInteger(storeThresholdMillis),
      'stuckThresholdSeconds': Informant.parseInteger(stuckThresholdSeconds),
      'maxSpans': Informant.parseInteger(maxSpans),
      'version': generalConfigVersion
    };
    $.post('config/general', JSON.stringify(config), function (response) {
      generalConfigVersion = response;
      Informant.showAndFadeSuccessMessage('#generalSaveComplete');
      setStatus('#generalStatus', config.enabled, true);
    });
  }

  function saveCoarseConfig() {
    var enabled = $('#coarseEnabled').is(':checked');
    var initialDelayMillis = $('#coarseInitialDelayMillis').val();
    var intervalMillis = $('#coarseIntervalMillis').val();
    var totalSeconds = $('#coarseTotalSeconds').val();
    // handle validation
    if (!Informant.isInteger(initialDelayMillis) || !Informant.isInteger(intervalMillis)
        || !Informant.isInteger(totalSeconds)) {
      return;
    }
    var config = {
      'enabled': enabled,
      'initialDelayMillis': Informant.parseInteger(initialDelayMillis),
      'intervalMillis': Informant.parseInteger(intervalMillis),
      'totalSeconds': Informant.parseInteger(totalSeconds),
      'version': coarseConfigVersion
    };
    $.post('config/coarse-profiling', JSON.stringify(config), function (response) {
      coarseConfigVersion = response;
      Informant.showAndFadeSuccessMessage('#coarseSaveComplete');
      setStatus('#coarseStatus', config.enabled);
    });
  }

  function saveFineConfig() {
    var enabled = $('#fineEnabled').is(':checked');
    var tracePercentage = $('#fineTracePercentage').val();
    var intervalMillis = $('#fineIntervalMillis').val();
    var totalSeconds = $('#fineTotalSeconds').val();
    var storeThresholdMillis = $('#fineStoreThresholdMillis').val();
    // handle validation
    if (!Informant.isPercentage(tracePercentage) || !Informant.isInteger(intervalMillis)
        || !Informant.isInteger(totalSeconds)
        || (storeThresholdMillis !== '' && !Informant.isInteger(storeThresholdMillis))) {
      return;
    }
    if (storeThresholdMillis === '') {
      storeThresholdMillis = '-1';
    }
    var config = {
      'enabled': enabled,
      'tracePercentage': Informant.parsePercentage(tracePercentage),
      'intervalMillis': Informant.parseInteger(intervalMillis),
      'totalSeconds': Informant.parseInteger(totalSeconds),
      'storeThresholdMillis': Informant.parseInteger(storeThresholdMillis),
      'version': fineConfigVersion
    };
    $.post('config/fine-profiling', JSON.stringify(config), function (response) {
      fineConfigVersion = response;
      Informant.showAndFadeSuccessMessage('#fineSaveComplete');
      setStatus('#fineStatus', config.enabled);
    });
  }

  function saveUserConfig() {
    var enabled = $('#userEnabled').is(':checked');
    var storeThresholdMillis = $('#userStoreThresholdMillis').val();
    // handle validation
    if (!Informant.isInteger(storeThresholdMillis)) {
      return;
    }
    var config = {
      'enabled': enabled,
      'userId': $('#userUserId').val(),
      'storeThresholdMillis': Informant.parseInteger(storeThresholdMillis),
      'fineProfiling': $('#userFineProfiling').is(':checked'),
      'version': userConfigVersion
    };
    $.post('config/user', JSON.stringify(config), function (response) {
      userConfigVersion = response;
      Informant.showAndFadeSuccessMessage('#userSaveComplete');
      setStatus('#userStatus', config.enabled);
    });
  }

  // saveStorageConfig can take a while if resizing large rolling database
  var savingStorageConfig = false;

  function saveStorageConfig() {
    // handle crazy user clicking on the button because this can take a while if resizing large
    // rolling database
    if (savingStorageConfig) {
      return;
    }
    var snapshotExpirationDays = $('#storageSnapshotExpirationDays').val();
    var rollingSizeMb = $('#storageRollingSizeMb').val();
    // handle validation
    if (!Informant.isInteger(snapshotExpirationDays) || !Informant.isInteger(rollingSizeMb)) {
      return;
    }
    savingStorageConfig = true;
    var config = {
      'snapshotExpirationHours': Informant.parseInteger(snapshotExpirationDays) * 24,
      'rollingSizeMb': Informant.parseInteger(rollingSizeMb),
      'version': storageConfigVersion
    };
    $.post('config/storage', JSON.stringify(config), function (response) {
      savingStorageConfig = false;
      storageConfigVersion = response;
      Informant.hideSpinner('#saveStorageButtonSpinner');
      Informant.showAndFadeSuccessMessage('#storageSaveComplete');
    });
    // in case button is clicked again before success message is hidden
    $('#saveStorageComplete').addClass('hide');
    Informant.showSpinner('#saveStorageButtonSpinner');
  }

  function savePluginConfig(pluginId) {
    var pluginDescriptor = pluginDescriptorForId(pluginId);
    var config = {
      'enabled': $('#' + escapeSelector(pluginId) + '_enabled').is(':checked'),
      'properties': {},
      'version': pluginConfigVersions[pluginId]
    };
    var validationError = false;
    var i;
    for (i = 0; i < pluginDescriptor.properties.length; i++) {
      var propertyDescriptor = pluginDescriptor.properties[i];
      if (propertyDescriptor.hidden) {
        continue;
      }
      var value;
      var $selector = $('#' + escapeSelector(pluginDescriptor.id + '_' + propertyDescriptor.name));
      if (propertyDescriptor.type === 'boolean') {
        value = $selector.is(':checked');
      } else if (propertyDescriptor.type === 'double') {
        if (Informant.isDouble($selector.val())) {
          value = Informant.parseDouble($selector.val());
        } else {
          validationError = true;
        }
      } else {
        value = $selector.val();
      }
      config.properties[propertyDescriptor.name] = value;
    }
    // check for validation error
    if (validationError) {
      return;
    }
    $.post('config/plugin/' + pluginId, JSON.stringify(config), function (response) {
      pluginConfigVersions[pluginId] = response;
      Informant.showAndFadeSuccessMessage('#' + escapeSelector(pluginId) + '_saveComplete');
      setStatus('#' + escapeSelector(pluginId) + '_status', config.enabled);
    });
  }

  function setStatus(selector, value, general) {
    var $selector = $(selector);
    if (value) {
      $selector.removeClass('configuration-status-off');
      $selector.addClass('configuration-status-on');
      $selector.text('ON');
    } else {
      $selector.removeClass('configuration-status-on');
      $selector.addClass('configuration-status-off');
      $selector.text('OFF');
    }
    if (!general && !$('#generalEnabled').is(':checked')) {
      // this needs to be set the first time through, at least for the plugin nodes which didn't
      // exist when setStatus was called for general the first time
      $selector.css('color', '#bbb');
    }
    if (general) {
      // update other status'
      var $configurationStatus = $('.configuration-status');
      if (value) {
        $configurationStatus.css('color', '');
      } else {
        $configurationStatus.css('color', '#bbb');
        // except for general
        $selector.css('color', '');
      }
    }
  }

  function pluginDescriptorForId(pluginId) {
    var i;
    for (i = 0; i < pluginDescriptors.length; i++) {
      if (pluginDescriptors[i].id === pluginId) {
        return pluginDescriptors[i];
      }
    }
    return null;
  }

  function escapeSelector(id) {
    // '.' and ':' have to be escaped for jquery selectors
    return id.replace(/\./g, '\\.').replace(/:/g, '\\:');
  }

  Informant.configureAjaxError();
  $('#fineStoreThresholdOverride').change(function () {
    var $fineStoreThresholdMillis = $('#fineStoreThresholdMillis');
    if ($('#fineStoreThresholdOverride').is(':checked')) {
      $fineStoreThresholdMillis.removeAttr('readonly');
    } else {
      $fineStoreThresholdMillis.val('');
      $fineStoreThresholdMillis.closest('.control-group').removeClass('error');
      $fineStoreThresholdMillis.attr('readonly', 'readonly');
    }
  });
  read();
  $('#saveGeneralButton').click(saveGeneralConfig);
  $('#saveCoarseButton').click(saveCoarseConfig);
  $('#saveFineButton').click(saveFineConfig);
  $('#saveUserButton').click(saveUserConfig);
  $('#saveStorageButton').click(saveStorageConfig);
  $('#plugins').on('click', '.save-plugin-button', function () {
    savePluginConfig($(this).data('plugin-id'));
  });
  var postingDeleteAll = false;
  $('#deleteAllButton').click(function () {
    // handle crazy user clicking on the button
    if (postingDeleteAll) {
      return;
    }
    postingDeleteAll = true;
    $.post('admin/data/delete-all', function () {
      postingDeleteAll = false;
      Informant.hideSpinner('#deleteAllSpinner');
      Informant.showAndFadeSuccessMessage('#deleteAllSuccessMessage');
    });
    // in case button is clicked again before success message is hidden
    $('#deleteAllSuccessMessage').addClass('hide');
    Informant.showSpinner('#deleteAllSpinner');
  });
});
