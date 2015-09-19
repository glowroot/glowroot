/*
 * Copyright 2012-2015 the original author or authors.
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
  function ($scope, $location, $http, $rootScope, $timeout, confirmIfHasChanges, httpErrors, queryStrings, modals) {

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
        $scope.showTimer = captureKind === 'timer' || captureKind === 'trace-entry' || captureKind === 'transaction';
        $scope.showTraceEntry = captureKind === 'trace-entry' || captureKind === 'transaction';
        $scope.showTraceEntryStackThreshold = captureKind === 'trace-entry';

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

    if (version) {
      $http.get('backend/config/instrumentation/' + version)
          .success(function (data) {
            $scope.loaded = true;
            onNewData(data);
          })
          .error(httpErrors.handler($scope));
    } else {
      $scope.loaded = true;
      onNewData({
        config: {
          captureKind: 'timer'
        }
      });
    }

    $scope.hasChanges = function () {
      return $scope.config && ($scope.config.className || $scope.originalConfig.className)
          && !angular.equals($scope.config, $scope.originalConfig);
    };
    var removeConfirmIfHasChangesListener = $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    $scope.hasMethodSignatureError = function () {
      return !$scope.methodSignatures || !$scope.methodSignatures.length
          || $scope.formCtrl.selectedMethodSignature.$invalid;
    };

    $scope.showClassNameSpinner = 0;
    $scope.showMethodNameSpinner = 0;

    $scope.classNames = function (suggestion) {
      var postData = {
        partialClassName: suggestion,
        limit: 10
      };
      $scope.showClassNameSpinner++;
      // using 'then' method to return promise
      return $http.get('backend/config/matching-class-names' + queryStrings.encodeObject(postData))
          .then(function (response) {
            $scope.showClassNameSpinner--;
            return response.data;
          }, function (data, status) {
            $scope.showClassNameSpinner--;
            httpErrors.handler($scope)(data, status);
          });
    };

    $scope.onSelectClassName = function () {
      var className = $scope.config.className;
      // check if the value has really changed (e.g. that a user didn't start altering text and
      // then changed mind and put the previous value back)
      if (className !== $scope.selectedClassName) {
        $scope.selectedClassName = className;
        $scope.selectedMethodName = '';
        $scope.methodSignatures = [];
        $scope.config.methodName = '';
      }
    };

    $scope.methodNames = function (suggestion) {
      if (!$scope.config.className) {
        return [];
      }
      if (suggestion.indexOf('*') !== -1 || suggestion.indexOf('|') !== -1) {
        return [suggestion];
      }
      var queryData = {
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
          }, function (data, status) {
            $scope.showMethodNameSpinner--;
            httpErrors.handler($scope)(data, status);
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

    $scope.onBlurMethodName = function () {
      if ($scope.config.methodName) {
        onBlurDebouncer = $timeout(function () {
          $scope.onSelectMethodName();
        }, 100);
      } else {
        // the user cleared the text input and tabbed away
        $scope.methodSignatures = [];
        $scope.selectedMethodSignature = undefined;
      }
    };

    $scope.methodSignatureText = function (methodSignature) {
      if (methodSignature.name.indexOf('*') !== -1 || methodSignature.name.indexOf('|') !== -1) {
        return 'any signature';
      }
      if (isSignatureAll(methodSignature)) {
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

    $scope.save = function (deferred) {
      var postData = angular.copy($scope.config);
      var url;
      if (version) {
        url = 'backend/config/instrumentation/update';
      } else {
        url = 'backend/config/instrumentation/add';
      }
      $http.post(url, postData)
          .success(function (data) {
            onNewData(data);
            deferred.resolve(version ? 'Saved' : 'Added');
            version = data.version;
            // fix current url (with updated version) before returning to list page in case back button is used later
            $timeout(function () {
              $location.search({v: version}).replace();
              $timeout(function () {
                $location.url('config/instrumentation-list');
              });
            });
          })
          .error(httpErrors.handler($scope, deferred));
    };

    $scope.delete = function (deferred) {
      $http.post('backend/config/instrumentation/remove', '"' + $scope.config.version + '"')
          .success(function () {
            removeConfirmIfHasChangesListener();
            $location.url('config/instrumentation-list').replace();
          })
          .error(httpErrors.handler($scope, deferred));
    };

    $scope.exportToJson = function () {
      var data = angular.copy($scope.config);
      delete data.version;
      if (!data.methodReturnType) {
        delete data.methodReturnType;
      }
      if (!data.methodModifiers.length) {
        delete data.methodModifiers;
      }
      if (!data.timerName) {
        delete data.timerName;
      }
      if (!data.traceEntryTemplate) {
        delete data.traceEntryTemplate;
      }
      if (!data.traceEntryStackThresholdMillis) {
        delete data.traceEntryStackThresholdMillis;
      }
      if (!data.traceEntryCaptureSelfNested) {
        delete data.traceEntryCaptureSelfNested;
      }
      if (!data.transactionType) {
        delete data.transactionType;
      }
      if (!data.transactionNameTemplate) {
        delete data.transactionNameTemplate;
      }
      if (!data.transactionSlowThresholdMillis) {
        delete data.transactionSlowThresholdMillis;
      }
      if (!data.transactionUserTemplate) {
        delete data.transactionUserTemplate;
      }
      if (!data.transactionCustomAttributeTemplates || !Object.keys(data.transactionCustomAttributeTemplates).length) {
        delete data.transactionCustomAttributeTemplates;
      }
      if (!data.enabledProperty) {
        delete data.enabledProperty;
      }
      if (!data.traceEntryEnabledProperty) {
        delete data.traceEntryEnabledProperty;
      }
      $scope.jsonExport = JSON.stringify(data, null, 2);

      gtClipboard('#jsonExportModal .fa-clipboard', function () {
        return document.getElementById('jsonExport');
      }, function () {
        return $scope.jsonExport;
      });

      // need to wait to display jsonExport until after jsonExport gets bound to the dom
      // otherwise vertical centering won't work correctly
      $timeout(function () {
        modals.display('#jsonExportModal', true);
      });
    };

    function matchingMethods(methodName) {
      var queryData = {
        className: $scope.config.className,
        methodName: methodName
      };
      $http.get('backend/config/method-signatures' + queryStrings.encodeObject(queryData))
          .success(function (data) {
            $scope.methodSignatures = data;
            $scope.methodSignatures.unshift({
              name: methodName,
              parameterTypes: ['..'],
              returnType: '',
              modifiers: []
            });
            if (data.length === 1) {
              $scope.selectedMethodSignature = data[0];
            } else {
              $scope.selectedMethodSignature = undefined;
            }
          })
          .error(function (data, status) {
            httpErrors.handler($scope)(data, status);
          });
    }

    $scope.$watch('config.captureKind', function (newValue) {
      if (!$scope.config) {
        return;
      }
      $scope.captureKindTransaction = newValue === 'transaction';
      $scope.showTimer = newValue === 'timer' || newValue === 'trace-entry' || newValue === 'transaction';
      $scope.showTraceEntry = newValue === 'trace-entry' || newValue === 'transaction';
      $scope.showTraceEntryStackThreshold = newValue === 'trace-entry';
      if (!$scope.showTimer) {
        $scope.config.timerName = '';
      }
      if (!$scope.showTraceEntry) {
        $scope.config.traceEntryTemplate = '';
        $scope.config.traceEntryCaptureSelfNested = false;
      }
      if (!$scope.showTraceEntryStackThreshold) {
        $scope.config.traceEntryStackThresholdMillis = '';
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
