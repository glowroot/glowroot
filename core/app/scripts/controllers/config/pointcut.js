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

      if (data.className) {
        var methodSignature = {
          name: data.methodName,
          parameterTypes: data.methodParameterTypes,
          returnType: data.methodReturnType,
          modifiers: data.methodModifiers
        };
        var adviceKind = data.adviceKind;
        $scope.selectedMethodName = data.methodName;
        $scope.selectedMethodSignature = methodSignature;
        $scope.methodSignatures = [ methodSignature ];
        $scope.metricOrGreater = adviceKind === 'metric' || adviceKind === 'span' || adviceKind === 'trace';
        $scope.spanOrGreater = adviceKind === 'span' || adviceKind === 'trace';
        $scope.span = adviceKind === 'span';
        $scope.trace = adviceKind === 'trace';
        $scope.loadMethodSignatures = true;
      } else {
        $timeout(function () {
          // focus on class name
          $scope.isFocus = true;
        }, 0);
      }
    }

    onNewData($scope.pointcut.config);

    $scope.hasChanges = function () {
      return ($scope.config.className || $scope.originalConfig.className) &&
          !angular.equals($scope.config, $scope.originalConfig);
    };
    $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    $scope.classNames = function (suggestion) {
      var postData = {
        partialClassName: suggestion,
        limit: 10
      };
      // use 'then' method to return promise
      return $http.get('backend/config/matching-class-names?' + queryStrings.encodeObject(postData))
          .then(function (response) {
            return response.data;
          }, function () {
            // TODO handle error
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
      if (suggestion.indexOf('*') !== -1) {
        return [ suggestion ];
      }
      var queryData = {
        className: $scope.config.className,
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
              parameterTypes: [ '..' ],
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
      if (methodSignature.name.indexOf('*') !== -1 || methodSignature.name.indexOf('|') !== -1) {
        return 'all methods matching the above name';
      }
      if (isSignatureAll(methodSignature)) {
        return 'all methods with the above name';
      }
      var text = '';
      var i;
      for (i = 0; i < methodSignature.modifiers.length; i++) {
        text += methodSignature.modifiers[i] + ' ';
      }
      text += methodSignature.returnType + ' ' + methodSignature.name + '(';
      for (i = 0; i < methodSignature.parameterTypes.length; i++) {
        if (i > 0) {
          text += ', ';
        }
        text += methodSignature.parameterTypes[i];
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
        className: $scope.config.className,
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
                parameterTypes: [ '..' ],
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

    $scope.$watch('config.adviceKind', function (value) {
      $scope.metricOrGreater = value === 'metric' || value === 'span' || value === 'trace';
      $scope.spanOrGreater = value === 'span' || value === 'trace';
      $scope.span = value === 'span';
      $scope.trace = value === 'trace';
      if (!$scope.metricOrGreater) {
        $scope.config.metricName = '';
      }
      if (!$scope.spanOrGreater) {
        $scope.config.messageTemplate = '';
        $scope.config.captureSelfNested = false;
      }
      if (!$scope.span) {
        $scope.config.stackTraceThresholdMillis = '';
      }
    });

    $scope.$watch('selectedMethodSignature', function (newValue) {
      if (newValue) {
        $scope.config.methodParameterTypes = newValue.parameterTypes;
        $scope.config.methodReturnType = newValue.returnType;
        $scope.config.methodModifiers = newValue.modifiers;
      } else {
        $scope.config.methodParameterTypes = '';
        $scope.config.methodReturnType = '';
        $scope.config.methodModifiers = '';
      }
    });

    function isSignatureAll(methodSignature) {
      return methodSignature.modifiers.length === 0 && methodSignature.returnType === '' &&
          methodSignature.parameterTypes.length === 1 && methodSignature.parameterTypes[0] === '..';
    }
  }
]);
