/*
 * Copyright 2013 the original author or authors.
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
$(document).ready(function () {
  'use strict';
  Informant.configureAjaxError();
  var plot, normalPoints, errorPoints, activePoints, highlightedItemId, summaryItem;
  var limitExceeded;
  var plotSelecting;
  // previousFrom and previousTo are needed for tracking whether scroll wheel zoom is in or out
  var previousFrom, previousTo;
  var modalVanishPoint;
  var refreshQueryString;
  var loadDetailId;
  var options = {
    legend: { show: false },
    series: {
      points: { show: true }
    },
    grid: { hoverable: true, clickable: true },
    xaxis: { mode: 'time' },
    yaxis: { ticks: 10, zoomRange: false },
    zoom: { interactive: true, amount: 1.5 },
    colors: ['#edc240', '#cb4b4b', '#afd8f8'],
    selection: { mode: 'xy' }
  };

  var $body = $('body');
  var $chart = $('#chart');

  // qtip adds some code to the beginning of jquery's cleanData function which causes the trace
  // detail modal to close slowly when it has 5000 spans
  // this extra cleanup code is not needed anyways since cleanup is performed explicitly
  $.cleanData = $.cleanData_replacedByqTip;
  // need to track dimensions to identify real window resizes in IE which sends window
  // resize event when any element on the page is resized
  // also, now with responsive design, body width doesn't change on every window resize event
  // so body dimensions are tracked instead of window dimensions since that is what determines
  // plot dimensions
  var bodyWidth = $body.width();
  var bodyHeight = $body.height();
  $(window).resize(function () {
    // check plot in case this is a resize before initial plot is rendered
    if (plot && ($body.width() !== bodyWidth || $body.height() !== bodyHeight)) {
      bodyWidth = $body.width();
      bodyHeight = $body.height();
      plot = $.plot($chart, [normalPoints, errorPoints, activePoints], options);
    }
  });

  function plotResponseData(from, to) {
    // update time filter before translating range to timezone-less flot values
    updateTimeFilter(from, to);
    var fromAsDate = new Date(from);
    fromAsDate.setHours(0, 0, 0, 0);
    var zoomRangeFrom = fromAsDate.getTime();
    var zoomRangeTo = zoomRangeFrom + 24 * 60 * 60 * 1000; // zoomRangeFrom + 24 hours
    from -= new Date(from).getTimezoneOffset() * 60 * 1000;
    to -= new Date(to).getTimezoneOffset() * 60 * 1000;
    zoomRangeFrom -= new Date(zoomRangeFrom).getTimezoneOffset() * 60 * 1000;
    zoomRangeTo -= new Date(zoomRangeTo).getTimezoneOffset() * 60 * 1000;
    options.xaxis.min = from;
    options.xaxis.max = to;
    options.xaxis.zoomRange = [ zoomRangeFrom, zoomRangeTo ];
    options.yaxis.min = 0;
    // reset yaxis max so it will be auto calculated to fit data points
    options.yaxis.max = undefined;
    hideTooltip();
    if (plot) {
      plot.unhighlight();
    }
    plot = $.plot($chart, [normalPoints, errorPoints, activePoints], options);
    if (highlightedItemId) {
      // re-highlight if possible
      highlightPoint(highlightedItemId);
    }
  }

  function hideTooltip() {
    Informant.hideSpinner('#tooltipSpinner');
    $chart.qtip('hide');
  }

  function filterPoints(points, from, to, low, high) {
    var filteredPoints = [];
    // points are in timezone-less flot values
    from -= new Date(from).getTimezoneOffset() * 60 * 1000;
    to -= new Date(to).getTimezoneOffset() * 60 * 1000;
    var i;
    for (i = 0; i < points.length; i++) {
      var point = points[i];
      if (point[0] >= from && point[0] <= to && point[1] >= low && point[1] <= high) {
        filteredPoints.push(point);
      }
    }
    return filteredPoints;
  }

  function updateTimeFilter(from, to) {
    // TODO use localized time format
    // currently momentjs provides only 'LT' for localized time but this does not include seconds
    // see http://momentjs.com/docs/#/customization/long-date-formats
    $('#timeFilter').html(moment(from).format('h:mm:ss A') + ' &nbsp; to &nbsp; '
        + moment(to).format('h:mm:ss A (Z)'));
    previousFrom = from;
    previousTo = to;
  }

  function refresh() {
    // TODO use localized date format, see more detailed comment at datepicker construction since
    // the format needs to be sync'd between the date picker and this parsing
    var date = moment($('#dateFilter').val(), 'MM/DD/YYYY');
    var from;
    var to;
    if (plot) {
      from = moment(Math.floor(plot.getAxes().xaxis.min));
      to = moment(Math.ceil(plot.getAxes().xaxis.max));
      // shift timezone
      from.add('minutes', from.zone());
      to.add('minutes', to.zone());
      var fromAsDate = from.clone();
      fromAsDate.hours(0);
      fromAsDate.minutes(0);
      fromAsDate.seconds(0);
      fromAsDate.milliseconds(0);
      if (date.valueOf() === fromAsDate.valueOf()) {
        // dateFilter hasn't changed
        from = from.valueOf();
        to = to.valueOf();
      } else {
        // dateFilter has changed
        from = date.valueOf();
        to = date.valueOf() + 24 * 60 * 60 * 1000;
      }
    } else {
      // plot.getAxes() is not yet available because the refresh button was hit refresh during
      // (a possibly very long) initial load and
      from = date.valueOf();
      to = date.valueOf() + 24 * 60 * 60 * 1000;
    }
    getTracePoints(from, to, $('#limitFilter').val(), buildQueryString(), true);
  }

  function buildQueryString() {
    var queryString = '';
    var durationLow = $('#durationLow').val() * 1000;
    if (durationLow) {
      queryString += '&low=' + durationLow;
    }
    var durationHigh = $('#durationHigh').val() * 1000;
    if (durationHigh) {
      queryString += '&high=' + durationHigh;
    }
    if ($('#errorOnlyFilter').is(':checked')) {
      queryString += '&error-only=true';
    }
    if ($('#fineOnlyFilter').is(':checked')) {
      queryString += '&fine-only=true';
    }
    var headlineComparator = $('#headlineComparator').val();
    var headline = $('#headlineFilter').val();
    if (headline) {
      queryString += '&headline-comparator=' + headlineComparator;
      queryString += '&headline=' + headline;
    }
    var userIdComparator = $('#userIdComparator').val();
    var userId = $('#userIdFilter').val();
    if (userId) {
      queryString += '&user-id-comparator=' + userIdComparator;
      queryString += '&user-id=' + userId;
    }
    var background = $('#backgroundFilter').val();
    if (background) {
      queryString += '&background=' + background;
    }
    return queryString;
  }

  function getTracePoints(from, to, limit, queryString, refreshButton, delay) {
    var fullQueryString = 'from=' + from + '&to=' + to + '&limit=' + limit + queryString;
    // handle crazy user clicking on the button
    if (refreshButton && fullQueryString === refreshQueryString) {
      return;
    }
    if (!refreshQueryString) {
      // if refreshQueryString is defined, that means spinner is already showing
      Informant.showSpinner('#chartSpinner');
    }
    refreshQueryString = fullQueryString;
    if (delay) {
      setTimeout(function () {
        if (refreshQueryString === fullQueryString) {
          // still the current query
          getTracePoints(from, to, limit, queryString, refreshButton, false);
        }
      }, delay);
      return;
    }
    $.getJSON('explorer/points?' + fullQueryString, function (response) {
      if (refreshQueryString !== fullQueryString) {
        // a different query string has been posted since this one
        // (or the refresh was 'canceled' by a zoom-in action that doesn't require data loading)
        return;
      }
      refreshQueryString = undefined;
      Informant.hideSpinner('#chartSpinner');
      if (refreshButton) {
        Informant.showAndFadeSuccessMessage('#refreshSuccessMessage');
      }
      normalPoints = response.normalPoints;
      errorPoints = response.errorPoints;
      activePoints = response.activePoints;
      limitExceeded = response.limitExceeded;
      if (response.limitExceeded) {
        $('#limit').text(limit);
        $('#limitExceeded').removeClass('hide');
      } else {
        $('#limitExceeded').addClass('hide');
      }
      hideTooltip();
      // shift for timezone
      var i;
      for (i = 0; i < normalPoints.length; i++) {
        normalPoints[i][0] -= new Date(normalPoints[i][0]).getTimezoneOffset() * 60 * 1000;
      }
      for (i = 0; i < errorPoints.length; i++) {
        errorPoints[i][0] -= new Date(errorPoints[i][0]).getTimezoneOffset() * 60 * 1000;
      }
      for (i = 0; i < activePoints.length; i++) {
        activePoints[i][0] -= new Date(activePoints[i][0]).getTimezoneOffset() * 60 * 1000;
      }
      plotResponseData(from, to);
    });
  }

  function itemId(item) {
    if (item.seriesIndex === 0) {
      return normalPoints[item.dataIndex][2];
    }
    if (item.seriesIndex === 1) {
      return errorPoints[item.dataIndex][2];
    }
    return activePoints[item.dataIndex][2];
  }

  function highlightPoint(id) {
    var i;
    for (i = 0; i < normalPoints.length; i++) {
      if (normalPoints[i][2] === id) {
        plot.highlight(0, i);
        return;
      }
    }
    for (i = 0; i < errorPoints.length; i++) {
      if (errorPoints[i][2] === id) {
        plot.highlight(1, i);
        return;
      }
    }
    for (i = 0; i < activePoints.length; i++) {
      if (activePoints[i][2] === id) {
        plot.highlight(2, i);
        return;
      }
    }
  }

  function showTraceDetailTooltip(item) {
    var x = item.pageX;
    var y = item.pageY;
    var spinner = new Spinner({ lines: 10, width: 3, radius: 6, top: 2, left: 2 });

    function displaySpinner() {
      if (spinner) {
        var html = '<div class="inlineblock" id="tooltipSpinner" style="width: 35px;'
            + ' height: 31px;"></div>';
        $chart.qtip({
          content: {
            text: html
          },
          position: {
            my: 'left center',
            target: [ x, y + 5 ],
            viewport: $chart
          },
          hide: {
            event: 'unfocus'
          },
          show: {
            event: false
          },
          events: {
            hide: function () {
              summaryItem = null;
            }
          }
        });
        $chart.qtip('show');
        spinner.spin($('#tooltipSpinner').get(0));
      }
    }

    // small delay so that if there is an immediate response the spinner doesn't blink
    setTimeout(displaySpinner, 100);
    var id = itemId(item);
    summaryItem = item;
    modalVanishPoint = [x, y];
    var localSummaryItem = summaryItem;
    $.getJSON('explorer/summary/' + id, function (response) {
      spinner.stop();
      spinner = null;
      // intentionally not using itemEquals() here to avoid edge case where user clicks on item
      // then zooms and clicks on the same item. if the first request comes back after the second
      // click, then itemEquals() will be true and it will pop up the correct summary, but at the
      // incorrect location
      if (localSummaryItem !== summaryItem) {
        // too slow, the user has moved on to another summary already
        return;
      }
      var text;
      var summaryTrace;
      if (response.expired) {
        text = 'expired';
      } else {
        summaryTrace = response;
        var html = Trace.traceSummaryTemplate(summaryTrace);
        var showDetailHtml = '<div style="margin-top: 0.5em;">'
            + '<button class="realbtn red pad1" id="showDetail" style="font-size: 12px;">'
            + 'show detail</button></div>';
        text = '<div class="indent1">' + html + '</div>' + showDetailHtml;
      }
      $chart.qtip({
        content: {
          text: text
        },
        position: {
          my: 'left center',
          target: [ x, y + 5 ],
          viewport: $(window)
        },
        style: {
          classes: 'ui-tooltip-bootstrap qtip-override qtip-border-color-' + item.seriesIndex
        },
        hide: {
          event: 'unfocus'
        },
        show: {
          event: false
        },
        events: {
          hide: function () {
            summaryItem = null;
          }
        }
      });
      $chart.qtip('show');
      $('#showDetail').click(function () {
        // handle crazy user clicking on the 'show detail' link
        if (id === loadDetailId) {
          return false;
        }
        loadDetailId = id;
        summaryTrace.showExport = true;
        var summaryHtml = '<div class="indent1">' + Trace.traceSummaryTemplate(summaryTrace)
            + '</div><br><div class="indent2"><div class="button-spinner hide" id="detailSpinner"'
            + ' style="margin-left: 0px; margin-top: 30px;"></div></div>';
        var $qtip = $('.qtip');
        var initialFixedOffset = {
          top: $qtip.offset().top - $(window).scrollTop(),
          left: $qtip.offset().left - $(window).scrollLeft()
        };
        var initialWidth = $qtip.width();
        var initialHeight = $qtip.height();
        $chart.qtip('hide');
        displayModal(summaryHtml, initialFixedOffset, initialWidth, initialHeight);
        $.getJSON('trace/detail/' + id, function (response) {
          if (loadDetailId !== id) {
            // a different id has been posted since this one
            return;
          }
          loadDetailId = undefined;
          var html;
          if (response.expired) {
            html = 'expired';
          } else {
            Trace.detailTrace = response;
            Trace.detailTrace.showExport = true;
            html = '<div class="indent1">' + Trace.traceSummaryTemplate(Trace.detailTrace)
                + '</div><br>' + Trace.traceDetailTemplate(Trace.detailTrace);
          }
          Informant.hideSpinner('#detailSpinner');
          $('#modalContent').html(html);
        });
        return false;
      });
    });
  }

  function displayModal(initialHtml, initialFixedOffset, initialWidth, initialHeight) {
    var $modalContent = $('#modalContent');
    var $modal = $('#modal');
    $modalContent.html(initialHtml);
    $modal.removeClass('hide');
    // need to focus on something inside the modal, otherwise keyboard events won't be captured,
    // in particular, page up / page down won't scroll the modal
    $modalContent.focus();
    $modal.css('position', 'fixed');
    $modal.css('top', initialFixedOffset.top);
    $modal.css('left', initialFixedOffset.left);
    $modal.width(initialWidth);
    $modal.height(initialHeight);
    $modal.css('margin', 0);
    $modal.css('background-color', '#eee');
    $modal.css('font-size', '12px');
    $modal.css('line-height', '16px');
    $modal.modal({ 'show': true, 'keyboard': false, 'backdrop': false });
    var width = $(window).width() - 50;
    var height = $(window).height() - 50;
    $modal.animate({
      left: '25px',
      top: '25px',
      width: width + 'px',
      height: height + 'px',
      backgroundColor: '#fff',
      fontSize: '14px',
      lineHeight: '20px'
    }, 400, function () {
      if (loadDetailId) {
        // show spinner after animation, and only if still waiting for content
        Informant.showSpinner('#detailSpinner');
      }
      // this is needed to prevent the background from scrolling
      // wait until animation is complete since removing scrollbar makes the background page shift
      $body.css('overflow', 'hidden');
      // hiding the flot chart is needed to prevent a strange issue in chrome that occurs when
      // expanding a section of the details to trigger vertical scrollbar to be active, then
      // scroll a little bit down, leaving the section header visible, then click the section
      // header to collapse the section (while still scrolled down a bit from the top) and the
      // whole modal will shift down and to the right 25px in each direction (only in chrome)
      //
      // and without hiding flot chart there is another problem in chrome, in smaller browser
      // windows it causes the vertical scrollbar to get offset a bit left and upwards
      $chart.hide();
    });
    $body.append('<div class="modal-backdrop" id="modalBackdrop"></div>');
    var $modalBackdrop = $('#modalBackdrop');
    $modalBackdrop.css('background-color', '#ddd');
    $modalBackdrop.css('opacity', 0);
    $modalBackdrop.animate({
      'opacity': 0.8
    }, 400);
  }

  function hideModal() {
    // just in case spinner is still showing
    Informant.hideSpinner('#detailSpinner');
    // reset overflow so the background can scroll again
    $body.css('overflow', '');
    // re-display flot chart
    $chart.show();
    // remove large dom content first since it makes animation jerky at best
    // (and need to remove it afterwards anyways to clean up the dom)
    $('#modalContent').empty();
    var $modal = $('#modal');
    $modal.animate({
      left: (modalVanishPoint[0] - $(window).scrollLeft()) + 'px',
      top: (modalVanishPoint[1] - $(window).scrollTop()) + 'px',
      width: 0,
      height: 0,
      backgroundColor: '#eee'
    }, 200, function () {
      $modal.addClass('hide');
      $modal.modal('hide');
    });
    var $modalBackdrop = $('#modalBackdrop');
    $modalBackdrop.animate({
      'opacity': 0
    }, 200, function () {
      $modalBackdrop.remove();
    });
  }

  $chart.bind('plotzoom', function (event, plot) {
    var from = Math.floor(plot.getAxes().xaxis.min);
    var to = Math.ceil(plot.getAxes().xaxis.max);
    // convert points out of timezone-less flot values
    from += new Date(from).getTimezoneOffset() * 60 * 1000;
    to += new Date(to).getTimezoneOffset() * 60 * 1000;
    var zoomingOut = from < previousFrom || to > previousTo;
    if (zoomingOut) {
      // scroll zooming out, reset duration limits
      $('#durationLow').val('');
      $('#durationHigh').val('');
      $('#durationComparator').val('greater').change();
    }
    if (limitExceeded || zoomingOut) {
      // set delay=50 to handle rapid zooming
      getTracePoints(from, to, $('#limitFilter').val(), buildQueryString(), false, 50);
    } else {
      // no need to hit server
      // cancel any refresh in action
      if (refreshQueryString) {
        refreshQueryString = undefined;
        Informant.hideSpinner('#chartSpinner');
      }
      normalPoints = filterPoints(normalPoints, from, to, 0, Number.MAX_VALUE);
      errorPoints = filterPoints(errorPoints, from, to, 0, Number.MAX_VALUE);
      activePoints = filterPoints(activePoints, from, to, 0, Number.MAX_VALUE);
      plotResponseData(from, to);
    }
  });
  $chart.mousedown(function () {
    hideTooltip();
  });
  $(document).keyup(function (e) {
    if (e.keyCode === 27) { // esc key
      if ($('#modal').is(':visible')) {
        hideModal();
      } else if (plotSelecting) {
        plot.clearSelection();
        cancelingPlotSelection = true;
      } else if (summaryItem) {
        // the tooltips (spinny and summary) have hide events that set summaryItem = null
        // so summaryItem must be checked before calling hideTooltip()
        hideTooltip();
      } else {
        // hitting esc when no item
        highlightedItemId = undefined;
        plot.unhighlight();
      }
    }
  });
  $chart.bind('plothover', function (event, pos, item) {
    if (plotSelecting && item && itemId(item) !== highlightedItemId) {
      plot.unhighlight(item.series, item.datapoint);
    }
  });
  $chart.bind('plotclick', function (event, pos, item) {
    if (item) {
      highlightedItemId = itemId(item);
      plot.unhighlight();
      // TODO highlight with bolder or larger outline
      plot.highlight(item.series, item.datapoint);
      showTraceDetailTooltip(item);
    }
  });
  var cancelingPlotSelection;
  $(document).mousedown(function () {
    // need to reset this variable at some point, now seems good
    cancelingPlotSelection = false;
  });
  $chart.bind('plotselecting', function (event, ranges) {
    if (ranges) {
      // plotselecting events are triggered with null ranges parameter when '!selectionIsSane()'
      // (see jquery.flot.selection.js)
      plotSelecting = true;
    }
  });
  $chart.bind('plotunselected', function () {
    plotSelecting = false;
  });
  $chart.bind('plotselected', function (event, ranges) {
    if (cancelingPlotSelection) {
      // unfortunately, plotselected is still called after plot.clearSelection() in the keyup
      // event handler for the esc key
      plot.clearSelection();
      return;
    }
    plotSelecting = false;
    var from = Math.floor(ranges.xaxis.from);
    var to = Math.ceil(ranges.xaxis.to);
    from += new Date(from).getTimezoneOffset() * 60 * 1000;
    to += new Date(to).getTimezoneOffset() * 60 * 1000;
    // round min/max to the nearest millisecond in outward direction so that they encompass the
    // requested selection
    var low = Math.floor(ranges.yaxis.from * 1000) / 1000;
    var high = Math.ceil(ranges.yaxis.to * 1000) / 1000;
    if (low === 0) {
      $('#durationLow').val('');
      $('#durationHigh').val(high);
      $('#durationComparator').val('less').change();
    } else {
      $('#durationLow').val(low);
      $('#durationHigh').val(high);
      $('#durationComparator').val('between').change();
    }
    if (limitExceeded) {
      getTracePoints(from, to, $('#limitFilter').val(), buildQueryString(), false);
    } else {
      // no need to hit server
      // cancel any refresh in action
      if (refreshQueryString) {
        refreshQueryString = undefined;
        Informant.hideSpinner('#chartSpinner');
      }
      normalPoints = filterPoints(normalPoints, from, to, low, high);
      errorPoints = filterPoints(errorPoints, from, to, low, high);
      activePoints = filterPoints(activePoints, from, to, low, high);
      plotResponseData(from, to);
    }
  });

  var $toggleExtraFiltersButton = $('#toggleExtraFiltersButton');
  $toggleExtraFiltersButton.click(function (e) {
    var $extraFilters = $('#extraFilters');
    $extraFilters.toggleClass('hide');
    if ($extraFilters.hasClass('hide')) {
      $toggleExtraFiltersButton.html('more filters');
    } else {
      $toggleExtraFiltersButton.html('less filters');
    }
    // without preventDefault, click triggers form submission
    e.preventDefault();
  });
  var now = new Date();
  var today = new Date();
  today.setHours(0, 0, 0, 0);
  // TODO use bootstrap-datepicker momentjs backend when it's available and then use momentjs's
  // localized format 'moment.longDateFormat.L' both here and when parsing date
  // see https://github.com/eternicode/bootstrap-datepicker/issues/24
  var $dateFilter = $('#dateFilter');
  $dateFilter.val(moment(today).format('MM/DD/YYYY'));
  $dateFilter.datepicker({format: 'mm/dd/yyyy', autoclose: true, todayHighlight: true});
  var $durationComparator = $('#durationComparator');
  $durationComparator.change(function () {
    if ($durationComparator.val() === 'greater') {
      $('#durationLowDiv').removeClass('hide');
      $('#durationAndDiv').addClass('hide');
      $('#durationHighDiv').addClass('hide');
      $('#durationHigh').val('');
    } else if ($durationComparator.val() === 'less') {
      $('#durationLowDiv').addClass('hide');
      $('#durationLow').val('');
      $('#durationAndDiv').addClass('hide');
      $('#durationHighDiv').removeClass('hide');
    } else if ($durationComparator.val() === 'between') {
      $('#durationLowDiv').removeClass('hide');
      $('#durationAndDiv').removeClass('hide');
      $('#durationHighDiv').removeClass('hide');
    }
  });
  $('#refreshButton').click(refresh);
  $(".refresh-data-on-enter-key").keypress(function (event) {
    if (event.which === 13) {
      refresh();
      // without preventDefault, enter triggers 'more filters' button
      event.preventDefault();
    }
  });
  $('#zoomOut').click(function () { plot.zoomOut(); });
  $('#modalHide').click(hideModal);
  // get the last 2 hours, but nothing prior to today (e.g. if 'now' is 1am)
  var from = Math.max(today.getTime(), now.getTime() - 2 * 60 * 60 * 1000);
  // now + 15 minutes, this is primarily to set the right-hand border for the chart
  var to = now.getTime() + 15 * 60 * 1000;
  getTracePoints(from, to, 500, '', false);
});
