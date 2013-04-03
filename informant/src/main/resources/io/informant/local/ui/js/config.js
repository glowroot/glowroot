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
(function () {
  'use strict';
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
  Handlebars.registerHelper('ifMetric', function (pointcut, options) {
    if (pointcut.captureItems.indexOf('metric') !== -1) {
      return options.fn(this);
    }
    return options.inverse(this);
  });
  Handlebars.registerHelper('ifSpan', function (pointcut, options) {
    if (pointcut.captureItems.indexOf('span') !== -1) {
      return options.fn(this);
    }
    return options.inverse(this);
  });
  Handlebars.registerHelper('escapeSelector', escapeSelector);

  var pluginTemplate = Handlebars.compile($('#pluginTemplate').html());
  var pointcutTemplate = Handlebars.compile($('#pointcutTemplate').html());
  var generalConfigVersion;
  var coarseConfigVersion;
  var fineConfigVersion;
  var userConfigVersion;
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
      // storage
      $('#dataExpirationDays').val(generalConfig.snapshotExpirationHours / 24);
      Informant.addIntegerValidator('#dataExpirationDays');
      $('#dataRollingSizeMb').val(generalConfig.rollingSizeMb);
      Informant.addIntegerValidator('#dataRollingSizeMb');
      $('#offscreenMeasure').text(config.dataDir);
      $('#dataDir').text(config.dataDir);
      $('#dataDir').css('width', ($('#offscreenMeasure').width() + 50) + 'px');
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
          var selector =
              '#' + escapeSelector(pluginDescriptor.id + '_' + propertyDescriptor.name);
          if (propertyDescriptor.type === 'boolean') {
            $(selector).attr('checked', value);
          } else if (propertyDescriptor.type === 'double') {
            Informant.addDoubleValidator(selector);
            $(selector).val(value);
          } else {
            $(selector).val(value);
          }
        }
      }
      var pointcutConfigs = config.pointcutConfigs;
      for (i = 0; i < pointcutConfigs.length; i++) {
        applyPointcutEditTemplate(pointcutConfigs[i], $('#existingPointcuts'));
      }
    });
  }

// updateGeneralConfig can take a while if resizing large rolling database
  var postingUpdateGeneral = false;

  function saveGeneralConfig() {
    // handle crazy user clicking on the button because this can take a while if resizing large
    // rolling database
    if (postingUpdateGeneral) {
      return;
    }
    var enabled = $('#generalEnabled').is(':checked');
    var storeThresholdMillis = $('#storeThresholdMillis').val();
    var stuckThresholdSeconds = $('#stuckThresholdSeconds').val();
    var maxSpans = $('#maxSpans').val();
    var dataExpirationDays = $('#dataExpirationDays').val();
    var dataRollingSizeMb = $('#dataRollingSizeMb').val();
    // handle validation
    if (!Informant.isInteger(storeThresholdMillis) || !Informant.isInteger(stuckThresholdSeconds)
        || !Informant.isInteger(maxSpans) || !Informant.isInteger(dataExpirationDays)
        || !Informant.isInteger(dataRollingSizeMb)) {
      return;
    }
    postingUpdateGeneral = true;
    var config = {
      'enabled': enabled,
      'storeThresholdMillis': Informant.parseInteger(storeThresholdMillis),
      'stuckThresholdSeconds': Informant.parseInteger(stuckThresholdSeconds),
      'maxSpans': Informant.parseInteger(maxSpans),
      'snapshotExpirationHours': Informant.parseInteger(dataExpirationDays) * 24,
      'rollingSizeMb': Informant.parseInteger(dataRollingSizeMb),
      'version': generalConfigVersion
    };
    $.post('config/general', JSON.stringify(config), function (response) {
      postingUpdateGeneral = false;
      generalConfigVersion = response;
      Informant.hideSpinner('#saveGeneralButtonSpinner');
      Informant.showAndFadeSuccessMessage('#generalSaveComplete');
      setStatus('#generalStatus', config.enabled, true);
    });
    // in case button is clicked again before success message is hidden
    $('#saveGeneralComplete').addClass('hide');
    Informant.showSpinner('#saveGeneralButtonSpinner');
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
      var selector = '#' + escapeSelector(pluginDescriptor.id + '_' + propertyDescriptor.name);
      if (propertyDescriptor.type === 'boolean') {
        value = $(selector).is(':checked');
      } else if (propertyDescriptor.type === 'double') {
        if (Informant.isDouble($(selector).val())) {
          value = Informant.parseDouble($(selector).val());
        } else {
          validationError = true;
        }
      } else {
        value = $(selector).val();
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
    if (value) {
      $(selector).removeClass('configuration-status-off');
      $(selector).addClass('configuration-status-on');
      $(selector).text('ON');
    } else {
      $(selector).removeClass('configuration-status-on');
      $(selector).addClass('configuration-status-off');
      $(selector).text('OFF');
    }
    if (!general && !$('#generalEnabled').is(':checked')) {
      // this needs to be set the first time through, at least for the plugin nodes which didn't
      // exist when setStatus was called for general the first time
      $(selector).css('color', '#bbb');
    }
    if (general) {
      // update other status'
      if (value) {
        $('.configuration-status').css('color', '');
      } else {
        $('.configuration-status').css('color', '#bbb');
        // except for general
        $(selector).css('color', '');
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

  var pointcutCounter = 0;

  function applyPointcutEditTemplate(pointcut, selector) {
    var pointcutNum = pointcutCounter++;

    function matchingTypeNames(partialTypeName, callback) {
      var url = 'pointcut/matching-type-names?partial-type-name=' + partialTypeName + '&limit=7';
      $.getJSON(url, function (matchingTypeNames) {
        callback(matchingTypeNames);
      });
    }

    function matchingMethodNames(partialMethodName, callback) {
      var url = 'pointcut/matching-method-names?type-name='
          + $('#pointcutTypeName_' + pointcutNum).val() + '&partial-method-name='
          + partialMethodName + '&limit=7';
      $.getJSON(url, function (matchingMethodNames) {
        callback(matchingMethodNames);
      });
    }

    function signatureText(modifiers, returnType, methodName, argTypes) {
      var signature = '';
      var i;
      for (i = 0; i < modifiers.length; i++) {
        signature += modifiers[i].toLowerCase() + ' ';
      }
      signature += returnType + ' ' + methodName + '(';
      for (i = 0; i < argTypes.length; i++) {
        if (i > 0) {
          signature += ', ';
        }
        signature += argTypes[i];
      }
      signature += ')';
      return signature;
    }

    function updateSpanTemplate() {
      var signature = getSignature();
      if (!signature) {
        // no radio button selected
        return;
      }
      var template = $('#pointcutTypeName_' + pointcutNum).val() + '.' + signature.name + '()';
      var i;
      for (i = 0; i < signature.argTypeNames.length; i++) {
        if (i === 0) {
          template += ': {{' + i + '}}';
        } else {
          template += ', {{' + i + '}}';
        }
      }
      if (signature.returnTypeName !== 'void') {
        template += ' => {{?}}';
      }
      $('#pointcutSpanTemplate_' + pointcutNum).val(template);
    }

    function matchingMethods(methodName) {
      var url = 'pointcut/matching-methods?type-name=' + $('#pointcutTypeName_' + pointcutNum).val()
          + '&method-name=' + methodName;
      $.getJSON(url, function (signatures) {
        $('#pointcutMethodSignatures_' + pointcutNum).html('');
        $('#pointcutSpanTemplate_' + pointcutNum).val('');
        var html = '<div style="padding-top: 20px">';
        var i;
        for (i = 0; i < signatures.length; i++) {
          html += '<div style="padding: 10px 0">'
              + '<div class="radio">'
              + '<input type="radio" name="pointcutMethodSignature_' + pointcutNum + '" value="' + i
              + '">'
              + signatureText(signatures[i].modifiers, signatures[i].returnTypeName,
              signatures[i].name, signatures[i].argTypeNames)
              + '<br></div></div>';
        }
        html += '</div>';
        $('#pointcutMethodSignatures_' + pointcutNum).append(html);
        $('#pointcutMethodSignatures_' + pointcutNum).data('signatures', signatures);
        var $pointcutMethodSignatureRadio =
            $('input[type=radio][name=pointcutMethodSignature_' + pointcutNum + ']');
        $pointcutMethodSignatureRadio.change(function () {
          var span = $('#pointcutCaptureSpan_' + pointcutNum).is(':checked');
          if (span) {
            updateSpanTemplate();
          }
        });
        if (signatures.length === 1) {
          $pointcutMethodSignatureRadio.attr('checked', true);
          $pointcutMethodSignatureRadio.change();
        }
      });
    }

    function selectMethodName(methodName) {
      // since matchingMethods clears the span template, check here if the value has really
      // changed (e.g. that a user didn't start altering text and then changed mind and put the
      // previous value back)
      // also, this condition is needed in case where user clicks on typeahead value with mouse in
      // which case change event and typeahead event are called and this condition ensures that the
      // typeahead wins (because it runs first due to manually inserted delay in change event
      // handler)
      if (methodName !== $('#pointcutMethodName_' + pointcutNum).data('selectedValue')) {
        $('#pointcutMethodName_' + pointcutNum).data('selectedValue', methodName);
        matchingMethods(methodName);
      }
      return methodName;
    }

    function updateSectionHiding() {
      var metric = $('#pointcutCaptureMetric_' + pointcutNum).is(':checked');
      var span = $('#pointcutCaptureSpan_' + pointcutNum).is(':checked');
      var trace = $('#pointcutCaptureTrace_' + pointcutNum).is(':checked');
      if (metric) {
        $('#pointcutMetricSection_' + pointcutNum).removeClass('hide');
      } else {
        $('#pointcutMetricSection_' + pointcutNum).addClass('hide');
      }
      if (span || trace) {
        $('#pointcutSpanSection_' + pointcutNum).removeClass('hide');
      } else {
        $('#pointcutSpanSection_' + pointcutNum).addClass('hide');
      }
      if (span && $('#pointcutSpanTemplate_' + pointcutNum).val() === '') {
        // populate default template value on selecting span
        updateSpanTemplate();
      }
      if (!span) {
        // clear template value on de-selecting span
        $('#pointcutSpanTemplate_' + pointcutNum).val('');
      }
    }

    function getSignature() {
      var signatures = $('#pointcutMethodSignatures_' + pointcutNum).data('signatures');
      if (signatures.length === 1) {
        return signatures[0];
      }
      var selectedMethodSignature = $('input[type=radio][name=pointcutMethodSignature_'
          + pointcutNum + ']:checked');
      if (selectedMethodSignature.length === 0) {
        return undefined;
      }
      return signatures[selectedMethodSignature.val()];
    }

    function savePointcut() {
      var captureItems = [];
      if ($('#pointcutCaptureMetric_' + pointcutNum).is(':checked')) {
        captureItems.push('metric');
      }
      if ($('#pointcutCaptureSpan_' + pointcutNum).is(':checked')) {
        captureItems.push('span');
      }
      if ($('#pointcutCaptureTrace_' + pointcutNum).is(':checked')) {
        captureItems.push('trace');
      }
      var signature = getSignature();
      if (!signature) {
        // TODO handle this better
        alert('method for pointcut must be selected');
        return;
      }
      // methodReturnTypeName and methodModifiers are intentionally not included in pointcuts since
      // the method name and arg types are enough to uniquely identify the method, and further
      // restricting the pointcut based on return type and modifiers would make it brittle to slight
      // changes in the return type (e.g. narrowing) or modifiers on the method (e.g. visibility)
      var updatedPointcut = {
        'captureItems': captureItems,
        'typeName': $('#pointcutTypeName_' + pointcutNum).val(),
        'methodName': $('#pointcutMethodName_' + pointcutNum).val(),
        'methodArgTypeNames': signature.argTypeNames,
        'metricName': $('#pointcutMetricName_' + pointcutNum).val(),
        'spanTemplate': $('#pointcutSpanTemplate_' + pointcutNum).val()
      };
      var url;
      if (pointcut.version) {
        url = 'config/pointcut/' + pointcut.version;
      } else {
        url = 'config/pointcut/+';
      }
      $.post(url, JSON.stringify(updatedPointcut), function (response) {
        Informant.showAndFadeSuccessMessage('#pointcutSaveComplete_' + pointcutNum);
        pointcut = updatedPointcut;
        pointcut.version = response;
        fixLabels();
      });
    }

    function fixLabels() {
      if (pointcut.version) {
        $('#pointcutHeader_' + pointcutNum).text(pointcut.typeName + '.' + pointcut.methodName
            + '(' + pointcut.methodArgTypeNames.join(', ') + ')');
        $('#pointcutSaveButton_' + pointcutNum).text('Save');
        $('#pointcutSaveComplete_' + pointcutNum).text('Saved');
      } else {
        $('#pointcutHeader_' + pointcutNum).text('<New Pointcut>');
        $('#pointcutSaveButton_' + pointcutNum).text('Add');
        $('#pointcutSaveComplete_' + pointcutNum).text('Added');
      }
    }

    function addBehavior() {
      $('#pointcutCaptureMetric_' + pointcutNum).click(updateSectionHiding);
      $('#pointcutCaptureSpan_' + pointcutNum).click(updateSectionHiding);
      $('#pointcutCaptureTrace_' + pointcutNum).click(updateSectionHiding);
      $('#pointcutTypeName_' + pointcutNum).typeahead({ source: matchingTypeNames });
      // important to bind typeahead event handler before change handler below since handler logic
      // relies on it running first
      $('#pointcutMethodName_' + pointcutNum).typeahead({
        source: matchingMethodNames,
        updater: selectMethodName
      });
      $('#pointcutTypeName_' + pointcutNum).change(function () {
        // check if the value has really changed (e.g. that a user didn't start altering text and
        // then changed mind and put the previous value back)
        if ($(this).val() !== $(this).data('value')) {
          $(this).data('value', $(this).val());
          $('#pointcutMethodName_' + pointcutNum).val('');
          $('#pointcutMethodSignatures_' + pointcutNum).html('');
          $('#pointcutSpanTemplate_' + pointcutNum).val('');
        }
      });
      $('#pointcutMethodName_' + pointcutNum).change(function () {
        // just in case user types in a value and doesn't select from typeahead
        // but this also gets called if user selects typeahead with mouse (when the field loses
        // focus, before the typeahead gains input control)
        // so delay this action so that it runs after the typeahead in this case, at which time
        // $('#pointcutMethodName_' + pointcutNum).val() will be the value selected in the typeahead
        // instead of the partial value that the user typed
        setTimeout(function () {
          selectMethodName($('#pointcutMethodName_' + pointcutNum).val());
        }, 250);
      });
      $('#pointcutSaveButton_' + pointcutNum).click(function () {
        savePointcut();
      });
      $('#pointcutDeleteButton_' + pointcutNum).click(function () {
        if (pointcut.version) {
          $.post('/config/pointcut/-', JSON.stringify(pointcut.version), function () {
            $('#pointcut_' + pointcutNum).remove();
          });
        } else {
          $('#pointcut_' + pointcutNum).remove();
        }
      });
    }

    function addData() {
      $('#pointcutTypeName_' + pointcutNum).val(pointcut.typeName);
      $('#pointcutMethodName_' + pointcutNum).val(pointcut.methodName);
      $('#pointcutMethodSignatures_' + pointcutNum).html('<div style="padding-top: 20px">'
          + pointcut.methodName + '(' + pointcut.methodArgTypeNames.join(', ') + ')</div>');
      $('#pointcutMethodSignatures_' + pointcutNum).data('signatures', [
        {
          argTypeNames: pointcut.methodArgTypeNames
        }
      ]);
      if (pointcut.captureItems.indexOf('metric') !== -1) {
        $('#pointcutCaptureMetric_' + pointcutNum).attr('checked', true);
      }
      if (pointcut.captureItems.indexOf('span') !== -1) {
        $('#pointcutCaptureSpan_' + pointcutNum).attr('checked', true);
      }
      if (pointcut.captureItems.indexOf('trace') !== -1) {
        $('#pointcutCaptureTrace_' + pointcutNum).attr('checked', true);
      }
      // TODO 'methodArgTypeNames': signature.argTypeNames,
      $('#pointcutMetricName_' + pointcutNum).val(pointcut.metricName);
      $('#pointcutSpanTemplate_' + pointcutNum).val(pointcut.spanTemplate);
      updateSectionHiding();
    }

    $(selector).append(pointcutTemplate({ num: pointcutNum }));
    fixLabels();
    addBehavior();
    if (pointcut.version) {
      addData();
    } else {
      // display form immediately for new pointcut
      $('#pointcutForm_' + pointcutNum).addClass('in');
      $('#pointcutTypeName_' + pointcutNum).focus();
    }
  }

  $(document).ready(function () {
    Informant.configureAjaxError();
    $('#fineStoreThresholdOverride').change(function () {
      if ($('#fineStoreThresholdOverride').is(':checked')) {
        $('#fineStoreThresholdMillis').removeAttr('readonly');
      } else {
        $('#fineStoreThresholdMillis').val('');
        $('#fineStoreThresholdMillis').closest('.control-group').removeClass('error');
        $('#fineStoreThresholdMillis').attr('readonly', 'readonly');
      }
    });
    read();
    $('#saveGeneralButton').click(saveGeneralConfig);
    $('#saveCoarseButton').click(saveCoarseConfig);
    $('#saveFineButton').click(saveFineConfig);
    $('#saveUserButton').click(saveUserConfig);
    $('#plugins').on('click', '.save-plugin-button', function () {
      savePluginConfig($(this).data('plugin-id'));
    });
    $('#pointcutNewButton button').click(function () {
      applyPointcutEditTemplate({}, '#pointcutNew');
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
}());
