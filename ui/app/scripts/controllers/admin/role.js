/*
 * Copyright 2016-2018 the original author or authors.
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

glowroot.controller('AdminRoleCtrl', [
  '$scope',
  '$location',
  '$http',
  'confirmIfHasChanges',
  'httpErrors',
  function ($scope, $location, $http, confirmIfHasChanges, httpErrors) {

    // initialize page binding object
    $scope.page = {};

    $scope.name = $location.search().name;

    function onNewData(data) {
      // need to sort to keep hasChanges() consistent
      data.config.permissions.sort();
      var i;
      var permissionBlock;
      for (i = 0; i < data.config.permissionBlocks.length; i++) {
        permissionBlock = data.config.permissionBlocks[i];
        permissionBlock.agentRollupIds.sort();
        permissionBlock.permissions.sort();
        if (permissionBlock.agentRollupIds.length === 1 && permissionBlock.agentRollupIds[0] === '*') {
          // need to put '*' (all agent config) first, to avoid hasChanges()
          data.config.permissionBlocks.splice(i, 1);
          data.config.permissionBlocks.unshift(permissionBlock);
        }
      }

      $scope.config = data.config;
      $scope.originalConfig = angular.copy(data.config);

      if (data.config.name) {
        $scope.heading = data.config.name;
      } else {
        $scope.heading = '<New>';
      }
      $scope.page.permissions = newPermissionBlock();
      $scope.page.permissions.admin = {
        _: false,
        view: false,
        edit: false
      };
      $scope.page.permissionBlocks = [];
      angular.forEach(data.config.permissions, function (permission) {
        if (permission === 'report') {
          $scope.page.permissions.report = true;
          return;
        }
        if (permission === 'admin') {
          $scope.page.permissions.admin._ = true;
          return;
        }
        if (permission === 'admin:view') {
          $scope.page.permissions.admin.view = true;
          return;
        }
        if (permission === 'admin:edit') {
          $scope.page.permissions.admin.edit = true;
          return;
        }
        populatePermissionBlock($scope.page.permissions, permission);
      });
      $scope.allActiveAgentRollups = data.allActiveAgentRollups;
      $scope.rollupsExist = false;
      angular.forEach($scope.allActiveAgentRollups, function (agentRollup) {
        if ($scope.isRollup(agentRollup.id)) {
          $scope.rollupsExist = true;
        }
      });
      angular.forEach($scope.allActiveAgentRollups, function (agentRollup) {
        var indent = '';
        for (var i = 0; i < agentRollup.depth; i++) {
          indent += '\u00a0\u00a0\u00a0\u00a0';
        }
        agentRollup.indentedDisplay = indent + agentRollup.lastDisplayPart;
      });
      angular.forEach(data.config.permissionBlocks, function (configPermissionBlock) {
        var permissionBlock = newPermissionBlock();
        permissionBlock.agentRollupIds = configPermissionBlock.agentRollupIds;
        permissionBlock.allActiveAgentRollupsPlus = [];
        updateAllActiveAgentRollupsPlus(permissionBlock);
        $scope.page.permissionBlocks.push(permissionBlock);
        angular.forEach(configPermissionBlock.permissions, function (permission) {
          populatePermissionBlock(permissionBlock, permission);
        });
      });
    }

    function populatePermissionBlock(permissionBlock, permission) {
      if (permission === 'agent:transaction') {
        permissionBlock.transaction._ = true;
      } else if (permission === 'agent:transaction:overview') {
        permissionBlock.transaction.overview = true;
      } else if (permission === 'agent:transaction:traces') {
        permissionBlock.transaction.traces = true;
      } else if (permission === 'agent:transaction:queries') {
        permissionBlock.transaction.queries = true;
      } else if (permission === 'agent:transaction:serviceCalls') {
        permissionBlock.transaction.serviceCalls = true;
      } else if (permission === 'agent:transaction:profile') {
        permissionBlock.transaction.profile = true;
      } else if (permission === 'agent:error') {
        permissionBlock.error._ = true;
      } else if (permission === 'agent:error:overview') {
        permissionBlock.error.overview = true;
      } else if (permission === 'agent:error:traces') {
        permissionBlock.error.traces = true;
      } else if (permission === 'agent:jvm') {
        permissionBlock.jvm._ = true;
      } else if (permission === 'agent:jvm:gauges') {
        permissionBlock.jvm.gauges = true;
      } else if (permission === 'agent:jvm:threadDump') {
        permissionBlock.jvm.threadDump = true;
      } else if (permission === 'agent:jvm:heapDump') {
        permissionBlock.jvm.heapDump = true;
      } else if (permission === 'agent:jvm:heapHistogram') {
        permissionBlock.jvm.heapHistogram = true;
      } else if (permission === 'agent:jvm:mbeanTree') {
        permissionBlock.jvm.mbeanTree = true;
      } else if (permission === 'agent:jvm:systemProperties') {
        permissionBlock.jvm.systemProperties = true;
      } else if (permission === 'agent:jvm:environment') {
        permissionBlock.jvm.environment = true;
      } else if (permission === 'agent:syntheticMonitor') {
        permissionBlock.syntheticMonitor = true;
      } else if (permission === 'agent:incident') {
        permissionBlock.incident = true;
      } else if (permission === 'agent:config') {
        permissionBlock.config._ = true;
      } else if (permission === 'agent:config:view') {
        permissionBlock.config.view = true;
      } else if (permission === 'agent:config:edit') {
        permissionBlock.config.edit._ = true;
      } else if ($scope.layout.central && permission === 'agent:config:edit:general') {
        permissionBlock.config.edit.general = true;
      } else if (permission === 'agent:config:edit:transaction') {
        permissionBlock.config.edit.transaction = true;
      } else if (permission === 'agent:config:edit:gauge') {
        permissionBlock.config.edit.gauge = true;
      } else if (permission === 'agent:config:edit:jvm') {
        permissionBlock.config.edit.jvm = true;
      } else if ($scope.layout.central && permission === 'agent:config:edit:syntheticMonitor') {
        permissionBlock.config.edit.syntheticMonitor = true;
      } else if (permission === 'agent:config:edit:alert') {
        permissionBlock.config.edit.alert = true;
      } else if (permission === 'agent:config:edit:ui') {
        permissionBlock.config.edit.ui = true;
      } else if (permission === 'agent:config:edit:plugin') {
        permissionBlock.config.edit.plugin = true;
      } else if (permission === 'agent:config:edit:instrumentation') {
        permissionBlock.config.edit.instrumentation = true;
      } else if (permission === 'agent:config:edit:advanced') {
        permissionBlock.config.edit.advanced = true;
      }
    }

    function cascadeInsidePermissionsObj(permissionsObj) {
      if (permissionsObj.transaction._) {
        permissionsObj.transaction.overview = false;
        permissionsObj.transaction.traces = false;
        permissionsObj.transaction.queries = false;
        permissionsObj.transaction.serviceCalls = false;
        permissionsObj.transaction.profile = false;
      }
      if (permissionsObj.error._) {
        permissionsObj.error.overview = false;
        permissionsObj.error.traces = false;
      }
      if (permissionsObj.jvm._) {
        permissionsObj.jvm.gauges = false;
        permissionsObj.jvm.threadDump = false;
        permissionsObj.jvm.heapDump = false;
        permissionsObj.jvm.heapHistogram = false;
        permissionsObj.jvm.mbeanTree = false;
        permissionsObj.jvm.systemProperties = false;
        permissionsObj.jvm.environment = false;
      }
      if (permissionsObj.config._) {
        permissionsObj.config.view = false;
        permissionsObj.config.edit._ = false;
        if ($scope.layout.central) {
          permissionsObj.config.edit.general = false;
        }
        permissionsObj.config.edit.transaction = false;
        permissionsObj.config.edit.gauge = false;
        permissionsObj.config.edit.jvm = false;
        if ($scope.layout.central) {
          permissionsObj.config.edit.syntheticMonitor = false;
        }
        permissionsObj.config.edit.alert = false;
        permissionsObj.config.edit.ui = false;
        permissionsObj.config.edit.plugin = false;
        permissionsObj.config.edit.instrumentation = false;
        permissionsObj.config.edit.reweave = false;
        permissionsObj.config.edit.advanced = false;
      }
      if (permissionsObj.config.edit._) {
        if ($scope.layout.central) {
          permissionsObj.config.edit.general = false;
        }
        permissionsObj.config.edit.transaction = false;
        permissionsObj.config.edit.gauge = false;
        permissionsObj.config.edit.jvm = false;
        if ($scope.layout.central) {
          permissionsObj.config.edit.syntheticMonitor = false;
        }
        permissionsObj.config.edit.alert = false;
        permissionsObj.config.edit.ui = false;
        permissionsObj.config.edit.plugin = false;
        permissionsObj.config.edit.instrumentation = false;
        permissionsObj.config.edit.reweave = false;
        permissionsObj.config.edit.advanced = false;
      }
      if (permissionsObj.admin && permissionsObj.admin._) {
        permissionsObj.admin.view = false;
        permissionsObj.admin.edit = false;
      }
    }

    $scope.transactionOverviewRequired = function (permissionsObj) {
      if (!permissionsObj) {
        return false;
      }
      return permissionsObj.transaction.traces || permissionsObj.transaction.queries
          || permissionsObj.transaction.serviceCalls || permissionsObj.transaction.profile;
    };

    $scope.viewConfigRequired = function (permissionsObj) {
      if (!permissionsObj) {
        return false;
      }
      return permissionsObj.config.edit._ || ($scope.layout.central && permissionsObj.config.edit.general)
          || permissionsObj.config.edit.transaction || permissionsObj.config.edit.gauge
          || permissionsObj.config.edit.jvm || ($scope.layout.central && permissionsObj.config.edit.syntheticMonitor)
          || permissionsObj.config.edit.alert || permissionsObj.config.edit.ui || permissionsObj.config.edit.plugin
          || permissionsObj.config.edit.instrumentation || permissionsObj.config.edit.reweave
          || permissionsObj.config.edit.advanced;
    };

    function permissionsObjToList(permissionsObj) {
      var permissions = [];
      if (permissionsObj.transaction._) {
        permissions.push('agent:transaction');
      }
      if (permissionsObj.transaction.overview) {
        permissions.push('agent:transaction:overview');
      }
      if (permissionsObj.transaction.traces) {
        permissions.push('agent:transaction:traces');
      }
      if (permissionsObj.transaction.queries) {
        permissions.push('agent:transaction:queries');
      }
      if (permissionsObj.transaction.serviceCalls) {
        permissions.push('agent:transaction:serviceCalls');
      }
      if (permissionsObj.transaction.profile) {
        permissions.push('agent:transaction:profile');
      }
      if (permissionsObj.error._) {
        permissions.push('agent:error');
      }
      if (permissionsObj.error.overview) {
        permissions.push('agent:error:overview');
      }
      if (permissionsObj.error.traces) {
        permissions.push('agent:error:traces');
      }
      if (permissionsObj.jvm._) {
        permissions.push('agent:jvm');
      }
      if (permissionsObj.jvm.gauges) {
        permissions.push('agent:jvm:gauges');
      }
      if (permissionsObj.jvm.threadDump) {
        permissions.push('agent:jvm:threadDump');
      }
      if (permissionsObj.jvm.heapDump) {
        permissions.push('agent:jvm:heapDump');
      }
      if (permissionsObj.jvm.heapHistogram) {
        permissions.push('agent:jvm:heapHistogram');
      }
      if (permissionsObj.jvm.mbeanTree) {
        permissions.push('agent:jvm:mbeanTree');
      }
      if (permissionsObj.jvm.systemProperties) {
        permissions.push('agent:jvm:systemProperties');
      }
      if (permissionsObj.jvm.environment) {
        permissions.push('agent:jvm:environment');
      }
      if (permissionsObj.syntheticMonitor) {
        permissions.push('agent:syntheticMonitor');
      }
      if (permissionsObj.incident) {
        permissions.push('agent:incident');
      }
      if (permissionsObj.config._) {
        permissions.push('agent:config');
      }
      if (permissionsObj.config.view) {
        permissions.push('agent:config:view');
      }
      if (permissionsObj.config.edit._) {
        permissions.push('agent:config:edit');
      }
      if ($scope.layout.central && permissionsObj.config.edit.general) {
        permissions.push('agent:config:edit:general');
      }
      if (permissionsObj.config.edit.transaction) {
        permissions.push('agent:config:edit:transaction');
      }
      if (permissionsObj.config.edit.gauge) {
        permissions.push('agent:config:edit:gauge');
      }
      if (permissionsObj.config.edit.jvm) {
        permissions.push('agent:config:edit:jvm');
      }
      if ($scope.layout.central && permissionsObj.config.edit.syntheticMonitor) {
        permissions.push('agent:config:edit:syntheticMonitor');
      }
      if (permissionsObj.config.edit.alert) {
        permissions.push('agent:config:edit:alert');
      }
      if (permissionsObj.config.edit.ui) {
        permissions.push('agent:config:edit:ui');
      }
      if (permissionsObj.config.edit.plugin) {
        permissions.push('agent:config:edit:plugin');
      }
      if (permissionsObj.config.edit.instrumentation) {
        permissions.push('agent:config:edit:instrumentation');
      }
      if (permissionsObj.config.edit.advanced) {
        permissions.push('agent:config:edit:advanced');
      }
      if (permissionsObj.report) {
        permissions.push('report');
      }
      if (permissionsObj.admin && permissionsObj.admin._) {
        permissions.push('admin');
      }
      if (permissionsObj.admin && permissionsObj.admin.view) {
        permissions.push('admin:view');
      }
      if (permissionsObj.admin && permissionsObj.admin.edit) {
        permissions.push('admin:edit');
      }
      return permissions;
    }

    function updateAllActiveAgentRollupsPlus(permissionBlock) {
      // need to re-use same array, otherwise not reflected in UI
      // e.g. after removing an inactive item it still shows up in the dropdown
      permissionBlock.allActiveAgentRollupsPlus.length = 0;
      Array.prototype.push.apply(permissionBlock.allActiveAgentRollupsPlus, $scope.allActiveAgentRollups);
      var activeAgentRollupIds = {};
      angular.forEach(permissionBlock.allActiveAgentRollupsPlus, function (agentRollup) {
        activeAgentRollupIds[agentRollup.id] = true;
      });
      angular.forEach(permissionBlock.agentRollupIds, function (agentRollupId) {
        if (!activeAgentRollupIds[agentRollupId]) {
          var display = agentRollupId + ' (not active in the past 30 days)';
          permissionBlock.allActiveAgentRollupsPlus.push({
            depth: 0,
            id: agentRollupId,
            display: display,
            indentedDisplay: display,
            notActive: true
          });
        }
      });
    }

    $scope.$watch('page', function () {
      if (!$scope.page.permissions) {
        // this happens during page load
        return;
      }
      cascadeInsidePermissionsObj($scope.page.permissions);
      var permissions = permissionsObjToList($scope.page.permissions);
      // need to sort to preserve original (sorted) ordering and avoid hasChanges()
      permissions.sort();
      $scope.config.permissions = permissions;
      $scope.config.permissionBlocks = [];
      angular.forEach($scope.page.permissionBlocks, function (permissionBlock) {
        var configPermissionBlock = {
          agentRollupIds: permissionBlock.agentRollupIds,
          permissions: []
        };
        cascadeInsidePermissionsObj(permissionBlock);
        updateAllActiveAgentRollupsPlus(permissionBlock);
        configPermissionBlock.permissions = permissionsObjToList(permissionBlock);
        configPermissionBlock.agentRollupIds.sort();
        configPermissionBlock.permissions.sort();
        $scope.config.permissionBlocks.push(configPermissionBlock);
      });
    }, true);

    function newPermissionBlock() {
      return {
        transaction: {
          _: false,
          overview: false,
          traces: false,
          queries: false,
          serviceCalls: false,
          profile: false
        },
        error: {
          _: false,
          overview: false,
          traces: false
        },
        jvm: {
          _: false,
          gauges: false,
          threadDump: false,
          heapDump: false,
          heapHistogram: false,
          mbeanTree: false,
          systemProperties: false,
          environment: false
        },
        syntheticMonitor: false,
        incident: false,
        config: {
          _: false,
          view: false,
          edit: {
            _: false,
            transaction: false,
            gauge: false,
            jvm: false,
            alert: false,
            ui: false,
            plugin: false,
            instrumentation: false,
            advanced: false
          }
        }
      };
    }

    $scope.addPermissionBlock = function () {
      var permissionBlock = newPermissionBlock();
      permissionBlock.agentRollupIds = [];
      permissionBlock.allActiveAgentRollupsPlus = angular.copy($scope.allActiveAgentRollups);
      $scope.page.permissionBlocks.push(permissionBlock);
    };

    $scope.removePermissionBlock = function (permissionBlock) {
      var index = $scope.page.permissionBlocks.indexOf(permissionBlock);
      $scope.page.permissionBlocks.splice(index, 1);
    };

    if ($scope.name) {
      $http.get('backend/admin/roles?name=' + encodeURIComponent($scope.name))
          .then(function (response) {
            $scope.loaded = true;
            onNewData(response.data);
          }, function (response) {
            httpErrors.handle(response, $scope);
          });
    } else if ($scope.layout.central) {
      $http.get('backend/admin/all-active-agent-rollups')
          .then(function (response) {
            $scope.loaded = true;
            onNewData({
              config: {
                permissions: [],
                permissionBlocks: []
              },
              allActiveAgentRollups: response.data
            });
          }, function (response) {
            httpErrors.handle(response, $scope);
          });
    } else {
      $scope.loaded = true;
      onNewData({
        config: {
          permissions: [],
          permissionBlocks: []
        }
      });
    }

    $scope.hasChanges = function () {
      return !angular.equals($scope.config, $scope.originalConfig);
    };
    var removeConfirmIfHasChangesListener = $scope.$on('$locationChangeStart', confirmIfHasChanges($scope));

    function listsEqualOrderInsensitive(alist, blist) {
      if (alist.length !== blist.length) {
        return false;
      }
      var blistCopy = angular.copy(blist);
      var i, j;
      for (i = 0; i < alist.length; i++) {
        j = blistCopy.indexOf(alist[i]);
        if (j === -1) {
          return false;
        }
        blistCopy.splice(j, 1);
      }
      return true;
    }

    $scope.save = function (deferred) {
      var postData = angular.copy($scope.config);
      var url;
      if ($scope.name) {
        url = 'backend/admin/roles/update';
      } else {
        url = 'backend/admin/roles/add';
      }
      $http.post(url, postData)
          .then(function (response) {
            var data = response.data;
            // preserve permission ordering if possible in order to preserve permission block ordering
            // otherwise maybe confusing to user to have order suddenly change after save
            if (listsEqualOrderInsensitive(data.config.permissions, postData.permissions)) {
              data.config.permissions = postData.permissions;
            }
            onNewData(data);
            deferred.resolve($scope.name ? 'Saved' : 'Added');
            if (!$scope.name) {
              $scope.name = data.config.name;
              $location.search({name: $scope.name}).replace();
            }
          }, function (response) {
            if (response.status === 409 && response.data.message === 'name') {
              $scope.duplicateName = true;
              deferred.reject('There is already a role with this name');
              return;
            }
            httpErrors.handle(response, $scope, deferred);
          });
    };

    $scope.delete = function (deferred) {
      var postData = {
        name: $scope.name
      };
      $http.post('backend/admin/roles/remove', postData)
          .then(function (response) {
            if (response.data.errorCannotDeleteLastRole) {
              deferred.reject('Cannot delete last role');
              return;
            }
            removeConfirmIfHasChangesListener();
            $location.url('admin/role-list').replace();
          }, function (response) {
            httpErrors.handle(response, $scope, deferred);
          });
    };
  }
]);
