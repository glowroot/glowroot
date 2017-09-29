/*
 * Copyright 2012-2017 the original author or authors.
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
  'confirmIfHasChanges',
  'httpErrors',
  'queryStrings',
  function ($scope, $location, $http, confirmIfHasChanges, httpErrors, queryStrings) {

    var version = $location.search().v;

    function onNewData(data) {
      if (data.config.mbeanAttributes) {
        // need to sort attributes to keep hasChanges() consistent
        data.config.mbeanAttributes.sort(function (a, b) {
          return a.name < b.name ? -1 : a.name > b.name ? 1 : 0;
        });
      }
      $scope.config = data.config;
      $scope.originalConfig = angular.copy(data.config);

      if (data.config.mbeanObjectName) {
        $scope.heading = data.config.display;
        $scope.selectedMBeanObjectName = data.config.mbeanObjectName;
        $scope.mbeanUnavailable = data.mbeanUnavailable;
        $scope.mbeanUnmatched = data.mbeanUnmatched;
        var allMBeanAttributes = {};
        angular.forEach(data.mbeanAvailableAttributeNames, function (mbeanAttributeName) {
          allMBeanAttributes[mbeanAttributeName] = {
            checked: false,
            counter: false,
            available: true
          };
        });
        angular.forEach(data.config.mbeanAttributes, function (mbeanAttr) {
          var mbeanAttribute = allMBeanAttributes[mbeanAttr.name];
          if (mbeanAttribute) {
            mbeanAttribute.checked = true;
            mbeanAttribute.counter = mbeanAttr.counter;
          } else {
            allMBeanAttributes[mbeanAttr.name] = {
              checked: true,
              counter: mbeanAttr.counter,
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
            counter: value.counter,
            available: value.available
          });
        });
      } else {
        $scope.heading = '<New>';
      }
    }

    if (version) {
      $http.get('backend/config/gauges?agent-id=' + encodeURIComponent($scope.agentId) + '&version=' + version)
          .then(function (response) {
            $scope.loaded = true;
            $scope.agentNotConnected = response.data.agentNotConnected;
            onNewData(response.data);
          }, function (response) {
            httpErrors.handle(response, $scope);
          });
    } else {
      $http.get('backend/config/new-gauge-check-agent-connected?agent-id=' + encodeURIComponent($scope.agentId))
          .then(function (response) {
            $scope.loaded = true;
            $scope.agentNotConnected = !response.data;
            onNewData({
              config: {
                mbeanAttributes: []
              },
              mbeanAvailable: false,
              mbeanAvailableAttributeNames: []
            });
          }, function (response) {
            httpErrors.handle(response, $scope);
          });
    }

    $scope.$watch('allMBeanAttributes', function (newValue, oldValue) {
      if (newValue !== oldValue) {
        $scope.config.mbeanAttributes = [];
        angular.forEach($scope.allMBeanAttributes, function (mbeanAttribute) {
          if (mbeanAttribute.checked) {
            $scope.config.mbeanAttributes.push({
              name: mbeanAttribute.name,
              counter: mbeanAttribute.counter
            });
          }
        });
        // need to sort attributes to keep hasChanges() consistent
        $scope.config.mbeanAttributes.sort(function (a, b) {
          return a.name < b.name ? -1 : a.name > b.name ? 1 : 0;
        });
      }
    }, true);

    $scope.hasChanges = function () {
      return !angular.equals($scope.config, $scope.originalConfig);
    };
    var removeConfirmIfHasChangesListener = $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    $scope.showMBeanObjectNameSpinner = 0;

    $scope.mbeanObjectNames = function (suggestion) {
      if (suggestion.indexOf('*') !== -1) {
        return [suggestion];
      }
      var queryData = {
        agentId: $scope.agentId,
        partialObjectName: suggestion,
        limit: 10
      };
      $scope.showMBeanObjectNameSpinner++;
      // using 'then' method to return promise
      return $http.get('backend/config/matching-mbean-objects' + queryStrings.encodeObject(queryData))
          .then(function (response) {
            $scope.showMBeanObjectNameSpinner--;
            return response.data;
          }, function (response) {
            $scope.showMBeanObjectNameSpinner--;
            httpErrors.handle(response, $scope);
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
        $scope.mbeanUnmatched = false;
        $scope.duplicateMBean = false;
        $scope.allMBeanAttributes = [];
      }
    };

    function fetchMBeanAttributes(mbeanObjectName) {
      var queryData = {
        agentId: $scope.agentId,
        objectName: mbeanObjectName,
        gaugeVersion: $scope.config.version || ''
      };
      $scope.mbeanAttributesLoading = true;
      $http.get('backend/config/mbean-attributes' + queryStrings.encodeObject(queryData))
          .then(function (response) {
            $scope.mbeanAttributesLoading = false;
            var data = response.data;
            $scope.mbeanUnavailable = data.mbeanUnavailable;
            $scope.mbeanUnmatched = data.mbeanUnmatched;
            $scope.duplicateMBean = data.duplicateMBean;
            $scope.allMBeanAttributes = [];
            angular.forEach(data.mbeanAttributes, function (mbeanAttribute) {
              $scope.allMBeanAttributes.push({
                name: mbeanAttribute,
                checked: false,
                counter: false,
                available: true
              });
            });
          }, function (response) {
            $scope.mbeanAttributesLoading = false;
            httpErrors.handle(response, $scope);
          });
    }

    $scope.hasMBeanObjectNameError = function () {
      return $scope.config && (!$scope.config.mbeanObjectName || $scope.mbeanUnavailable
          || $scope.mbeanUnmatched || $scope.duplicateMBean);
    };

    $scope.saveDisabled = function () {
      return !$scope.config || !$scope.config.mbeanAttributes.length || $scope.formCtrl.$invalid
          || $scope.mbeanUnavailable || $scope.mbeanUnmatched || $scope.duplicateMBean;
    };

    $scope.save = function (deferred) {
      var postData = angular.copy($scope.config);
      var url;
      if (version) {
        url = 'backend/config/gauges/update';
      } else {
        url = 'backend/config/gauges/add';
      }
      var agentId = $scope.agentId;
      $http.post(url + '?agent-id=' + encodeURIComponent(agentId), postData)
          .then(function (response) {
            onNewData(response.data);
            deferred.resolve(version ? 'Saved' : 'Added');
            version = response.data.config.version;
            // fix current url (with updated version) before returning to list page in case back button is used later
            if (agentId) {
              $location.search({'agent-id': agentId, v: version}).replace();
            } else {
              $location.search({v: version}).replace();
            }
          }, function (response) {
            if (response.status === 409 && response.data.message === 'mbeanObjectName') {
              $scope.duplicateMBean = true;
              deferred.reject('There is already a gauge for this MBean');
              return;
            }
            httpErrors.handle(response, $scope, deferred);
          });
    };

    $scope.delete = function (deferred) {
      var postData = {
        version: $scope.config.version
      };
      var agentId = $scope.agentId;
      $http.post('backend/config/gauges/remove?agent-id=' + encodeURIComponent(agentId), postData)
          .then(function () {
            removeConfirmIfHasChangesListener();
            if (agentId) {
              $location.url('config/gauge-list?agent-id=' + encodeURIComponent(agentId)).replace();
            } else {
              $location.url('config/gauge-list').replace();
            }
          }, function (response) {
            httpErrors.handle(response, $scope, deferred);
          });
    };
  }
]);
