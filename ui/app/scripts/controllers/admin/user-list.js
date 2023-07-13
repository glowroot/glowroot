/*
 * Copyright 2016-2023 the original author or authors.
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

glowroot.controller('AdminUserListCtrl', [
  '$scope',
  '$http',
  'httpErrors',
  function ($scope, $http, httpErrors) {

    $http.get('backend/admin/users')
        .then(function (response) {
          $scope.loaded = true;
          $scope.users = [];
          var anonymousUser;
          angular.forEach(response.data, function (user) {
            if (user.username.toLowerCase() === 'anonymous') {
              anonymousUser = user;
              anonymousUser.display = '<anonymous>';
            } else {
              $scope.users.push(user);
              user.display = user.username;
            }
          });
          if (anonymousUser) {
            $scope.users.push(anonymousUser);
          }
        }, function (response) {
          httpErrors.handle(response);
        });
  }
]);
