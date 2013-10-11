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

/* global informant, Informant, angular */

informant.controller('ConfigAdhocPointcutListCtrl', [
  '$scope',
  '$http',
  '$timeout',
  function ($scope, $http, $timeout) {
    var spinner = Informant.showSpinner('#initialLoadSpinner');
    $http.get('backend/config/adhoc-pointcut-section')
        .success(function (data) {
          spinner.stop();
          $scope.pointcuts = [];
          var i;
          for (i = 0; i < data.configs.length; i++) {
            var config = data.configs[i];
            var signature = {
              name: config.methodName,
              argTypeNames: config.methodArgTypeNames,
              returnTypeName: config.methodReturnTypeName,
              modifiers: config.methodModifiers
            };
            $scope.pointcuts.push({
              heading: config.typeName + '.' + config.methodName +
                  '(' + config.methodArgTypeNames.join(', ') + ')',
              config: config,
              selectedSignature: signature,
              signatures: [ signature ],
              version: config.version
            });
            delete config.version;
          }
          // use object so dirty flag can be updated by child controllers
          $scope.data = {
            dirty: data.jvmOutOfSync
          };
          $scope.jvmRetransformClassesSupported = data.jvmRetransformClassesSupported;
          // this is to hide 'New adhoc pointcut' section until pointcuts are loaded
          // in order to prevent jitter of seeing that section very briefly before it gets pushed down by
          // existing adhoc pointcut sections
          $scope.loaded = true;
        })
        .error(function (error) {
          // TODO
        });

    $scope.addPointcut = function () {
      var pointcut = {
        heading: '<New Pointcut>',
        config: {},
        // expand new pointcut
        isOpen: true
      };
      $scope.pointcuts.push(pointcut);
      $timeout(function () {
        // focus on type name
        pointcut.isFocus = true;
      }, 0);
    };

    // this is called by child controller below
    $scope.removePointcut = function (pointcut) {
      var index = $scope.pointcuts.indexOf(pointcut);
      if (index !== -1) {
        $scope.pointcuts.splice(index, 1);
      }
    };

    $scope.retransformClasses = function (deferred) {
      $http.post('backend/admin/adhoc-pointcuts/reweave', '')
          .success(function (data) {
            $scope.data.dirty = false;
            deferred.resolve('Success');
          })
          .error(function (data, status) {
            if (status === 0) {
              deferred.reject('Unable to connect to server');
            } else {
              deferred.reject('An error occurred');
            }
          });
    };
  }
]);

informant.controller('ConfigAdhocPointcutCtrl', [
  '$scope',
  '$http',
  function ($scope, $http) {
    var originalConfig = angular.copy($scope.pointcut.config);

    $scope.hasChanges = function () {
      return !angular.equals($scope.pointcut.config, originalConfig);
    };

    $scope.typeNames = function (suggestion) {
      var url = 'backend/adhoc-pointcut/matching-type-names?partial-type-name=' + suggestion + '&limit=7';
      return $http.get(url)
          .then(function (response) {
            return response.data;
          });
    };

    $scope.onSelectTypeName = function () {
      var typeName = $scope.pointcut.config.typeName;
      // check if the value has really changed (e.g. that a user didn't start altering text and
      // then changed mind and put the previous value back)
      if (typeName !== $scope.pointcut.selectedTypeName) {
        $scope.pointcut.selectedTypeName = typeName;
        $scope.pointcut.selectedMethodName = '';
        $scope.pointcut.signatures = [];
        $scope.pointcut.config.methodName = '';
        $scope.pointcut.config.metricName = '';
        $scope.pointcut.config.spanText = '';
        $scope.pointcut.config.traceGrouping = '';
      }
    };

    $scope.methodNames = function (suggestion) {
      if (suggestion.indexOf('*') !== -1) {
        return [ suggestion ];
      }
      var url = 'backend/adhoc-pointcut/matching-method-names?type-name=' +
          $scope.pointcut.config.typeName + '&partial-method-name=' + suggestion + '&limit=7';
      return $http.get(url)
          .then(function (response) {
            // TODO handle error response
            return response.data;
          });
    };

    $scope.onSelectMethodName = function () {
      var methodName = $scope.pointcut.config.methodName;

      // since matchingMethods clears the span text, check here if the value has really changed
      // (e.g. that a user didn't start altering text and then changed mind and put the previous
      // value back)

      // also, this condition is needed in case where user enters a value and clicks outside of the input field
      // to set the value to something that is not available in the dropdown

      if (methodName !== $scope.pointcut.selectedMethodName) {
        $scope.pointcut.selectedMethodName = methodName;
        if (methodName.indexOf('*') !== -1) {
          $scope.pointcut.signatures = [
            {
              name: methodName,
              argTypeNames: [ '..' ],
              returnTypeName: '',
              modifiers: []
            }
          ];
          $scope.pointcut.selectedSignature = $scope.pointcut.signatures[0];
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
      var signature = $scope.pointcut.selectedSignature;
      if (!signature) {
        deferred.reject('method for pointcut must be selected');
        return;
      }
      $scope.pointcut.config.methodArgTypeNames = signature.argTypeNames;
      $scope.pointcut.config.methodReturnTypeName = signature.returnTypeName;
      $scope.pointcut.config.methodModifiers = signature.modifiers;
      var url;
      var version = $scope.pointcut.version;
      if (version) {
        url = 'backend/config/adhoc-pointcut/' + version;
      } else {
        url = 'backend/config/adhoc-pointcut/+';
      }
      $http.post(url, $scope.pointcut.config)
          .success(function (data) {
            originalConfig = angular.copy($scope.pointcut.config);
            $scope.pointcut.version = data;
            $scope.pointcut.heading = $scope.pointcut.config.typeName +
                '.' + $scope.pointcut.config.methodName +
                '(' + $scope.pointcut.config.methodArgTypeNames.join(', ') + ')';
            $scope.data.dirty = true;
            deferred.resolve(version ? 'Saved' : 'Added');
          })
          .error(function (data, status) {
            if (status === 0) {
              deferred.reject('Unable to connect to server');
            } else {
              deferred.reject('An error occurred');
            }
          });
    };

    // TODO this needs access to outer scope remove pointcut and update retransformClassesButton
    $scope.pointcutDelete = function (deferred) {
      if ($scope.pointcut.version) {
        $http.post('backend/config/adhoc-pointcut/-', '"' + $scope.pointcut.version + '"')
            .success(function (data) {
              $scope.$parent.removePointcut($scope.pointcut);
              $scope.data.dirty = true;
              deferred.resolve('Deleted');
            })
            .error(function (data, status) {
              if (status === 0) {
                deferred.reject('Unable to connect to server');
              } else {
                deferred.reject('An error occurred');
              }
            });
      } else {
        $scope.$parent.removePointcut($scope.pointcut);
        deferred.resolve('Deleted');
      }
    };

    function matchingMethods(methodName) {
      var url = 'backend/adhoc-pointcut/matching-methods?type-name=' +
          $scope.pointcut.config.typeName + '&method-name=' + methodName;
      $http.get(url)
          .success(function (data) {
            $scope.pointcut.signatures = data;
            if (data.length !== 1) {
              // this includes the case where data.length === 0, which is possible if the user enters a
              // non-matching method name and clicks outside of the input field to bypass the typeahead values
              $scope.pointcut.signatures.push({
                name: methodName,
                argTypeNames: [ '..' ],
                returnTypeName: '',
                modifiers: []
              });
            }
            if (data.length === 1) {
              $scope.pointcut.selectedSignature = data[0];
            } else {
              $scope.pointcut.selectedSignature = undefined;
            }
          })
          .error(function () {
            // TODO
          });
    }

    $scope.$watch('pointcut.config.metric', function (newValue, oldValue) {
      if (newValue === oldValue) {
        // called due to watcher initialization
        return;
      }
      if (!newValue) {
        $scope.pointcut.config.span = false;
        $scope.pointcut.config.trace = false;
      }
    });

    $scope.$watch('pointcut.config.span', function (newValue, oldValue) {
      if (newValue === oldValue) {
        // called due to watcher initialization
        return;
      }
      if (newValue) {
        initSpanText();
      } else {
        $scope.pointcut.config.trace = false;
        $scope.pointcut.config.spanText = '';
        $scope.pointcut.config.traceGrouping = '';
      }
    });

    $scope.$watch('pointcut.config.trace', function (newValue, oldValue) {
      if (newValue === oldValue) {
        // called due to watcher initialization
        return;
      }
      if (newValue) {
        initSpanText();
        initTraceGrouping();
      } else {
        $scope.pointcut.config.traceGrouping = '';
      }
    });

    $scope.$watch('pointcut.selectedSignature', function (newValue, oldValue) {
      if (newValue === oldValue) {
        // called due to watcher initialization
        return;
      }
      if ($scope.pointcut.config.span) {
        initSpanText();
      }
      if ($scope.pointcut.config.trace) {
        initTraceGrouping();
      }
    });

    function initSpanText() {
      var signature = $scope.pointcut.selectedSignature;
      if (!signature) {
        // no radio button selected
        $scope.pointcut.config.spanText = '';
        return;
      }
      var template;
      if (signature.modifiers.indexOf('abstract') !== -1) {
        template = '{{this.class.name}}.';
      } else {
        template = $scope.pointcut.config.typeName + '.';
      }
      if (signature.name.indexOf('*') !== -1) {
        template += '{{methodName}}()';
        $scope.pointcut.config.spanText = template;
        return;
      }
      template += signature.name + '()';
      if (isSignatureAll(signature)) {
        $scope.pointcut.config.spanText = template;
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
      $scope.pointcut.config.spanText = template;
    }

    function isSignatureAll(signature) {
      return signature.modifiers.length === 0 && signature.returnTypeName === '' &&
          signature.argTypeNames.length === 1 && signature.argTypeNames[0] === '..';
    }

    function initTraceGrouping() {
      var signature = $scope.pointcut.selectedSignature;
      if (!signature) {
        // no radio button selected
        return;
      }
      var grouping = $scope.pointcut.config.typeName + '.' + signature.name + '()';
      $scope.pointcut.config.traceGrouping = grouping;
    }
  }
]);
