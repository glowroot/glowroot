/*
 * Copyright 2016 the original author or authors.
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
  '$timeout',
  'confirmIfHasChanges',
  'httpErrors',
  function ($scope, $location, $http, $timeout, confirmIfHasChanges, httpErrors) {

    // initialize page binding object
    $scope.page = {};

    $scope.name = $location.search().name;

    function onNewData(data) {
      // need to sort to avoid hasChanges()
      data.config.permissions.sort();

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
      var permissionBlocks = {};
      angular.forEach(data.config.permissions, function (permission) {
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
        var permissionBlock;
        if ($scope.layout.fat) {
          permissionBlock = $scope.page.permissions;
        } else {
          var index = permission.indexOf(':', 6);
          var agentId = permission.substring(6, index);
          permission = 'agent' + permission.substring(index);
          if (agentId === '*') {
            permissionBlock = $scope.page.permissions;
          } else {
            permissionBlock = permissionBlocks[agentId];
            if (!permissionBlock) {
              permissionBlock = newPermissionBlock();
              permissionBlock.agentIds = agentId.split(',');
              $scope.page.permissionBlocks.push(permissionBlock);
              permissionBlocks[agentId] = permissionBlock;
            }
          }
        }
        if (permission === 'agent:view') {
          permissionBlock.view = true;
        } else if (permission === 'agent:tool') {
          permissionBlock.tool._ = true;
        } else if (permission === 'agent:tool:threadDump') {
          permissionBlock.tool.threadDump = true;
        } else if (permission === 'agent:tool:heapDump') {
          permissionBlock.tool.heapDump = true;
        } else if (permission === 'agent:tool:mbeanTree') {
          permissionBlock.tool.mbeanTree = true;
        } else if (permission === 'agent:config') {
          permissionBlock.config._ = true;
        } else if (permission === 'agent:config:view') {
          permissionBlock.config.view = true;
        } else if (permission === 'agent:config:edit') {
          permissionBlock.config.edit._ = true;
        } else if (permission === 'agent:config:edit:transaction') {
          permissionBlock.config.edit.transaction = true;
        } else if (permission === 'agent:config:edit:gauge') {
          permissionBlock.config.edit.gauge = true;
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
      });
      $scope.allAgentIds = data.allAgentIds;
    }

    function cascadeInsidePermissionsObj(permissionsObj) {
      if (permissionsObj.tool._) {
        permissionsObj.tool.threadDump = false;
        permissionsObj.tool.heapDump = false;
        permissionsObj.tool.mbeanTree = false;
      }
      if (permissionsObj.config._) {
        permissionsObj.config.view = false;
        permissionsObj.config.edit._ = false;
        permissionsObj.config.edit.transaction = false;
        permissionsObj.config.edit.gauge = false;
        permissionsObj.config.edit.alert = false;
        permissionsObj.config.edit.ui = false;
        permissionsObj.config.edit.plugin = false;
        permissionsObj.config.edit.instrumentation = false;
        permissionsObj.config.edit.reweave = false;
        permissionsObj.config.edit.advanced = false;
      }
      if (permissionsObj.config.edit._) {
        permissionsObj.config.edit.transaction = false;
        permissionsObj.config.edit.gauge = false;
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
      if ($scope.viewPermissionDisabled(permissionsObj)) {
        permissionsObj.view = true;
      }
    }

    $scope.viewPermissionDisabled = function (permissionsObj) {
      if (!permissionsObj) {
        return false;
      }
      return permissionsObj.tool._ || permissionsObj.tool.threadDump || permissionsObj.tool.heapDump
          || permissionsObj.tool.mbeanTree || permissionsObj.config._ || permissionsObj.config.view
          || permissionsObj.config.edit._ || permissionsObj.config.edit.transaction || permissionsObj.config.edit.gauge
          || permissionsObj.config.edit.alert || permissionsObj.config.edit.ui || permissionsObj.config.edit.plugin
          || permissionsObj.config.edit.instrumentation || permissionsObj.config.edit.reweave
          || permissionsObj.config.edit.advanced;
    };

    function permissionsObjToList(permissionsObj) {
      var agentIds = permissionsObj.agentIds;
      if (agentIds) {
        agentIds = agentIds.join(',') + ':';
      } else if ($scope.layout.fat) {
        agentIds = '';
      } else {
        agentIds = '*:';
      }
      var permissions = [];
      if (permissionsObj.view) {
        permissions.push('agent:' + agentIds + 'view');
      }
      if (permissionsObj.tool._) {
        permissions.push('agent:' + agentIds + 'tool');
      }
      if (permissionsObj.tool.threadDump) {
        permissions.push('agent:' + agentIds + 'tool:threadDump');
      }
      if (permissionsObj.tool.heapDump) {
        permissions.push('agent:' + agentIds + 'tool:heapDump');
      }
      if (permissionsObj.tool.mbeanTree) {
        permissions.push('agent:' + agentIds + 'tool:mbeanTree');
      }
      if (permissionsObj.config._) {
        permissions.push('agent:' + agentIds + 'config');
      }
      if (permissionsObj.config.view) {
        permissions.push('agent:' + agentIds + 'config:view');
      }
      if (permissionsObj.config.edit._) {
        permissions.push('agent:' + agentIds + 'config:edit');
      }
      if (permissionsObj.config.edit.transaction) {
        permissions.push('agent:' + agentIds + 'config:edit:transaction');
      }
      if (permissionsObj.config.edit.gauge) {
        permissions.push('agent:' + agentIds + 'config:edit:gauge');
      }
      if (permissionsObj.config.edit.alert) {
        permissions.push('agent:' + agentIds + 'config:edit:alert');
      }
      if (permissionsObj.config.edit.ui) {
        permissions.push('agent:' + agentIds + 'config:edit:ui');
      }
      if (permissionsObj.config.edit.plugin) {
        permissions.push('agent:' + agentIds + 'config:edit:plugin');
      }
      if (permissionsObj.config.edit.instrumentation) {
        permissions.push('agent:' + agentIds + 'config:edit:instrumentation');
      }
      if (permissionsObj.config.edit.advanced) {
        permissions.push('agent:' + agentIds + 'config:edit:advanced');
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

    $scope.$watch('page', function () {
      if (!$scope.page.permissions) {
        // this happens during page load
        return;
      }
      cascadeInsidePermissionsObj($scope.page.permissions);
      var permissions = permissionsObjToList($scope.page.permissions);
      angular.forEach($scope.page.permissionBlocks, function (permissionBlock) {
        cascadeInsidePermissionsObj(permissionBlock);
        angular.forEach(permissionsObjToList(permissionBlock), function (permission) {
          if (permissions.indexOf(permission) === -1) {
            permissions.push(permission);
          }
        });
      });
      // need to sort to preserve original (sorted) ordering and avoid hasChanges()
      permissions.sort();
      $scope.config.permissions = permissions;
    }, true);

    function newPermissionBlock() {
      return {
        view: false,
        tool: {
          _: false,
          threadDump: false,
          heapDump: false,
          mbeanTree: false
        },
        config: {
          _: false,
          view: false,
          edit: {
            _: false,
            transaction: false,
            gauge: false,
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
      $scope.page.permissionBlocks.push(newPermissionBlock());
    };

    $scope.removePermissionBlock = function (permissionBlock) {
      var index = $scope.page.permissionBlocks.indexOf(permissionBlock);
      $scope.page.permissionBlocks.splice(index, 1);
    };

    if ($scope.name) {
      $http.get('backend/admin/roles?name=' + encodeURIComponent($scope.name))
          .success(function (data) {
            $scope.loaded = true;
            onNewData(data);
          })
          .error(httpErrors.handler($scope));
    } else if (!$scope.layout.fat) {
      $http.get('backend/admin/all-agent-ids')
          .success(function (data) {
            $scope.loaded = true;
            onNewData({
              config: {
                permissions: []
              },
              allAgentIds: data
            });
          })
          .error(httpErrors.handler($scope));
    } else {
      $scope.loaded = true;
      onNewData({
        config: {
          permissions: []
        },
        allAgentIds: []
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
          .success(function (data) {
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
          })
          .error(function (data, status) {
            if (status === 409 && data.message === 'name') {
              $scope.duplicateName = true;
              deferred.reject('There is already a role with this name');
              return;
            }
            httpErrors.handler($scope, deferred)(data, status);
          });
    };

    $scope.delete = function (deferred) {
      var postData = {
        name: $scope.name
      };
      $http.post('backend/admin/roles/remove', postData)
          .success(function () {
            removeConfirmIfHasChangesListener();
            $location.url('admin/role-list').replace();
          })
          .error(httpErrors.handler($scope, deferred));
    };
  }
]);
