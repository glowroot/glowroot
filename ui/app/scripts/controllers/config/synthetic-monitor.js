/*
 * Copyright 2017 the original author or authors.
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

glowroot.controller('ConfigSyntheticMonitorCtrl', [
  '$scope',
  '$location',
  '$http',
  'encryptionKeyMessage',
  'confirmIfHasChanges',
  'httpErrors',
  function ($scope, $location, $http, encryptionKeyMessage, confirmIfHasChanges, httpErrors) {

    // initialize page binding object
    $scope.page = {};

    var id = $location.search().id;

    function onNewData(data) {
      $scope.config = data;
      $scope.originalConfig = angular.copy(data);

      if (data.display) {
        $scope.heading = data.display;
      } else if (data.pingUrl) {
        $scope.heading = 'Ping ' + data.pingUrl;
      } else {
        $scope.heading = '<New>';
      }
    }

    if (id) {
      var url = 'backend/config/synthetic-monitors?agent-rollup-id=' + encodeURIComponent($scope.agentRollupId)
          + '&id=' + id;
      $http.get(url)
          .then(function (response) {
            $scope.loaded = true;
            onNewData(response.data);
          }, function (response) {
            httpErrors.handle(response, $scope);
          });
    } else {
      $scope.loaded = true;
      onNewData({
        kind: 'ping'
      });
    }

    $scope.$watch('config.kind', function (newValue, oldValue) {
      if (!$scope.config) {
        return;
      }
      if (oldValue === undefined) {
        return;
      }
      delete $scope.config.pingUrl;
      delete $scope.config.javaSource;
      if (newValue === 'java') {
        $scope.config.javaSource = 'import org.openqa.selenium.*;\n'
            + 'import org.openqa.selenium.support.ui.*;\n\n'
            + 'import static org.openqa.selenium.support.ui.ExpectedConditions.*;\n\n'
            + 'public class Example {\n\n'
            + '    public void test(WebDriver driver) throws Exception {\n\n'
            + '        // e.g.\n'
            + '        driver.get("https://www.example.org");\n'
            + '        new WebDriverWait(driver, 30).until(\n'
            + '                  visibilityOfElementLocated(By.xpath("//h1[text()=\'Example Domain\']")));\n'
            + '        WebElement element = driver.findElement(By.linkText("More information..."));\n'
            + '        element.click();\n'
            + '        // ...\n'
            + '    }\n'
            + '}';
      }
    });

    $scope.hasChanges = function () {
      return !angular.equals($scope.config, $scope.originalConfig);
    };
    var removeConfirmIfHasChangesListener = $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    $scope.save = function (deferred) {
      var postData = angular.copy($scope.config);
      var url;
      if (id) {
        url = 'backend/config/synthetic-monitors/update';
      } else {
        url = 'backend/config/synthetic-monitors/add';
      }
      var agentId = $scope.agentId;
      var agentRollupId = $scope.agentRollupId;
      $http.post(url + '?agent-rollup-id=' + encodeURIComponent(agentRollupId), postData)
          .then(function (response) {
            if (response.data.symmetricEncryptionKeyMissing) {
              deferred.reject('cassandra.symmetricEncryptionKey must be configured in the glowroot-central.properties'
                  + ' file before string constants beginning with the text "ENCRYPT:" can be encrypted'
                  + encryptionKeyMessage.extra());
              return;
            }
            if (response.data.javaSourceCompilationErrors) {
              $scope.javaSourceCompilationErrors = response.data.javaSourceCompilationErrors;
              deferred.reject('compile errors (see above)');
              return;
            }
            $scope.javaSourceCompilationErrors = [];
            onNewData(response.data);
            if (id) {
              deferred.resolve('Saved');
            } else {
              deferred.resolve('Added');
              id = response.data.id;
              // fix current url (with id) before returning to list page in case back button is used later
              if (agentId) {
                $location.search({'agent-id': agentId, id: id}).replace();
              } else if (agentRollupId) {
                $location.search({'agent-rollup-id': agentRollupId, id: id}).replace();
              } else {
                $location.search({id: id}).replace();
              }
            }
          }, function (response) {
            httpErrors.handle(response, $scope, deferred);
          });
    };

    $scope.delete = function (deferred) {
      var postData = {
        id: $scope.config.id
      };
      var agentId = $scope.agentId;
      var agentRollupId = $scope.agentRollupId;
      var url = 'backend/config/synthetic-monitors/remove?agent-rollup-id=' + encodeURIComponent(agentRollupId);
      $http.post(url, postData)
          .then(function () {
            removeConfirmIfHasChangesListener();
            if (agentId) {
              $location.url('config/synthetic-monitor-list?agent-id=' + encodeURIComponent(agentId)).replace();
            } else if (agentRollupId) {
              $location.url('config/synthetic-monitor-list?agent-rollup-id=' + encodeURIComponent(agentRollupId))
                  .replace();
            } else {
              $location.url('config/synthetic-monitor-list').replace();
            }
          }, function (response) {
            httpErrors.handle(response, $scope, deferred);
          });
    };
  }
]);
