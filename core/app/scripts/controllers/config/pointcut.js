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

/* global glowroot, angular */

glowroot.controller('PointcutCtrl', [
  '$scope',
  '$http',
  '$timeout',
  'confirmIfHasChanges',
  'httpErrors',
  'queryStrings',
  'conversions',
  function ($scope, $http, $timeout, confirmIfHasChanges, httpErrors, queryStrings, conversions) {
    // don't initialize page binding object since it is inherited from pointcut-list.js

    function onNewData(data) {
      $scope.config = data;
      $scope.originalConfig = angular.copy(data);

      if (data.typeName) {
        var signature = {
          name: data.methodName,
          argTypeNames: data.methodArgTypeNames,
          returnTypeName: data.methodReturnTypeName,
          modifiers: data.methodModifiers
        };
        $scope.heading = data.typeName + '.' + data.methodName + '(' + data.methodArgTypeNames.join(', ') + ')';
        $scope.selectedSignature = signature;
        $scope.signatures = [ signature ];
        $scope.spanDefinition = Boolean(data.spanText);
        $scope.traceDefinition = Boolean(data.transactionName);
        $scope.spanStackTraceThresholdMillis = data.spanStackTraceThresholdMillis;
      } else {
        $scope.heading = '<New pointcut>';
        $scope.spanDefinition = false;
        $scope.traceDefinition = false;
        $timeout(function () {
          // focus on type name
          $scope.isFocus = true;
        }, 0);
      }
    }

    onNewData($scope.pointcut.config);

    $scope.hasChanges = function () {
      return !angular.equals($scope.config, $scope.originalConfig);
    };
    $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    $scope.typeNames = function (suggestion) {
      var postData = {
        partialTypeName: suggestion,
        limit: 10
      };
      // use 'then' method to return promise
      return $http.get('backend/pointcut/matching-type-names?' + queryStrings.encodeObject(postData))
          .then(function (response) {
            return response.data;
          }, function () {
            // TODO handle error
          });
    };

    $scope.onSelectTypeName = function () {
      var typeName = $scope.config.typeName;
      // check if the value has really changed (e.g. that a user didn't start altering text and
      // then changed mind and put the previous value back)
      if (typeName !== $scope.selectedTypeName) {
        $scope.selectedTypeName = typeName;
        $scope.selectedMethodName = '';
        $scope.signatures = [];
        $scope.config.methodName = '';
      }
    };

    $scope.methodNames = function (suggestion) {
      if (suggestion.indexOf('*') !== -1) {
        return [ suggestion ];
      }
      var postData = {
        typeName: $scope.config.typeName,
        partialMethodName: suggestion,
        limit: 10
      };
      return $http.get('backend/pointcut/matching-method-names?' + queryStrings.encodeObject(postData))
          .then(function (response) {
            return response.data;
          }, function () {
            // TODO handle error
          });
    };

    $scope.onSelectMethodName = function () {
      var methodName = $scope.config.methodName;

      // since matchingMethods clears the span text, check here if the value has really changed
      // (e.g. that a user didn't start altering text and then changed mind and put the previous
      // value back)

      // also, this condition is needed in case where user enters a value and clicks outside of the input field
      // to set the value to something that is not available in the dropdown

      if (methodName !== $scope.selectedMethodName) {
        $scope.selectedMethodName = methodName;
        if (methodName === undefined) {
          // this can happen if user clears the text input and tabs away (onSelectMethodName is called on blur)
          $scope.signatures = [];
          $scope.selectedSignature = undefined;
        } else if (methodName.indexOf('*') !== -1) {
          $scope.signatures = [
            {
              name: methodName,
              argTypeNames: [ '..' ],
              returnTypeName: '',
              modifiers: []
            }
          ];
          $scope.selectedSignature = $scope.signatures[0];
        } else {
          matchingMethods(methodName);
        }
      }
    };

    $scope.signatureText = function (signature) {
      if (isSignatureAll(signature)) {
        return 'all methods with the above name';
      }
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
    };

    $scope.showError = function (name) {
      return $scope.formCtrl[name].$invalid;
    };

    // TODO this needs access to outer scope update retransformClassesButton
    $scope.pointcutSave = function (deferred) {
      var signature = $scope.selectedSignature;
      if (!signature) {
        deferred.reject('method for pointcut must be selected');
        return;
      }
      var postData = angular.copy($scope.config);
      delete postData.version;
      postData.methodArgTypeNames = signature.argTypeNames;
      postData.methodReturnTypeName = signature.returnTypeName;
      postData.methodModifiers = signature.modifiers;
      var url;
      var version = $scope.config.version;
      if (version) {
        url = 'backend/config/pointcut/' + version;
      } else {
        url = 'backend/config/pointcut/+';
      }
      $http.post(url, postData)
          .success(function (data) {
            onNewData(data);
            $scope.page.dirty = true;
            deferred.resolve(version ? 'Saved' : 'Added');
          })
          .error(httpErrors.handler($scope, deferred));
    };

    // TODO this needs access to outer scope remove pointcut and update retransformClassesButton
    $scope.pointcutDelete = function (deferred) {
      if ($scope.config.version) {
        $http.post('backend/config/pointcut/-', '"' + $scope.config.version + '"')
            .success(function (data) {
              $scope.$parent.removePointcut($scope.pointcut);
              $scope.page.dirty = true;
              deferred.resolve('Deleted');
            })
            .error(httpErrors.handler($scope, deferred));
      } else {
        $scope.$parent.removePointcut($scope.pointcut);
        deferred.resolve('Deleted');
      }
    };

    function matchingMethods(methodName) {
      var postData = {
        typeName: $scope.config.typeName,
        methodName: methodName
      };
      $scope.signaturesLoading = true;
      $http.get('backend/pointcut/matching-methods?' + queryStrings.encodeObject(postData))
          .success(function (data) {
            $scope.signaturesLoading = false;
            $scope.signatures = data;
            if (data.length !== 1) {
              // this includes the case where data.length === 0, which is possible if the user enters a
              // non-matching method name and clicks outside of the input field to bypass the typeahead values
              $scope.signatures.push({
                name: methodName,
                argTypeNames: [ '..' ],
                returnTypeName: '',
                modifiers: []
              });
            }
            if (data.length === 1) {
              $scope.selectedSignature = data[0];
            } else {
              $scope.selectedSignature = undefined;
            }
          })
          .error(httpErrors.handler($scope));
    }

    $scope.$watch('spanDefinition', function (newValue, oldValue) {
      if (newValue === oldValue) {
        // called due to watcher initialization
        return;
      }
      if (!newValue) {
        $scope.config.spanText = '';
        $scope.config.spanStackTraceThresholdMillis = '';
        $scope.config.spanIgnoreSameNested = '';
        $scope.traceDefinition = false;
      }
    });

    $scope.$watch('traceDefinition', function (newValue, oldValue) {
      if (newValue === oldValue) {
        // called due to watcher initialization
        return;
      }
      if (!newValue) {
        $scope.config.transactionName = '';
        $scope.config.background = '';
      }
    });

    $scope.$watch('spanStackTraceThresholdMillis', function (newValue) {
      $scope.config.spanStackTraceThresholdMillis = conversions.toNumber(newValue);
    });

    function isSignatureAll(signature) {
      return signature.modifiers.length === 0 && signature.returnTypeName === '' &&
          signature.argTypeNames.length === 1 && signature.argTypeNames[0] === '..';
    }
  }
]);
