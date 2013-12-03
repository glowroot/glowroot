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

/* global glowroot, angular */

glowroot.controller('PointcutCtrl', [
  '$scope',
  '$http',
  '$timeout',
  'confirmIfHasChanges',
  'httpErrors',
  function ($scope, $http, $timeout, confirmIfHasChanges, httpErrors) {
    // initialize page binding object
    $scope.page = {};

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
        $scope.page.selectedSignature = signature;
        $scope.signatures = [ signature ];
      } else {
        $scope.heading = '<New pointcut>';
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
      return $http.post('backend/pointcut/matching-type-names', postData)
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
        $scope.config.metricName = '';
        $scope.config.spanText = '';
        $scope.config.traceGrouping = '';
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
      return $http.post('backend/pointcut/matching-method-names', postData)
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
          $scope.page.selectedSignature = undefined;
        } else if (methodName.indexOf('*') !== -1) {
          $scope.signatures = [
            {
              name: methodName,
              argTypeNames: [ '..' ],
              returnTypeName: '',
              modifiers: []
            }
          ];
          $scope.page.selectedSignature = $scope.signatures[0];
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

    // TODO this needs access to outer scope update retransformClassesButton
    $scope.pointcutSave = function (deferred) {
      var signature = $scope.page.selectedSignature;
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
      $http.post('backend/pointcut/matching-methods', postData)
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
              $scope.page.selectedSignature = data[0];
            } else {
              $scope.page.selectedSignature = undefined;
            }
          })
          .error(httpErrors.handler($scope));
    }

    $scope.$watch('config.metric', function (newValue, oldValue) {
      if (newValue === oldValue) {
        // called due to watcher initialization
        return;
      }
      if (!newValue) {
        $scope.config.span = false;
        $scope.config.trace = false;
      }
    });

    $scope.$watch('config.span', function (newValue, oldValue) {
      if (newValue === oldValue) {
        // called due to watcher initialization
        return;
      }
      if (newValue) {
        initSpanText();
      } else {
        $scope.config.trace = false;
        $scope.config.spanText = '';
        $scope.config.traceGrouping = '';
      }
    });

    $scope.$watch('config.trace', function (newValue, oldValue) {
      if (newValue === oldValue) {
        // called due to watcher initialization
        return;
      }
      if (newValue) {
        initSpanText();
        initTraceGrouping();
      } else {
        $scope.config.traceGrouping = '';
      }
    });

    $scope.$watch('page.selectedSignature', function (newValue, oldValue) {
      if (newValue === oldValue) {
        // called due to watcher initialization
        return;
      }
      if ($scope.config.span) {
        initSpanText();
      }
      if ($scope.config.trace) {
        initTraceGrouping();
      }
    });

    function initSpanText() {
      var signature = $scope.page.selectedSignature;
      if (!signature) {
        // no radio button selected
        $scope.config.spanText = '';
        return;
      }
      var template;
      if (signature.modifiers.indexOf('abstract') !== -1) {
        template = '{{this.class.name}}.';
      } else {
        template = $scope.config.typeName + '.';
      }
      if (signature.name.indexOf('*') !== -1) {
        template += '{{methodName}}()';
        $scope.config.spanText = template;
        return;
      }
      template += signature.name + '()';
      if (isSignatureAll(signature)) {
        $scope.config.spanText = template;
        return;
      }
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
      $scope.config.spanText = template;
    }

    function isSignatureAll(signature) {
      return signature.modifiers.length === 0 && signature.returnTypeName === '' &&
          signature.argTypeNames.length === 1 && signature.argTypeNames[0] === '..';
    }

    function initTraceGrouping() {
      var signature = $scope.page.selectedSignature;
      if (!signature) {
        // no radio button selected
        return;
      }
      var grouping = $scope.config.typeName + '.' + signature.name + '()';
      $scope.config.traceGrouping = grouping;
    }
  }
]);
