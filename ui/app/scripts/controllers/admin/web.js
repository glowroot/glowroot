/*
 * Copyright 2013-2017 the original author or authors.
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

glowroot.controller('AdminWebCtrl', [
  '$scope',
  '$http',
  '$rootScope',
  '$location',
  '$timeout',
  'confirmIfHasChanges',
  'httpErrors',
  function ($scope, $http, $rootScope, $location, $timeout, confirmIfHasChanges, httpErrors) {

    $scope.hasChanges = function () {
      return $scope.originalConfig && !angular.equals($scope.config, $scope.originalConfig);
    };
    $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    function onNewData(data) {
      $scope.loaded = true;
      $scope.config = data.config;
      $scope.originalConfig = angular.copy(data.config);
      if (!$scope.layout.central) {
        $scope.activePort = data.activePort;
        $scope.activeBindAddress = data.activeBindAddress;
        $scope.activeHttps = data.activeHttps;
        $scope.certificateDir = data.certificateDir;
      }
    }

    $scope.save = function (deferred) {
      // another copy to modify for the http post data
      var postData = angular.copy($scope.config);
      if ($scope.layout.central) {
        $http.post('backend/admin/web', postData)
            .then(function (response) {
              var data = response.data;
              onNewData(data);
              deferred.resolve('Saved');
            }, function (response) {
              httpErrors.handle(response, $scope, deferred);
            });
      } else {
        var changingPort = $scope.config.port !== $scope.activePort;
        var previousActivePort = $scope.activePort;
        var changingHttps = $scope.config.https !== $scope.activeHttps;
        $http.post('backend/admin/web', postData)
            .then(function (response) {
              var data = response.data;
              if (data.httpsRequiredFilesDoNotExist) {
                deferred.reject('The certificate and private key to be used must be placed in the glowroot directory'
                    + ' with filenames ui-cert.pem and ui-key.pem before enabling HTTPS');
                return;
              }
              if (data.httpsValidationError) {
                deferred.reject(data.httpsValidationError);
                return;
              }
              if (data.portChangeFailed) {
                deferred.reject('Save succeeded, but switching over to the new port failed');
                return;
              }
              onNewData(data);
              if (!changingPort && !changingHttps) {
                // normal path
                deferred.resolve('Saved');
                return;
              }
              var text;
              if (changingPort && !changingHttps) {
                text = 'port';
              } else if (!changingPort && changingHttps) {
                text = 'protocol';
              } else {
                text = 'port and protocol';
              }
              if ($location.port() !== previousActivePort) {
                deferred.reject('The save succeeded, and switching the http listener over to the new port succeeded,'
                    + ' but you are not being redirected to the new ' + text + ' since it seems you are using an'
                    + ' intermediary proxy?');
                return;
              }
              deferred.resolve('Saved, redirecting to new ' + text + ' ...');
              $timeout(function () {
                var newProtocol = data.activeHttps ? 'https' : 'http';
                var newUrl = newProtocol + '://' + $location.host();
                if (data.activePort !== 80) {
                  newUrl += ':' + data.activePort;
                }
                newUrl += $location.path();
                document.location.href = newUrl;
              }, 500);
            }, function (response) {
              httpErrors.handle(response, $scope, deferred);
            });
      }
    };

    $http.get('backend/admin/web')
        .then(function (response) {
          onNewData(response.data);
        }, function (response) {
          httpErrors.handle(response, $scope);
        });
  }
]);
