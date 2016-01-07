/*
 * Copyright 2012-2016 the original author or authors.
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

/* global glowroot, angular, $ */

glowroot.controller('ConfigInstrumentationListCtrl', [
  '$scope',
  '$location',
  '$http',
  '$timeout',
  '$q',
  'locationChanges',
  'httpErrors',
  'modals',
  function ($scope, $location, $http, $timeout, $q, locationChanges, httpErrors, modals) {

    $scope.display = function (config) {
      return config.className + '::' + config.methodName;
    };

    $scope.displayExtra = function (config) {
      var captureKind = config.captureKind;
      if (captureKind === 'timer') {
        return 'Timer';
      } else if (captureKind === 'trace-entry') {
        return 'Trace entry';
      } else if (captureKind === 'transaction') {
        return 'Transaction';
      } else {
        return 'Other';
      }
    };

    function refresh(deferred) {
      $http.get('backend/config/instrumentation?server-id=' + $scope.serverId)
          .success(function (data) {
            $scope.loaded = true;
            $scope.configs = data.configs;
            // use object so dirty flag can be updated by child controllers
            $scope.dirty = data.jvmOutOfSync;
            $scope.jvmRetransformClassesSupported = data.jvmRetransformClassesSupported;
            if (deferred) {
              deferred.resolve();
            } else {
              // preload cache for class name and method name auto completion
              $http.get('backend/config/preload-classpath-cache?server-id=' + $scope.serverId);
            }
          })
          .error(httpErrors.handler($scope, deferred));
    }

    $scope.displayImportModal = function () {
      $location.search('import', true);
    };

    $scope.$watch('jsonToImport', function () {
      $scope.importErrorMessage = '';
    });

    $scope.importFromJson = function () {
      $scope.importErrorMessage = '';
      var postData = {
        serverId: $scope.serverId,
        classAnnotation: '',
        methodDeclaringClassName: '',
        methodAnnotation: '',
        methodReturnType: '',
        transactionType: '',
        transactionNameTemplate: '',
        transactionUserTemplate: '',
        traceEntryCaptureSelfNested: false,
        enabledProperty: '',
        traceEntryEnabledProperty: ''
      };
      try {
        angular.extend(postData, angular.fromJson($scope.jsonToImport));
      } catch (e) {
        $scope.importErrorMessage = 'Invalid json';
        return;
      }
      var initialErrors = [];
      if (postData.className === undefined) {
        initialErrors.push('Missing className');
      }
      if (postData.methodName === undefined) {
        initialErrors.push('Missing methodName');
      }
      if (postData.captureKind === undefined) {
        initialErrors.push('Missing captureKind');
      }
      if (initialErrors.length) {
        $scope.importErrorMessage = initialErrors.join(', ');
        return;
      }
      $scope.importing = true;
      $http.post('backend/config/instrumentation/add', postData)
          .success(function (data) {
            if (data.errors) {
              $scope.importing = false;
              $scope.importErrorMessage = data.errors.join(', ');
              return;
            }
            var deferred = $q.defer();
            deferred.promise.finally(function () {
              // leave spinner going until subsequent refresh is complete
              $scope.importing = false;
              $('#importModal').modal('hide');
            });
            refresh(deferred);
          })
          .error(function (data, status) {
            $scope.importing = false;
            // validation errors are handled in success, this is unexpected error, display stack trace on main page
            $('#importModal').modal('hide');
            httpErrors.handler($scope)(data, status);
          });
    };

    $scope.retransformClasses = function (deferred) {
      $http.post('backend/admin/reweave')
          .success(function (data) {
            $scope.dirty = false;
            if (data.classes) {
              var msg = 're-transformed ' + data.classes + ' class' + (data.classes > 1 ? 'es' : '');
              deferred.resolve('Success (' + msg + ')');
            } else {
              deferred.resolve('Success (no classes needed re-transforming)');
            }
          })
          .error(httpErrors.handler($scope, deferred));
    };

    refresh();

    locationChanges.on($scope, function () {
      if ($location.search().import) {
        $scope.jsonToImport = '';
        $scope.importErrorMessage = '';
        $scope.importing = false;
        $('#importModal').data('location-query', 'import');
        modals.display('#importModal', true);
        $timeout(function() {
          $('#importModal textarea').focus();
        });
      } else {
        $('#importModal').modal('hide');
      }
    });
  }
]);
