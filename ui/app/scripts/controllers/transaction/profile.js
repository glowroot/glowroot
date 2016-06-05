/*
 * Copyright 2015-2016 the original author or authors.
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

/* global glowroot, HandlebarsRendering, gtParseIncludesExcludes, $ */

glowroot.controller('TransactionProfileCtrl', [
  '$scope',
  '$http',
  '$location',
  'locationChanges',
  'queryStrings',
  'httpErrors',
  'auxiliary',
  function ($scope, $http, $location, locationChanges, queryStrings, httpErrors, auxiliary) {

    $scope.$parent.activeTabItem = 'profile';

    if ($scope.hideMainContent()) {
      return;
    }

    var appliedFilter;

    $scope.auxiliary = auxiliary;
    $scope.showProfile = false;
    $scope.showSpinner = 0;

    $scope.$watchGroup(['range.chartFrom', 'range.chartTo', 'range.chartRefresh'], function () {
      $location.search('filter', $scope.filter || null);
      refreshData();
    });

    $scope.clickTopRadioButton = function (item) {
      if (($scope.auxiliary && item === 'aux-thread-profile') || (!$scope.auxiliary && item === 'main-thread-profile')) {
        $scope.range.chartRefresh++;
      } else {
        $location.url('transaction/' + item + $scope.tabQueryString());
      }
    };

    $scope.clickActiveTopLink = function (event, item) {
      if (event.ctrlKey) {
        return;
      }
      if (($scope.auxiliary && item === 'aux-thread-profile') || (!$scope.auxiliary && item === 'main-thread-profile')) {
        $scope.range.chartRefresh++;
        // suppress normal link
        event.preventDefault();
        return false;
      }
    };

    $scope.flameGraphHref = function () {
      var query = $scope.buildQueryObject();
      delete query['summary-sort-order'];
      if ($scope.filter) {
        query.filter = $scope.filter;
      }
      if ($scope.auxiliary) {
        return 'transaction/aux-thread-flame-graph' + queryStrings.encodeObject(query);
      } else {
        return 'transaction/main-thread-flame-graph' + queryStrings.encodeObject(query);
      }
    };

    $scope.refresh = function () {
      $scope.applyLast();
      appliedFilter = $scope.filter;
      $scope.range.chartRefresh++;
    };

    locationChanges.on($scope, function () {
      var priorAppliedFilter = appliedFilter;
      appliedFilter = $location.search().filter || '';

      if (priorAppliedFilter !== undefined && appliedFilter !== priorAppliedFilter) {
        // e.g. back or forward button was used to navigate
        $scope.range.chartRefresh++;
      }
      $scope.filter = appliedFilter;
      $scope.truncateBranchPercentage = $location.search()['truncate-branch-percentage'] || 0.1;
    });

    $('.gt-profile-text-filter').on('gtClearProfileFilter', function (event, response) {
      $scope.$apply(function () {
        $scope.filter = '';
        $scope.refresh();
      });
      response.handled = true;
    });

    function refreshData() {
      $scope.parsingError = undefined;
      var parseResult = gtParseIncludesExcludes($scope.filter);
      if (parseResult.error) {
        $scope.parsingError = parseResult.error;
        $scope.showProfile = true;
        $scope.sampleCount = 0;
        $('#profileOuter').removeData('gtLoaded');
        HandlebarsRendering.profileToggle(undefined, '#profileOuter', {stackTraceElement: '', sampleCount: 0});
        return;
      }
      var query = {
        agentRollup: $scope.agentRollup,
        transactionType: $scope.transactionType,
        transactionName: $scope.transactionName,
        from: $scope.range.chartFrom,
        to: $scope.range.chartTo,
        auxiliary: $scope.auxiliary,
        include: parseResult.includes,
        exclude: parseResult.excludes,
        truncateBranchPercentage: $scope.truncateBranchPercentage
      };
      $scope.showSpinner++;
      $http.get('backend/transaction/profile' + queryStrings.encodeObject(query))
          .success(function (data) {
            $scope.showSpinner--;
            $scope.showOverwrittenMessage = data.overwritten;
            $scope.hasUnfilteredAuxThreadProfile = data.hasUnfilteredAuxThreadProfile;
            if ($scope.showOverwrittenMessage) {
              $scope.showProfile = false;
              return;
            }
            $scope.showProfile = data.profile.unfilteredSampleCount;
            if ($scope.showProfile) {
              $scope.sampleCount = data.profile.unfilteredSampleCount;
              $('#profileOuter').removeData('gtLoaded');
              HandlebarsRendering.profileToggle(undefined, '#profileOuter', data.profile);
            }
          })
          .error(function (data, status) {
            $scope.showSpinner--;
            httpErrors.handler($scope)(data, status);
          });
    }
  }
]);
