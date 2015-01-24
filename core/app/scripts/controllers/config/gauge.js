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

glowroot.controller('ConfigGaugeCtrl', [
  '$scope',
  '$location',
  '$http',
  '$timeout',
  'confirmIfHasChanges',
  'httpErrors',
  'queryStrings',
  function ($scope, $location, $http, $timeout, confirmIfHasChanges, httpErrors, queryStrings) {

    var version = $location.search().v;

    function onNewData(data) {
      // need to sort attribute names to keep hasChanges() consistent
      if (data.config.mbeanAttributeNames) {
        data.config.mbeanAttributeNames.sort();
      }
      $scope.config = data.config;
      $scope.originalConfig = angular.copy(data.config);

      if (data.config.mbeanObjectName) {
        $scope.heading = data.config.name;
        // \u200b is zero width space and \u00a0 is non-breaking space
        // these are used to change wrapping behavior on smaller screens (or larger mbean names)
        $scope.heading = $scope.heading.replace(/\//g, '\u200b/');
        $scope.heading = $scope.heading.replace(/ /g, '\u00a0');
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

    if (version) {
      $http.get('backend/config/gauges/' + version)
          .success(function (data) {
            $scope.loaded = true;
            onNewData(data);
          })
          .error(httpErrors.handler($scope));
    } else {
      $scope.loaded = true;
      onNewData({
        config: {
          mbeanAttributeNames: []
        },
        mbeanAvailable: false,
        mbeanAvailableAttributeNames: []
      });
    }

    $scope.$watch('allMBeanAttributes', function (newValue, oldValue) {
      if (newValue !== oldValue) {
        $scope.config.mbeanAttributeNames = [];
        angular.forEach($scope.allMBeanAttributes, function (mbeanAttribute) {
          if (mbeanAttribute.checked) {
            $scope.config.mbeanAttributeNames.push(mbeanAttribute.name);
          }
        });
        // need to sort attribute names to keep hasChanges() consistent
        $scope.config.mbeanAttributeNames.sort();
      }
    }, true);

    $scope.hasChanges = function () {
      return !angular.equals($scope.config, $scope.originalConfig);
    };
    var removeConfirmIfHasChangesListener = $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    $scope.showMBeanObjectNameSpinner = 0;

    $scope.mbeanObjectNames = function (suggestion) {
      var queryData = {
        partialMBeanObjectName: suggestion,
        limit: 10
      };
      $scope.showMBeanObjectNameSpinner++;
      // use 'then' method to return promise
      return $http.get('backend/config/matching-mbean-objects' + queryStrings.encodeObject(queryData))
          .then(function (response) {
            $scope.showMBeanObjectNameSpinner--;
            return response.data;
          }, function (data, status) {
            $scope.showMBeanObjectNameSpinner--;
            httpErrors.handler($scope)(data, status);
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
        gaugeVersion: $scope.config.version || ''
      };
      $scope.mbeanAttributesLoading = true;
      $http.get('backend/config/mbean-attributes' + queryStrings.encodeObject(queryData))
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
          .error(function (data, status) {
            $scope.mbeanAttributesLoading = false;
            httpErrors.handler($scope)(data, status);
          });
    }

    $scope.hasMBeanObjectNameError = function () {
      return $scope.config && (!$scope.config.mbeanObjectName || $scope.mbeanUnavailable || $scope.duplicateMBean);
    };

    $scope.saveDisabled = function () {
      return !$scope.hasChanges() || $scope.formCtrl.$invalid || $scope.mbeanUnavailable || $scope.duplicateMBean;
    };

    $scope.save = function (deferred) {
      var postData = angular.copy($scope.config);
      // e.g. java.lang:name=PS Eden Space,type=MemoryPool
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
      if (version) {
        url = 'backend/config/gauges/update';
      } else {
        url = 'backend/config/gauges/add';
      }
      $http.post(url, postData)
          .success(function (data) {
            onNewData(data);
            deferred.resolve(version ? 'Saved' : 'Added');
            version = data.config.version;
            // fix current url (with updated version) before returning to list page in case back button is used later
            $timeout(function () {
              $location.search({v: version}).replace();
              $timeout(function () {
                $location.url('/config/gauge-list');
              });
            });
          })
          .error(function (data, status) {
            if (status === 409 && data.message === 'mbeanObjectName') {
              $scope.duplicateMBean = true;
              deferred.reject('There is already a gauge for this MBean');
              return;
            }
            httpErrors.handler($scope, deferred)(data, status);
          });
    };

    $scope.delete = function (deferred) {
      $http.post('backend/config/gauges/remove', '"' + $scope.config.version + '"')
          .success(function () {
            removeConfirmIfHasChangesListener();
            $location.url('/config/gauge-list').replace();
          })
          .error(httpErrors.handler($scope, deferred));
    };
  }
]);
