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

glowroot.controller('ConfigMBeanGaugeCtrl', [
  '$scope',
  '$http',
  '$timeout',
  'confirmIfHasChanges',
  'httpErrors',
  'queryStrings',
  function ($scope, $http, $timeout, confirmIfHasChanges, httpErrors, queryStrings) {

    function onNewData(data) {
      $scope.config = data.config;
      $scope.originalConfig = angular.copy(data.config);

      if (data.config.mbeanObjectName) {
        $scope.heading = data.config.name;
        $scope.selectedMBeanObjectName = data.config.mbeanObjectName;
        $scope.mbeanUnavailable = data.mbeanUnavailable;
        var allMBeanAttributes = {};
        angular.forEach(data.mbeanAvailableAttributeNames, function (mbeanAttributeName) {
          allMBeanAttributes[mbeanAttributeName] = {
            checked: false,
            available: true
          };
        });
        angular.forEach(data.config.mbeanAttributeNames, function (mbeanAttributeName) {
          var mbeanAttribute = allMBeanAttributes[mbeanAttributeName];
          if (mbeanAttribute) {
            mbeanAttribute.checked = true;
          } else {
            allMBeanAttributes[mbeanAttributeName] = {
              checked: true,
              available: false
            };
          }
        });
        // need to put attributes in an array to loop in ng-repeat with orderBy
        $scope.allMBeanAttributes = [];
        angular.forEach(allMBeanAttributes, function (value, key) {
          $scope.allMBeanAttributes.push({
            name: key,
            checked: value.checked,
            available: value.available
          });
        });
      } else {
        $scope.heading = '<New gauge>';
        $timeout(function () {
          // focus on type name
          $scope.isFocus = true;
        }, 0);
      }
    }

    onNewData($scope.gauge);
    $scope.$watch('allMBeanAttributes', function () {
      $scope.config.mbeanAttributeNames = [];
      angular.forEach($scope.allMBeanAttributes, function (mbeanAttribute) {
        if (mbeanAttribute.checked) {
          $scope.config.mbeanAttributeNames.push(mbeanAttribute.name);
        }
      });
    }, true);

    $scope.hasChanges = function () {
      return !angular.equals($scope.config, $scope.originalConfig);
    };
    $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    $scope.showMBeanObjectNameSpinner = 0;

    $scope.mbeanObjectNames = function (suggestion) {
      var queryData = {
        partialMBeanObjectName: suggestion,
        limit: 10
      };
      $scope.showMBeanObjectNameSpinner++;
      // use 'then' method to return promise
      return $http.get('backend/config/matching-mbean-objects?' + queryStrings.encodeObject(queryData))
          .then(function (response) {
            $scope.showMBeanObjectNameSpinner--;
            return response.data;
          }, function () {
            $scope.showMBeanObjectNameSpinner--;
            // TODO handle error
          });
    };

    $scope.onSelectMBeanObjectName = function () {
      var mbeanObjectName = $scope.config.mbeanObjectName;
      // check if the value has really changed (e.g. that a user didn't start altering text and
      // then changed mind and put the previous value back)
      if (mbeanObjectName !== $scope.selectedMBeanObjectName) {
        $scope.selectedMBeanObjectName = mbeanObjectName;
        fetchMBeanAttributes(mbeanObjectName);
      }
    };

    $scope.onBlurMBeanObjectName = function () {
      if (!$scope.config.mbeanObjectName) {
        // the user cleared the text input and tabbed away
        $scope.mbeanUnavailable = false;
        $scope.duplicateMBean = false;
        $scope.allMBeanAttributes = [];
      }
    };

    function fetchMBeanAttributes(mbeanObjectName) {
      var queryData = {
        mbeanObjectName: mbeanObjectName,
        mbeanGaugeVersion: $scope.config.version || ''
      };
      $scope.mbeanAttributesLoading = true;
      $http.get('backend/config/mbean-attributes?' + queryStrings.encodeObject(queryData))
          .success(function (data) {
            $scope.mbeanAttributesLoading = false;
            $scope.mbeanUnavailable = data.mbeanUnavailable;
            $scope.duplicateMBean = data.duplicateMBean;
            $scope.allMBeanAttributes = [];
            angular.forEach(data.mbeanAttributes, function (mbeanAttribute) {
              $scope.allMBeanAttributes.push({
                name: mbeanAttribute,
                checked: false,
                available: true
              });
            });
          })
          .error(httpErrors.handler($scope));
    }

    $scope.hasMBeanObjectNameError = function () {
      return !$scope.config.mbeanObjectName || $scope.mbeanUnavailable || $scope.duplicateMBean;
    };

    $scope.saveDisabled = function () {
      return !$scope.hasChanges() || $scope.formCtrl.$invalid || $scope.mbeanUnavailable || $scope.duplicateMBean;
    };

    $scope.save = function (deferred) {
      var postData = angular.copy($scope.config);
      delete postData.version;
      // java.lang:name=PS Eden Space,type=MemoryPool
      var parts = postData.mbeanObjectName.split(/[:,]/);
      postData.name = parts[0];
      for (var i = 1; i < parts.length; i++) {
        postData.name += '/' + parts[i].split('=')[1];
      }
      postData.mbeanAttributeNames = [];
      angular.forEach($scope.allMBeanAttributes, function (mbeanAttribute) {
        if (mbeanAttribute.checked) {
          postData.mbeanAttributeNames.push(mbeanAttribute.name);
        }
      });
      var url;
      var version = $scope.config.version;
      if (version) {
        url = 'backend/config/mbean-gauge/' + version;
      } else {
        url = 'backend/config/mbean-gauge/+';
      }
      $http.post(url, postData)
          .success(function (data) {
            onNewData(data);
            deferred.resolve(version ? 'Saved' : 'Added');
          })
          .error(function (data, status) {
            if (status === 409 && data.message === 'mbeanObjectName') {
              $scope.duplicateMBean = true;
            }
            // normal error handling
            httpErrors.handler($scope, deferred)(data, status);
          });
    };

    $scope.delete = function (deferred) {
      if ($scope.config.version) {
        $http.post('backend/config/mbean-gauge/-', '"' + $scope.config.version + '"')
            .success(function (data) {
              $scope.$parent.removeGauge($scope.gauge);
              deferred.resolve('Deleted');
            })
            .error(httpErrors.handler($scope, deferred));
      } else {
        $scope.$parent.removeGauge($scope.gauge);
        deferred.resolve('Deleted');
      }
    };
  }
]);
