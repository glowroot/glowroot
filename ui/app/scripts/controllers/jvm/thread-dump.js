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

    Handlebars.registerHelper('agentIdQueryString', function () {
      if ($scope.agentId) {
        return 'agent-id=' + encodeURIComponent($scope.agentId) + '&';
      } else {
        return '';
      }
    });

    $scope.exportAsText = function () {
      var textWindow = window.open();
      var exportHtml = threadDumpHtml.replace(/ <a [^>]*>view trace<\/a>/g, '');
      $(textWindow.document.body).html('<pre style="white-space: pre-wrap;">' + exportHtml + '</pre>');
    };

    locationChanges.on($scope, function() {
      var modalTraceId = $location.search()['modal-trace-id'];
      var modalCheckLiveTraces = $location.search()['modal-check-live-traces'];
      if (modalTraceId) {
        $('#traceModal').data('location-query', ['modal-trace-id', 'modal-check-live-traces']);
        traceModal.displayModal($scope.agentId, modalTraceId, modalCheckLiveTraces);
      } else {
        $('#traceModal').modal('hide');
      }
    });

    $scope.refresh = function (deferred) {
      $http.get('backend/jvm/thread-dump?agent-id=' + encodeURIComponent($scope.agentId))
          .then(function (response) {
            $scope.loaded = true;
            $scope.agentNotConnected = response.data.agentNotConnected;
            if ($scope.agentNotConnected) {
              return;
            }
            // $.trim() is needed because this template is sensitive to surrounding spaces
            threadDumpHtml = $.trim(JST['thread-dump'](response.data));
            $('#threadDump').html('<br>' + threadDumpHtml);
            if (deferred) {
              deferred.resolve('Refreshed');
            }
          }, function (response) {
            httpErrors.handle(response, $scope, deferred);
          });
    };

    $scope.refresh();
  }
]);
