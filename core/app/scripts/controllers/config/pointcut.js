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

glowroot.controller('ConfigPointcutCtrl', [
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

      if (data.type) {
        var methodSignature = {
          name: data.methodName,
          argTypes: data.methodArgTypes,
          returnType: data.methodReturnType,
          modifiers: data.methodModifiers
        };
        $scope.heading = data.type + '.' + data.methodName + '(' + data.methodArgTypes.join(', ') + ')';
        $scope.selectedMethodName = data.methodName;
        $scope.selectedMethodSignature = methodSignature;
        $scope.methodSignatures = [ methodSignature ];
        $scope.spanDefinition = Boolean(data.spanText);
        $scope.traceDefinition = Boolean(data.transactionName);
        $scope.spanStackTraceThresholdMillis = data.spanStackTraceThresholdMillis;
        $scope.loadMethodSignatures = true;
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
      return $scope.selectedMethodSignature && !angular.equals($scope.config, $scope.originalConfig);
    };
    $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    $scope.types = function (suggestion) {
      var postData = {
        partialTypeName: suggestion,
        limit: 10
      };
      // use 'then' method to return promise
      return $http.get('backend/config/matching-types?' + queryStrings.encodeObject(postData))
          .then(function (response) {
            return response.data;
          }, function () {
            // TODO handle error
          });
    };

    $scope.onSelectType = function () {
      var type = $scope.config.type;
      // check if the value has really changed (e.g. that a user didn't start altering text and
      // then changed mind and put the previous value back)
      if (type !== $scope.selectedType) {
        $scope.selectedType = type;
        $scope.selectedMethodName = '';
        $scope.methodSignatures = [];
        $scope.config.methodName = '';
      }
    };

    $scope.methodNames = function (suggestion) {
      if (suggestion.indexOf('*') !== -1) {
        return [ suggestion ];
      }
      var queryData = {
        type: $scope.config.type,
        partialMethodName: suggestion,
        limit: 10
      };
      return $http.get('backend/config/matching-method-names?' + queryStrings.encodeObject(queryData))
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
        $scope.loadMethodSignatures = false;
        $scope.selectedMethodName = methodName;
        if (methodName === undefined) {
          // this can happen if user clears the text input and tabs away (onSelectMethodName is called on blur)
          $scope.methodSignatures = [];
          $scope.selectedMethodSignature = undefined;
        } else if (methodName.indexOf('*') !== -1) {
          $scope.methodSignatures = [
            {
              name: methodName,
              argTypes: [ '..' ],
              returnType: '',
              modifiers: []
            }
          ];
          $scope.selectedMethodSignature = $scope.methodSignatures[0];
        } else {
          matchingMethods(methodName);
        }
      } else if ($scope.loadMethodSignatures) {
        $scope.loadMethodSignatures = false;
        matchingMethods(methodName, true);
      }
    };

    $scope.methodSignatureText = function (methodSignature) {
      if (isSignatureAll(methodSignature)) {
        return 'all methods with the above name';
      }
      var text = '';
      var i;
      for (i = 0; i < methodSignature.modifiers.length; i++) {
        text += methodSignature.modifiers[i] + ' ';
      }
      text += methodSignature.returnType + ' ' + methodSignature.name + '(';
      for (i = 0; i < methodSignature.argTypes.length; i++) {
        if (i > 0) {
          text += ', ';
        }
        text += methodSignature.argTypes[i];
      }
      text += ')';
      return text;
    };

    $scope.save = function (deferred) {
      var postData = angular.copy($scope.config);
      delete postData.version;
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

    $scope.delete = function (deferred) {
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

    function matchingMethods(methodName, keepSelectedMethodSignature) {
      var queryData = {
        type: $scope.config.type,
        methodName: methodName
      };
      $scope.methodSignaturesLoading = true;
      $http.get('backend/config/method-signatures?' + queryStrings.encodeObject(queryData))
          .success(function (data) {
            $scope.methodSignaturesLoading = false;
            $scope.methodSignatures = data;
            if (data.length !== 1) {
              // this includes the case where data.length === 0, which is possible if the user enters a
              // non-matching method name and clicks outside of the input field to bypass the typeahead values
              $scope.methodSignatures.push({
                name: methodName,
                argTypes: [ '..' ],
                returnType: '',
                modifiers: []
              });
            }
            if (keepSelectedMethodSignature) {
              for (var i = 0; i < data.length; i++) {
                if (angular.equals($scope.selectedMethodSignature, data[i])) {
                  $scope.selectedMethodSignature = data[i];
                  break;
                }
              }
            } else if (data.length === 1) {
              $scope.selectedMethodSignature = data[0];
            } else if (!keepSelectedMethodSignature) {
              $scope.selectedMethodSignature = undefined;
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

    $scope.$watch('selectedMethodSignature', function (newValue) {
      if (newValue) {
        $scope.config.methodArgTypes = newValue.argTypes;
        $scope.config.methodReturnType = newValue.returnType;
        $scope.config.methodModifiers = newValue.modifiers;
      } else {
        $scope.config.methodArgTypes = '';
        $scope.config.methodReturnType = '';
        $scope.config.methodModifiers = '';
      }
    });

    function isSignatureAll(methodSignature) {
      return methodSignature.modifiers.length === 0 && methodSignature.returnType === '' &&
          methodSignature.argTypes.length === 1 && methodSignature.argTypes[0] === '..';
    }
  }
]);
