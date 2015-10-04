/*
 * Copyright 2015 the original author or authors.
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

/* global glowroot, moment, $ */

glowroot.controller('ChartRangeCtrl', [
  '$scope',
  '$location',
  'queryStrings',
  'charts',
  'modals',
  function ($scope, $location, queryStrings, charts, modals) {

    $scope.$parent.chartRefresh = 0;

    function fancyDate(date) {
      var today = new Date();
      today.setHours(0, 0, 0, 0);
      var dateDate = new Date(date);
      dateDate.setHours(0, 0, 0, 0);
      if (dateDate.getTime() === today.getTime()) {
        return 'Today';
      }
      if (dateDate.getTime() === today.getTime() - 24 * 60 * 60 * 1000) {
        return 'Yesterday';
      }
      return moment(date).format('l');
    }

    $scope.lastDisplay = function (last) {
      if (last === 60 * 60 * 1000) {
        return 'Last 60 minutes'; // instead of Last 1 hour
      }
      if (last === 24 * 60 * 60 * 1000) {
        return 'Last 24 hours'; // instead of Last 1 day
      }

      var display = 'Last';

      function append(num, label) {
        if (num) {
          display += ' ' + num + ' ' + label;
          if (num !== 1) {
            display += 's';
          }
        }
      }

      var duration = moment.duration(last);
      append(Math.floor(duration.asDays()), 'day');
      append(duration.hours(), 'hour');
      append(duration.minutes(), 'minute');
      append(duration.seconds(), 'second');
      return display;
    };

    $scope.timeDisplay = function () {
      if ($scope.last) {
        return $scope.lastDisplay($scope.last);
      }
      var fromDate = fancyDate($scope.chartFrom);
      var toDate = fancyDate($scope.chartTo);
      if (fromDate === toDate) {
        return fromDate + ', ' + moment($scope.chartFrom).format('LT') + ' to ' + moment($scope.chartTo).format('LT');
      } else {
        return fromDate + ' ' + moment($scope.chartFrom).format('LT') + ' to ' + toDate + ' ' +
            moment($scope.chartTo).format('LT');
      }
    };

    $scope.headerRangeQueryString = function (last) {
      var query = $scope.buildQueryObject();
      delete query['trace-chart-from'];
      delete query['trace-chart-to'];
      delete query.from;
      delete query.to;
      if (last === 4 * 60 * 60 * 1000) {
        delete query.last;
      } else {
        query.last = last;
      }
      return queryStrings.encodeObject(query);
    };

    $scope.updateLast = function (last, event) {
      if (last === $scope.$parent.last && !event.ctrlKey) {
        // no change, force refresh
        $scope.$parent.chartRefresh++;
        // suppress normal link
        event.preventDefault();
        return false;
      }
    };

    $scope.rangeSelections = [
      30 * 60 * 1000, // 30 minutes
      60 * 60 * 1000, // 60 minutes
      2 * 60 * 60 * 1000, // 2 hours
      4 * 60 * 60 * 1000, // 4 hours
      8 * 60 * 60 * 1000, // 8 hours
      24 * 60 * 60 * 1000, // 24 hours
      2 * 24 * 60 * 60 * 1000, // 2 days
      7 * 24 * 60 * 60 * 1000, // 7 days
      30 * 24 * 60 * 60 * 1000 // 30 days
    ];

    $scope.openCustomRange = function () {
      modals.display('#customDateRangeModal', true);

      var icons = {
        time: 'fa fa-clock-o',
        date: 'fa fa-calendar',
        up: 'fa fa-chevron-up',
        down: 'fa fa-chevron-down',
        previous: 'fa fa-chevron-left',
        next: 'fa fa-chevron-right'
      };
      var from = $scope.chartFrom;
      var to = $scope.chartTo;
      $('#customDateRangeFromDate').datetimepicker({
        icons: icons,
        format: 'L'
      });
      $('#customDateRangeFromTime').datetimepicker({
        icons: icons,
        format: 'LT'
      });
      $('#customDateRangeToDate').datetimepicker({
        icons: icons,
        format: 'L'
      });
      $('#customDateRangeToTime').datetimepicker({
        icons: icons,
        format: 'LT'
      });
      $('#customDateRangeFromDate').data('DateTimePicker').date(moment(from).startOf('day'));
      $('#customDateRangeFromTime').data('DateTimePicker').date(moment(from));
      $('#customDateRangeToDate').data('DateTimePicker').date(moment(to).startOf('day'));
      $('#customDateRangeToTime').data('DateTimePicker').date(moment(to));
      // don't focus on first input as that makes esc not work, plus likely to use date picker anyways
    };

    $scope.applyCustomDateRange = function () {
      function timeComponent(dateTime) {
        var startOfDay = dateTime.clone().startOf('day');
        return dateTime.valueOf() - startOfDay;
      }

      var fromDate = $('#customDateRangeFromDate').data('DateTimePicker').date();
      var fromTime = $('#customDateRangeFromTime').data('DateTimePicker').date();
      var toDate = $('#customDateRangeToDate').data('DateTimePicker').date();
      var toTime = $('#customDateRangeToTime').data('DateTimePicker').date();
      $scope.$parent.chartFrom = fromDate + timeComponent(fromTime);
      $scope.$parent.chartTo = toDate + timeComponent(toTime);
      $scope.$parent.last = 0;
      $scope.$parent.chartRefresh++;
      $('#customDateRangeModal').modal('hide');
    };
  }
]);
