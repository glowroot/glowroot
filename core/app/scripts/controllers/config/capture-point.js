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

/* global glowroot, angular */

glowroot.controller('ConfigCapturePointCtrl', [
  '$scope',
  '$location',
  '$http',
  '$rootScope',
  '$timeout',
  'confirmIfHasChanges',
  'httpErrors',
  'queryStrings',
  'conversions',
  function ($scope, $location, $http, $rootScope, $timeout, confirmIfHasChanges, httpErrors, queryStrings) {

    var version = $location.search().v;

    function onNewData(data) {
      $scope.config = data;
      $scope.originalConfig = angular.copy(data);

      function simpleName(className) {
        var splitIndex = className.lastIndexOf('.');
        if (splitIndex !== -1) {
          return className.substring(splitIndex + 1);
        }
        return className;
      }

      if (data.className) {
        $scope.heading = simpleName(data.className) + '::' + data.methodName;
        var methodSignature = {
          name: data.methodName,
          parameterTypes: data.methodParameterTypes,
          returnType: data.methodReturnType,
          modifiers: data.methodModifiers
        };
        var captureKind = data.captureKind;
        $scope.selectedClassName = data.className;
        $scope.selectedMethodName = data.methodName;
        $scope.selectedMethodSignature = methodSignature;
        $scope.methodSignatures = [methodSignature];
        $scope.captureKindTransaction = captureKind === 'transaction';
        $scope.showMetric = captureKind === 'metric' || captureKind === 'trace-entry' || captureKind === 'transaction';
        $scope.showTraceEntry = captureKind === 'trace-entry' || captureKind === 'transaction';
        $scope.showTraceEntryStackThreshold = captureKind === 'trace-entry';
        $scope.loadMethodSignatures = true;
      } else {
        $scope.heading = '<New capture point>';
        $timeout(function () {
          // focus on class name
          $scope.isFocus = true;
        }, 0);
      }
    }

    if (version) {
      $http.get('backend/config/capture-points/' + version)
          .success(function (data) {
            $scope.loaded = true;
            onNewData(data);
          })
          .error(httpErrors.handler($scope));
    } else {
      $scope.loaded = true;
      onNewData({
        config: {
          captureKind: 'metric'
        }
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
      if (suggestion.indexOf('*') !== -1) {
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
      var methodName = $scope.config.methodName;

      // since matchingMethods clears the trace entry text, check here if the value has really changed
      // (e.g. that a user didn't start altering text and then changed mind and put the previous
      // value back)

      // also, this condition is needed in case where user enters a value and clicks outside of the input field
      // to set the value to something that is not available in the dropdown

      if (methodName !== $scope.selectedMethodName) {
        $scope.loadMethodSignatures = false;
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
      } else if ($scope.loadMethodSignatures) {
        $scope.loadMethodSignatures = false;
        matchingMethods(methodName, true);
      }
    };

    $scope.onBlurMethodName = function () {
      if ($scope.config.methodName) {
        $scope.onSelectMethodName();
      } else {
        // the user cleared the text input and tabbed away
        $scope.methodSignatures = [];
        $scope.selectedMethodSignature = undefined;
      }
    };

    $scope.methodSignatureText = function (methodSignature) {
      if (methodSignature.name.indexOf('*') !== -1 || methodSignature.name.indexOf('|') !== -1) {
        return 'match any signature';
      }
      if (isSignatureAll(methodSignature)) {
        return 'match any signature';
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
      var url;
      if (version) {
        url = 'backend/config/capture-points/update';
      } else {
        url = 'backend/config/capture-points/add';
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
                $location.url('/config/capture-point-list');
              });
            });
          })
          .error(httpErrors.handler($scope, deferred));
    };

    $scope.delete = function (deferred) {
      $http.post('backend/config/capture-points/remove', '"' + $scope.config.version + '"')
          .success(function () {
            removeConfirmIfHasChangesListener();
            $location.url('/config/capture-point-list').replace();
          })
          .error(httpErrors.handler($scope, deferred));
    };

    function matchingMethods(methodName, keepSelectedMethodSignature) {
      var queryData = {
        className: $scope.config.className,
        methodName: methodName
      };
      $scope.methodSignaturesLoading = true;
      $http.get('backend/config/method-signatures' + queryStrings.encodeObject(queryData))
          .success(function (data) {
            $scope.methodSignaturesLoading = false;
            $scope.methodSignatures = data;
            if (data.length !== 1) {
              // this includes the case where data.length === 0, which is possible if the user enters a
              // non-matching method name and clicks outside of the input field to bypass the typeahead values
              $scope.methodSignatures.push({
                name: methodName,
                parameterTypes: ['..'],
                returnType: '',
                modifiers: []
              });
            }
            if (keepSelectedMethodSignature) {
              for (var i = 0; i < data.length; i++) {
                if ($scope.selectedMethodSignature.name === data[i].name &&
                    angular.equals($scope.selectedMethodSignature.parameterTypes, data[i].parameterTypes)) {
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
          .error(function (data, status) {
            $scope.methodSignaturesLoading = false;
            httpErrors.handler($scope)(data, status);
          });
    }

    $scope.$watch('config.captureKind', function (newValue, oldValue) {
      if (newValue !== oldValue) {
        $scope.captureKindTransaction = newValue === 'transaction';
        $scope.showMetric = newValue === 'metric' || newValue === 'trace-entry' || newValue === 'transaction';
        $scope.showTraceEntry = newValue === 'trace-entry' || newValue === 'transaction';
        $scope.showTraceEntryStackThreshold = newValue === 'trace-entry';
        if (!$scope.showMetric) {
          $scope.config.metricName = '';
        }
        if (!$scope.showTraceEntry) {
          $scope.config.traceEntryTemplate = '';
          $scope.config.traceEntryCaptureSelfNested = false;
        }
        if (!$scope.showTraceEntryStackThreshold) {
          $scope.config.traceEntryStackThresholdMillis = '';
        }
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
      return methodSignature.modifiers.length === 0 && methodSignature.returnType === '' &&
          methodSignature.parameterTypes.length === 1 && methodSignature.parameterTypes[0] === '..';
    }
  }
]);
