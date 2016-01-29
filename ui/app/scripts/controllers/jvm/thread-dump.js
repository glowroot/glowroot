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

/* global glowroot, $, Handlebars, JST */

glowroot.controller('JvmThreadDumpCtrl', [
  '$scope',
  '$http',
  '$location',
  '$q',
  'locationChanges',
  'traceModal',
  'httpErrors',
  function ($scope, $http, $location, $q, locationChanges, traceModal, httpErrors) {

    $scope.$parent.heading = 'Thread dump';

    if ($scope.hideMainContent()) {
      return;
    }

    var threadDumpHtml;

    Handlebars.registerHelper('ifBlocked', function (state, options) {
      if (state === 'BLOCKED') {
        return options.fn(this);
      } else {
        return options.inverse(this);
      }
    });

    Handlebars.registerHelper('ifWaiting', function (state, options) {
      if (state === 'WAITING' || state === 'TIMED_WAITING') {
        return options.fn(this);
      } else {
        return options.inverse(this);
      }
    });

    $scope.exportAsText = function () {
      var textWindow = window.open();
      var exportHtml = threadDumpHtml.replace(/ <a [^>]*>view trace<\/a>/g, '');
      $(textWindow.document.body).html('<pre>' + exportHtml + '</pre>');
    };

    locationChanges.on($scope, function() {
      var modalTraceId = $location.search()['modal-trace-id'];
      if (modalTraceId) {
        $('#traceModal').data('location-query', 'modal-trace-id');
        traceModal.displayModal($scope.serverId, modalTraceId);
      } else {
        $('#traceModal').modal('hide');
      }
    });

    $scope.refresh = function (deferred) {
      $http.get('backend/jvm/thread-dump?server-id=' + encodeURIComponent($scope.serverId))
          .success(function (data) {
            $scope.loaded = true;
            $scope.agentNotConnected = data.agentNotConnected;
            if ($scope.agentNotConnected) {
              return;
            }
            // $.trim() is needed because this template is sensitive to surrounding spaces
            threadDumpHtml = $.trim(JST['thread-dump'](data));
            $('#threadDump').html('<br>' + threadDumpHtml);
            if (deferred) {
              deferred.resolve('Refreshed');
            }
          })
          .error(httpErrors.handler($scope, deferred));
    };

    $scope.refresh();
  }
]);
