/*
 * Copyright 2012-2019 the original author or authors.
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

/* global glowroot, angular, gtClipboard */

glowroot.controller('ConfigInstrumentationCtrl', [
  '$scope',
  '$location',
  '$http',
  '$rootScope',
  '$timeout',
  'confirmIfHasChanges',
  'httpErrors',
  'queryStrings',
  'modals',
  'instrumentationExport',
  function ($scope, $location, $http, $rootScope, $timeout, confirmIfHasChanges, httpErrors, queryStrings, modals, instrumentationExport) {

    var version = $location.search().v;

    var onBlurDebouncer;

    function onNewData(data) {
      $scope.config = data.config;
      $scope.originalConfig = angular.copy(data.config);

      function simpleName(className) {
        var splitIndex = className.lastIndexOf('.');
        if (splitIndex !== -1) {
          return className.substring(splitIndex + 1);
        }
        return className;
      }

      if (data.config.className) {
        $scope.heading = simpleName(data.config.className) + '::' + data.config.methodName;
        var captureKind = data.config.captureKind;
        $scope.selectedClassName = data.config.className;
        $scope.selectedMethodName = data.config.methodName;
        $scope.methodSignatures = data.methodSignatures || [];
        $scope.captureKindTransaction = captureKind === 'transaction';
        $scope.showTraceEntry = captureKind === 'trace-entry' || captureKind === 'transaction';
        $scope.showTraceEntryStackThreshold = captureKind === 'trace-entry';
        $scope.showTimer = captureKind === 'timer' || captureKind === 'trace-entry' || captureKind === 'transaction';

        $scope.methodSignatures.unshift({
          name: $scope.config.methodName,
          parameterTypes: ['..'],
          returnType: '',
          modifiers: []
        });

        for (var i = 0; i < $scope.methodSignatures.length; i++) {
          if (angular.equals($scope.methodSignatures[i].parameterTypes, $scope.config.methodParameterTypes)) {
            $scope.selectedMethodSignature = $scope.methodSignatures[i];
            break;
          }
        }
        if (!$scope.selectedMethodSignature) {
          $scope.selectedMethodSignature = {
            name: $scope.config.methodName,
            parameterTypes: $scope.config.methodParameterTypes,
            returnType: '',
            modifiers: [],
            notAvailable: true
          };
          $scope.methodSignatures.push($scope.selectedMethodSignature);
        }
      } else {
        $scope.heading = '<New>';
      }
    }

    var NEW_CONFIG = {
      // when these are updated, make sure to update similar list in importFromJson
      // (see instrumentation-list.js)
      classAnnotation: '',
      subTypeRestriction: '',
      superTypeRestriction: '',
      methodAnnotation: '',
      nestingGroup: '',
      order: 0,
      captureKind: 'transaction',
      alreadyInTransactionBehavior: 'capture-trace-entry',
      transactionType: '',
      transactionNameTemplate: '',
      transactionUserTemplate: '',
      transactionOuter: false,
      traceEntryMessageTemplate: '',
      traceEntryCaptureSelfNested: false,
      timerName: '',
      enabledProperty: '',
      traceEntryEnabledProperty: ''
    };

    if (version) {
      $http.get('backend/config/instrumentation?agent-id=' + encodeURIComponent($scope.agentId) + '&version=' + version)
          .then(function (response) {
            $scope.loaded = true;
            $scope.agentNotConnected = response.data.agentNotConnected;
            onNewData(response.data);
          }, function (response) {
            httpErrors.handle(response);
          });
    } else if ($scope.layout.central) {
      $http.get('backend/config/new-instrumentation-check-agent-connected?agent-id=' + encodeURIComponent($scope.agentId))
          .then(function (response) {
            $scope.loaded = true;
            $scope.agentNotConnected = !response.data;
            resetMethodSignatures('');
            onNewData({
              config: NEW_CONFIG
            });
          }, function (response) {
            httpErrors.handle(response);
          });
    } else {
      $scope.loaded = true;
      $scope.agentNotConnected = false;
      resetMethodSignatures('');
      onNewData({
        config: NEW_CONFIG
      });
    }

    $scope.hasChanges = function () {
      return $scope.config && ($scope.config.className || $scope.originalConfig.className)
          && !angular.equals($scope.config, $scope.originalConfig);
    };
    var removeConfirmIfHasChangesListener = $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    $scope.showClassNameSpinner = 0;
    $scope.showMethodNameSpinner = 0;

    $scope.classNames = function (suggestion) {
      if ($scope.agentNotConnected) {
        return [];
      }
      var queryData = {
        agentId: $scope.agentId,
        partialClassName: suggestion,
        limit: 10
      };
      $scope.showClassNameSpinner++;
      // using 'then' method to return promise
      return $http.get('backend/config/matching-class-names' + queryStrings.encodeObject(queryData))
          .then(function (response) {
            $scope.showClassNameSpinner--;
            return response.data;
          }, function (response) {
            $scope.showClassNameSpinner--;
            httpErrors.handle(response);
          });
    };

    $scope.onSelectClassName = function () {
      var className = $scope.config.className;
      // check if the value has really changed (e.g. that a user didn't start altering text and
      // then changed mind and put the previous value back)
      if (className !== $scope.selectedClassName) {
        $scope.selectedClassName = className;
        $scope.selectedMethodName = '';
        resetMethodSignatures('');
        $scope.config.methodName = '';
      }
    };

    $scope.methodNames = function (suggestion) {
      if ($scope.agentNotConnected) {
        return [];
      }
      if (!$scope.config.className) {
        return [];
      }
      if (suggestion.indexOf('*') !== -1 || suggestion.indexOf('|') !== -1) {
        return [suggestion];
      }
      var queryData = {
        agentId: $scope.agentId,
        className: $scope.config.className,
        partialMethodName: suggestion,
        limit: 10
      };
      $scope.showMethodNameSpinner++;
      // using 'then' method to return promise
      return $http.get('backend/config/matching-method-names' + queryStrings.encodeObject(queryData))
          .then(function (response) {
            $scope.showMethodNameSpinner--;
            return response.data;
          }, function (response) {
            $scope.showMethodNameSpinner--;
            httpErrors.handle(response);
          });
    };

    $scope.onSelectMethodName = function () {
      $timeout.cancel(onBlurDebouncer);
      var methodName = $scope.config.methodName;

      // since matchingMethods clears the trace entry text, check here if the value has really changed
      // (e.g. that a user didn't start altering text and then changed mind and put the previous
      // value back)

      // also, this condition is needed in case where user enters a value and clicks outside of the input field
      // to set the value to something that is not available in the dropdown

      if (methodName !== $scope.selectedMethodName) {
        $scope.selectedMethodName = methodName;
        if (methodName.indexOf('*') !== -1) {
          $scope.methodSignatures = [
            {
              name: methodName,
              parameterTypes: ['..'],
              returnType: '',
              modifiers: []
            }
          ];
          $scope.selectedMethodSignature = $scope.methodSignatures[0];
        } else {
          matchingMethods(methodName);
        }
      }
    };

    function resetMethodSignatures(methodName) {
      $scope.methodSignatures = [{
        name: methodName,
        parameterTypes: ['..'],
        returnType: '',
        modifiers: []
      }];
      $scope.selectedMethodSignature = $scope.methodSignatures[0];
    }

    $scope.onBlurMethodName = function () {
      if (!$scope.config.className) {
        return;
      }
      if ($scope.config.methodName) {
        onBlurDebouncer = $timeout(function () {
          $scope.onSelectMethodName();
        }, 100);
      } else {
        // the user cleared the text input and tabbed away
        resetMethodSignatures('');
      }
    };

    $scope.methodSignatureText = function (methodSignature) {
      if (methodSignature === undefined || methodSignature.name === undefined
          || methodSignature.name.indexOf('*') !== -1 || methodSignature.name.indexOf('|') !== -1
          || isSignatureAll(methodSignature)) {
        return 'any signature';
      }
      var text = '';
      var i;
      if (methodSignature.modifiers) {
        for (i = 0; i < methodSignature.modifiers.length; i++) {
          text += methodSignature.modifiers[i] + ' ';
        }
      }
      text += methodSignature.returnType + ' ' + methodSignature.name + '(';
      if (methodSignature.parameterTypes) {
        for (i = 0; i < methodSignature.parameterTypes.length; i++) {
          if (i > 0) {
            text += ', ';
          }
          text += methodSignature.parameterTypes[i];
        }
      }
      text += ')';
      return text;
    };

    $scope.transactionTypes = function (suggestion) {
      if ($scope.agentNotConnected) {
        return [];
      }
      if (!$scope.config.transactionType) {
        return [];
      }
      return $scope.agentRollup.transactionTypes.filter(function (tt) {
        return tt.toLowerCase().includes(suggestion.toLowerCase());
      });
    };

    $scope.save = function (deferred) {
      var postData = angular.copy($scope.config);
      var url;
      if (version) {
        url = 'backend/config/instrumentation/update';
      } else {
        url = 'backend/config/instrumentation/add';
      }
      var agentId = $scope.agentId;
      $http.post(url + '?agent-id=' + encodeURIComponent(agentId), postData)
          .then(function (response) {
            onNewData(response.data);
            deferred.resolve(version ? 'Saved' : 'Added');
            version = response.data.config.version;
            // fix current url (with updated version) before returning to list page in case back button is used later
            if (agentId) {
              $location.search({'agent-id': agentId, v: version}).replace();
            } else {
              $location.search({v: version}).replace();
            }
          }, function (response) {
            httpErrors.handle(response, deferred);
          });
    };

    $scope.delete = function (deferred) {
      var postData = {
        versions: [
          $scope.config.version
        ]
      };
      var agentId = $scope.agentId;
      $http.post('backend/config/instrumentation/remove?agent-id=' + encodeURIComponent(agentId), postData)
          .then(function () {
            removeConfirmIfHasChangesListener();
            if (agentId) {
              $location.url('config/instrumentation-list?agent-id=' + encodeURIComponent(agentId)).replace();
            } else {
              $location.url('config/instrumentation-list').replace();
            }
          }, function (response) {
            httpErrors.handle(response, deferred);
          });
    };

    $scope.exportToJson = function (deferred) {
      deferred.resolve();
      var config = angular.copy($scope.config);
      instrumentationExport.clean(config);
      $scope.jsonExport = JSON.stringify(config, null, 2);

      gtClipboard('#jsonExportModal .fa-clipboard', '#jsonExportModal', function () {
        return $scope.jsonExport;
      });

      // need to wait to display jsonExport until after jsonExport gets bound to the dom
      // otherwise vertical centering won't work correctly
      $timeout(function () {
        modals.display('#jsonExportModal', true);
      });
    };

    function matchingMethods(methodName) {
      if ($scope.agentNotConnected) {
        resetMethodSignatures(methodName);
        return;
      }
      var queryData = {
        agentId: $scope.agentId,
        className: $scope.config.className,
        methodName: methodName
      };
      $http.get('backend/config/method-signatures' + queryStrings.encodeObject(queryData))
          .then(function (response) {
            resetMethodSignatures(methodName);
            $scope.methodSignatures = response.data;
            $scope.methodSignatures.unshift({
              name: methodName,
              parameterTypes: ['..'],
              returnType: '',
              modifiers: []
            });
            $scope.selectedMethodSignature = $scope.methodSignatures[0];
          }, function (response) {
            httpErrors.handle(response);
          });
    }

    $scope.$watchGroup(['config.captureKind', 'config.alreadyInTransactionBehavior'], function (newValue) {
      if (!$scope.config) {
        return;
      }
      var newCaptureKind = newValue[0];
      var newAlreadyInTransactionBehavior = newValue[1];
      $scope.captureKindTransaction = newCaptureKind === 'transaction';
      $scope.showTimer = newCaptureKind === 'timer' || newCaptureKind === 'trace-entry' || newCaptureKind === 'transaction';
      $scope.showTraceEntry = newCaptureKind === 'trace-entry' || newCaptureKind === 'transaction';
      $scope.showTraceEntryStackThresholdMillis = newCaptureKind === 'trace-entry'
          || (newCaptureKind === 'transaction' && newAlreadyInTransactionBehavior !== 'do-nothing');
      $scope.showTraceEntryCaptureSelfNested = newCaptureKind === 'trace-entry'
          || (newCaptureKind === 'transaction' && newAlreadyInTransactionBehavior === 'capture-trace-entry');
      if (!$scope.showTimer) {
        $scope.config.timerName = '';
      }
      if (!$scope.showTraceEntry) {
        $scope.config.traceEntryMessageTemplate = '';
      }
      if (!$scope.showTraceEntry) {
        $scope.config.traceEntryCaptureSelfNested = false;
        $scope.config.traceEntryStackThresholdMillis = null;
      }
      if (!$scope.captureKindTransaction) {
        $scope.config.alreadyInTransactionBehavior = null;
      } else if (!$scope.config.alreadyInTransactionBehavior) {
        $scope.config.alreadyInTransactionBehavior = 'capture-trace-entry';
      }
    });

    $scope.$watch('selectedMethodSignature', function (newValue, oldValue) {
      if (newValue !== oldValue) {
        if (newValue) {
          $scope.config.methodParameterTypes = newValue.parameterTypes;
          $scope.config.methodReturnType = '';
          $scope.config.methodModifiers = [];
        } else {
          $scope.config.methodParameterTypes = [];
          $scope.config.methodReturnType = '';
          $scope.config.methodModifiers = [];
        }
      }
    });

    function isSignatureAll(methodSignature) {
      return methodSignature.modifiers.length === 0 && methodSignature.returnType === ''
          && methodSignature.parameterTypes.length === 1 && methodSignature.parameterTypes[0] === '..';
    }
  }
]);
