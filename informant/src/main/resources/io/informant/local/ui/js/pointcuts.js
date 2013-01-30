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

require.config({
  paths: {
    'bootstrap-typeahead': 'lib/bootstrap/js/bootstrap-typeahead'
  },
  shim: {
    'bootstrap-typeahead': ['jquery']
  }
});

define(function (require) {
  'use strict';
  var $ = require('jquery');
  var Handlebars = require('handlebars');
  var Informant = require('informant');
  var pointcutTemplate = require('hbs!template/pointcut');
  require('bootstrap-transition');
  require('bootstrap-collapse');
  require('bootstrap-typeahead');

  function read() {
    Informant.showSpinner('#initialLoadSpinner');
    $.getJSON('config', function (config) {
      Informant.hideSpinner('#initialLoadSpinner');
      var pointcutConfigs = config.pointcutConfigs;
      var i;
      for (i = 0; i < pointcutConfigs.length; i++) {
        applyPointcutEditTemplate(pointcutConfigs[i]);
      }
      if (config.pointcutConfigsOutOfSync) {
        $('#retransformClassesButton').removeClass('hide');
      }
      if (config.retransformClassesSupported) {
        $('#retransformClassesDiv').removeClass('hide');
      }
    });
  }

  var pointcutCounter = 0;

  function applyPointcutEditTemplate(pointcut) {
    var pointcutNum = pointcutCounter++;

    function matchingTypeNames(partialTypeName, callback) {
      var url = 'pointcut/matching-type-names?partial-type-name=' + partialTypeName + '&limit=7';
      $.getJSON(url, function (matchingTypeNames) {
        callback(matchingTypeNames);
      });
    }

    function matchingMethodNames(partialMethodName, callback) {
      if (partialMethodName.indexOf('*') !== -1) {
        callback([ partialMethodName ]);
        return;
      }
      var url = 'pointcut/matching-method-names?type-name='
          + $('#pointcutTypeName_' + pointcutNum).val() + '&partial-method-name='
          + partialMethodName + '&limit=7';
      $.getJSON(url, function (matchingMethodNames) {
        callback(matchingMethodNames);
      });
    }

    function signatureText(signature) {
      var text = '';
      var i;
      for (i = 0; i < signature.modifiers.length; i++) {
        text += signature.modifiers[i] + ' ';
      }
      text += signature.returnTypeName + ' ' + signature.name + '(';
      for (i = 0; i < signature.argTypeNames.length; i++) {
        if (i > 0) {
          text += ', ';
        }
        text += signature.argTypeNames[i];
      }
      text += ')';
      return text;
    }

    function updateSpanTemplate() {
      var signature = getSignature();
      if (!signature) {
        // no radio button selected
        return;
      }
      var template;
      if (signature.modifiers.indexOf('abstract') != -1) {
        template = '{{this.class.name}}.';
      } else {
        template = $('#pointcutTypeName_' + pointcutNum).val() + '.';
      }
      if (signature.all) {
        // 'all matching' is selected
        template += '{{methodName}}()';
        $('#pointcutSpanTemplate_' + pointcutNum).val(template);
        return;
      }
      template += signature.name + '()';
      var i;
      for (i = 0; i < signature.argTypeNames.length; i++) {
        if (i === 0) {
          template += ': {{' + i + '}}';
        } else {
          template += ', {{' + i + '}}';
        }
      }
      if (signature.returnTypeName !== 'void') {
        template += ' => {{ret}}';
      }
      $('#pointcutSpanTemplate_' + pointcutNum).val(template);
    }

    function matchingMethods(methodName) {
      var url = 'pointcut/matching-methods?type-name=' + $('#pointcutTypeName_' + pointcutNum).val()
          + '&method-name=' + methodName;
      $.getJSON(url, function (signatures) {
        var $pointcutMethodSignatures = $('#pointcutMethodSignatures_' + pointcutNum);
        $pointcutMethodSignatures.html('');
        $('#pointcutSpanTemplate_' + pointcutNum).val('');
        var html = '';
        var i;
        for (i = 0; i < signatures.length; i++) {
          html += '<div class="radio">'
              + '<input type="radio" name="pointcutMethodSignature_' + pointcutNum + '" value="' + i
              + '">' + signatureText(signatures[i]) + '</div>';
        }
        if (signatures.length > 1) {
          html += '<div class="radio">'
            + '<input type="radio" name="pointcutMethodSignature_' + pointcutNum
            + '" value="all">all of the above</div>';
        }
        $pointcutMethodSignatures.append(html);
        $pointcutMethodSignatures.data('signatures', signatures);
        var $pointcutMethodSignatureRadio =
            $('input[type=radio][name=pointcutMethodSignature_' + pointcutNum + ']');
        $pointcutMethodSignatureRadio.change(function () {
          var span = $('#pointcutCaptureSpan_' + pointcutNum).is(':checked');
          var trace = $('#pointcutCaptureTrace_' + pointcutNum).is(':checked');
          if (span || trace) {
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
      var $pointcutMethodName = $('#pointcutMethodName_' + pointcutNum);
      if (methodName !== $pointcutMethodName.data('selectedValue')) {
        $pointcutMethodName.data('selectedValue', methodName);
        if (methodName.indexOf('*') !== -1) {
          var $pointcutMethodSignatures = $('#pointcutMethodSignatures_' + pointcutNum);
          $pointcutMethodSignatures.html('<div class="radio"><input type="radio" checked="checked">'
              + 'all methods with the above name</input></div>');
          $pointcutMethodSignatures.data('signatures',
              [
               {
                 all: true,
                 argTypeNames: [ '..' ],
                 returnTypeName: '',
                 modifiers: []
               }
             ]);
          var span = $('#pointcutCaptureSpan_' + pointcutNum).is(':checked');
          var trace = $('#pointcutCaptureTrace_' + pointcutNum).is(':checked');
          if (span || trace) {
            updateSpanTemplate();
          }
        } else {
          matchingMethods(methodName);
        }
      }
      return methodName;
    }

    function updateSectionHiding() {
      var metric = $('#pointcutCaptureMetric_' + pointcutNum).is(':checked');
      var span = $('#pointcutCaptureSpan_' + pointcutNum).is(':checked');
      var trace = $('#pointcutCaptureTrace_' + pointcutNum).is(':checked');
      var $pointcutMetricSection = $('#pointcutMetricSection_' + pointcutNum);
      if (metric) {
        $pointcutMetricSection.removeClass('hide');
      } else {
        $pointcutMetricSection.addClass('hide');
      }
      var $pointcutSpanSection = $('#pointcutSpanSection_' + pointcutNum);
      if (span || trace) {
        $pointcutSpanSection.removeClass('hide');
      } else {
        $pointcutSpanSection.addClass('hide');
      }
      var $pointcutSpanTemplate = $('#pointcutSpanTemplate_' + pointcutNum);
      if ((span || trace) && $pointcutSpanTemplate.val() === '') {
        // populate default template value on selecting span/trace
        updateSpanTemplate();
      }
      if (!span && !trace) {
        // clear template value on de-selecting span/trace
        $pointcutSpanTemplate.val('');
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
      if (selectedMethodSignature.val() === 'all') {
        return {
          all: true,
          argTypeNames: [ '..' ],
          returnTypeName: '',
          modifiers: []
        };
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
      var updatedPointcut = {
        'captureItems': captureItems,
        'typeName': $('#pointcutTypeName_' + pointcutNum).val(),
        'methodName': $('#pointcutMethodName_' + pointcutNum).val(),
        'methodArgTypeNames': signature.argTypeNames,
        'methodReturnTypeName': signature.returnTypeName,
        'methodModifiers': signature.modifiers,
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
        var somethingChanged = (response !== pointcut.version);
        pointcut = updatedPointcut;
        pointcut.version = response;
        fixLabels();
        if (somethingChanged) {
          // don't display button if save is performed without any changes (i.e. therefore version
          // hash is still the same)
          $('#retransformClassesButton').removeClass('hide');
        }
      });
    }

    function fixLabels() {
      var $pointcutHeader = $('#pointcutHeader_' + pointcutNum);
      var $pointcutSaveButton = $('#pointcutSaveButton_' + pointcutNum);
      var $pointcutSaveComplete = $('#pointcutSaveComplete_' + pointcutNum);
      if (pointcut.version) {
        $pointcutHeader.text(pointcut.typeName + '.' + pointcut.methodName
            + '(' + pointcut.methodArgTypeNames.join(', ') + ')');
        $pointcutSaveButton.text('Save');
        $pointcutSaveComplete.text('Saved');
      } else {
        $pointcutHeader.text('<New Pointcut>');
        $pointcutSaveButton.text('Add');
        $pointcutSaveComplete.text('Added');
      }
    }

    function addBehavior() {
      $('#pointcutCaptureMetric_' + pointcutNum).click(updateSectionHiding);
      $('#pointcutCaptureSpan_' + pointcutNum).click(updateSectionHiding);
      $('#pointcutCaptureTrace_' + pointcutNum).click(updateSectionHiding);
      var $pointcutTypeName = $('#pointcutTypeName_' + pointcutNum);
      $pointcutTypeName.typeahead({ source: matchingTypeNames });
      // important to bind typeahead event handler before change handler below since handler logic
      // relies on it running first
      var $pointcutMethodName = $('#pointcutMethodName_' + pointcutNum);
      $pointcutMethodName.typeahead({
        source: matchingMethodNames,
        updater: selectMethodName
      });
      $pointcutTypeName.change(function () {
        // check if the value has really changed (e.g. that a user didn't start altering text and
        // then changed mind and put the previous value back)
        var $this = $(this);
        if ($this.val() !== $this.data('value')) {
          $this.data('value', $this.val());
          $pointcutMethodName.val('');
          $('#pointcutMethodSignatures_' + pointcutNum).html('');
          $('#pointcutSpanTemplate_' + pointcutNum).val('');
        }
      });
      $pointcutMethodName.change(function () {
        // just in case user types in a value and doesn't select from typeahead
        // but this also gets called if user selects typeahead with mouse (when the field loses
        // focus, before the typeahead gains input control)
        // so delay this action so that it runs after the typeahead in this case, at which time
        // $pointcutMethodName.val() will be the value selected in the typeahead instead of the
        // the partial value that the user typed
        setTimeout(function () {
          selectMethodName($pointcutMethodName.val());
        }, 250);
      });
      $('#pointcutSaveButton_' + pointcutNum).click(function () {
        savePointcut();
      });
      $('#pointcutDeleteButton_' + pointcutNum).click(function () {
        var $pointcutForm = $('#pointcutForm_' + pointcutNum);
        if (pointcut.version) {
          $.post('/config/pointcut/-', JSON.stringify(pointcut.version), function () {
            // collapsing using accordion function, then removing completely
            $pointcutForm.one('hidden', function () {
              $('#pointcut_' + pointcutNum).remove();
            });
            $pointcutForm.collapse('hide');
            $('#retransformClassesButton').removeClass('hide');
          });
        } else {
          // collapsing using accordion function, then removing completely
          $pointcutForm.one('hidden', function () {
            $('#pointcut_' + pointcutNum).remove();
          });
          $pointcutForm.collapse('hide');
        }
      });
    }

    function addData() {
      $('#pointcutTypeName_' + pointcutNum).val(pointcut.typeName);
      $('#pointcutMethodName_' + pointcutNum).val(pointcut.methodName);
      var $pointcutMethodSignatures = $('#pointcutMethodSignatures_' + pointcutNum);
      if (pointcut.methodArgTypeNames.length === 1 && pointcut.methodArgTypeNames[0] === '..'
          && pointcut.methodModifiers.length === 0 && pointcut.methodReturnTypeName === '') {
        $pointcutMethodSignatures.html('<div class="radio"><input type="radio" checked="checked">'
            + 'all methods with the above name</input></div>');
      } else {
        $pointcutMethodSignatures.html('<div class="radio"><input type="radio" checked="checked">'
            + pointcut.methodModifiers.join(' ') + ' ' + pointcut.methodReturnTypeName + ' '
            + pointcut.methodName + '(' + pointcut.methodArgTypeNames.join(', ')
            + ')</input></div>');
      }
      $pointcutMethodSignatures.data('signatures',
          [
           {
             name: pointcut.methodName,
             argTypeNames: pointcut.methodArgTypeNames,
             returnTypeName: pointcut.methodReturnTypeName,
             modifiers: pointcut.methodModifiers
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
      $('#pointcutMetricName_' + pointcutNum).val(pointcut.metricName);
      $('#pointcutSpanTemplate_' + pointcutNum).val(pointcut.spanTemplate);
      updateSectionHiding();
    }

    $('#pointcutAccordion').append(pointcutTemplate({ num: pointcutNum }));
    fixLabels();
    addBehavior();
    if (pointcut.version) {
      addData();
    } else {
      // display form immediately for new pointcut
      $('#pointcutToggle_' + pointcutNum).trigger('click');
      $('#pointcutTypeName_' + pointcutNum).focus();
    }
  }

  $(document).ready(function () {
    Informant.configureAjaxError();
    read();
    $('#pointcutNewButton button').click(function () {
      applyPointcutEditTemplate({});
    });
    var postingRetransformClasses = false;
    $('#retransformClassesButton').click(function () {
      // handle crazy user clicking on the button
      if (postingRetransformClasses) {
        return;
      }
      postingRetransformClasses = true;
      $.post('admin/pointcut/retransform-classes', function () {
        postingRetransformClasses = false;
        Informant.hideSpinner('#retransformClassesSpinner');
        Informant.showAndFadeSuccessMessage('#retransformClassesSuccessMessage');
      });
      // in case button is clicked again before success message is hidden
      $('#retransformClassesSuccessMessage').addClass('hide');
      Informant.showSpinner('#retransformClassesSpinner');
      $('#retransformClassesButton').addClass('hide');
    });
  });
});
