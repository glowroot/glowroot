/*
 * Copyright 2016-2017 the original author or authors.
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

glowroot.factory('instrumentationExport', [
  function () {
    function clean(config) {
      delete config.version;
      if (!config.classAnnotation) {
        delete config.classAnnotation;
      }
      if (!config.subTypeRestriction) {
        delete config.subTypeRestriction;
      }
      if (!config.superTypeRestriction) {
        delete config.superTypeRestriction;
      }
      if (!config.methodAnnotation) {
        delete config.methodAnnotation;
      }
      if (!config.methodReturnType) {
        delete config.methodReturnType;
      }
      if (!config.methodModifiers.length) {
        delete config.methodModifiers;
      }
      if (!config.nestingGroup) {
        delete config.nestingGroup;
      }
      if (!config.order) {
        delete config.order;
      }
      if (!config.transactionType) {
        delete config.transactionType;
      }
      if (!config.transactionNameTemplate) {
        delete config.transactionNameTemplate;
      }
      if (!config.transactionUserTemplate) {
        delete config.transactionUserTemplate;
      }
      if (!config.transactionAttributeTemplates || !Object.keys(config.transactionAttributeTemplates).length) {
        delete config.transactionAttributeTemplates;
      }
      if (config.transactionSlowThresholdMillis === null) {
        delete config.transactionSlowThresholdMillis;
      }
      if (!config.transactionOuter) {
        delete config.transactionOuter;
      }
      if (!config.traceEntryMessageTemplate) {
        delete config.traceEntryMessageTemplate;
      }
      if (config.traceEntryStackThresholdMillis === null) {
        delete config.traceEntryStackThresholdMillis;
      }
      if (!config.traceEntryCaptureSelfNested) {
        delete config.traceEntryCaptureSelfNested;
      }
      if (!config.timerName) {
        delete config.timerName;
      }
      if (!config.enabledProperty) {
        delete config.enabledProperty;
      }
      if (!config.traceEntryEnabledProperty) {
        delete config.traceEntryEnabledProperty;
      }
    }

    return {
      clean: clean
    };
  }
]);
