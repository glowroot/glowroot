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

// IMPORTANT: DO NOT USE ANGULAR IN THIS FILE
// that would require adding angular to trace-export.js
// and that would significantly increase the size of the exported trace files

// Glowroot dependency is used for spinner, but is not used in export file
// angular dependency is used to call login.goToLogin() on 401 responses, but is not used in export file
/* global $, Handlebars, JST, moment, Glowroot, angular, SqlPrettyPrinter, gtClipboard, gtParseIncludesExcludes, console */

// IMPORTANT: DO NOT USE ANGULAR IN THIS FILE
// that would require adding angular to trace-export.js
// and that would significantly increase the size of the exported trace files

var HandlebarsRendering;

// showing ellipsed node markers makes the rendered profile tree much much larger since it cannot contract otherwise
// identical branches, and this makes navigation more difficult, as well as makes rendering much slower
var SHOW_ELLIPSED_NODE_MARKERS = false;

HandlebarsRendering = (function () {
  // indent1 must be sync'd with $indent1 variable in common-trace.less
  var indent1 = 8.41; // px
  var indent2 = indent1 * 2; // px

  var monospaceCharWidth = 8.41;

  var traceEntryLineLength;
  var traceEntryBarWidth = 50;
  var traceDurationNanos;

  var flattenedTraceEntries;

  Handlebars.registerHelper('eachKeyValuePair', function (map, options) {
    var buffer = '';
    if (map) {
      $.each(map, function (key, values) {
        $.each(values, function (index, value) {
          buffer += options.fn({key: key, value: value});
        });
      });
    }
    return buffer;
  });

  function traverseTimers(timers, callback) {
    function traverse(timer, depth) {
      callback(timer, depth);
      if (timer.childTimers) {
        $.each(timer.childTimers, function (index, childTimer) {
          traverse(childTimer, depth + 1);
        });
      }
    }

    // add the root node(s)
    if ($.isArray(timers)) {
      $.each(timers, function (index, timer) {
        traverse(timer, 0);
      });
    } else {
      traverse(timers, 0);
    }
  }

  function initTotalNanosList(breakdown) {
    if (!breakdown.treeTimers) {
      // e.g. no auxiliary threads
      return;
    }
    breakdown.treeTotalNanosList = buildTotalNanosList(breakdown.treeTimers);
    breakdown.flattenedTotalNanosList = buildTotalNanosList(breakdown.flattenedTimers);
  }

  function buildTotalNanosList(timers) {
    var totalNanosList = [];
    traverseTimers(timers, function (timer) {
      totalNanosList.push(timer.totalNanos);
    });
    totalNanosList.sort(function (a, b) {
      return b - a;
    });
    return totalNanosList;
  }

  function initOneLimit(breakdown, timers, totalNanosList) {
    var limit = Math.min(breakdown.limit, totalNanosList.length);
    var thresholdNanos = totalNanosList[limit - 1];
    var count = 0;
    traverseTimers(timers, function (timer) {
      // count is to handle multiple timers equal to the threshold
      timer.show = timer.totalNanos >= thresholdNanos && count++ < breakdown.limit;
    });
  }

  function updateOneLimit(breakdown, prefix, timers) {
    traverseTimers(timers, function (timer) {
      if (timer.show) {
        $('#' + breakdown.prefix + prefix + timer.id).show();
      } else {
        $('#' + breakdown.prefix + prefix + timer.id).hide();
      }
    });
  }

  function initLimit(breakdown) {
    breakdown.ftShowMore = breakdown.limit < breakdown.flattenedTimers.length;
    breakdown.ttShowMore = breakdown.limit < breakdown.timers.length;
    breakdown.showLess = breakdown.limit !== 10;
    initOneLimit(breakdown, breakdown.treeTimers, breakdown.treeTotalNanosList);
    initOneLimit(breakdown, breakdown.flattenedTimers, breakdown.flattenedTotalNanosList);
  }

  function updateLimit(breakdown) {

    initLimit(breakdown);

    // note: multiple focus calls ok since calling focus on hidden element (e.g. flattened timers) has no effect
    if (!breakdown.ftShowMore) {
      // re-focus on visible element, otherwise up/down/pgup/pgdown/ESC don't work
      $('#' + breakdown.prefix + 'ftForFocus').focus();
    }
    if (!breakdown.ttShowMore) {
      // re-focus on visible element, otherwise up/down/pgup/pgdown/ESC don't work
      $('#' + breakdown.prefix + 'ttForFocus').focus();
    }
    if (!breakdown.showLess) {
      // re-focus on visible element, otherwise up/down/pgup/pgdown/ESC don't work
      $('#' + breakdown.prefix + 'ttForFocus').focus();
      $('#' + breakdown.prefix + 'ftForFocus').focus();
    }

    setTimeout(function () {
      updateOneLimit(breakdown, 'tt', breakdown.treeTimers);
      updateOneLimit(breakdown, 'ft', breakdown.flattenedTimers);

      $('#' + breakdown.prefix + 'ttShowMore').toggle(breakdown.ttShowMore);
      $('#' + breakdown.prefix + 'ttShowAll').toggle(breakdown.ttShowMore);

      $('#' + breakdown.prefix + 'ftShowMore').toggle(breakdown.ftShowMore);
      $('#' + breakdown.prefix + 'ftShowAll').toggle(breakdown.ftShowMore);

      $('#' + breakdown.prefix + 'ttShowLess').toggle(breakdown.showLess);
      $('#' + breakdown.prefix + 'ftShowLess').toggle(breakdown.showLess);

      $('#' + breakdown.prefix + 'ttShowMoreAndLess').toggle(breakdown.ttShowMore && breakdown.showLess);
      $('#' + breakdown.prefix + 'ftShowMoreAndLess').toggle(breakdown.ftShowMore && breakdown.showLess);
    });
  }

  function initTimers(breakdown) {
    if (!breakdown.treeTimers) {
      // e.g. no auxiliary threads
      return;
    }
    var nextId = 0;
    var timers = [];

    traverseTimers(breakdown.treeTimers, function (timer, depth) {
      timer.id = nextId++;
      timer.depth = depth;
      timers.push(timer);
      if (timer.childTimers) {
        timer.childTimers.sort(function (a, b) {
          return b.totalNanos - a.totalNanos;
        });
      }
    });
    breakdown.timers = timers;
  }

  function initFlattenedTimers(breakdown) {
    if (!breakdown.treeTimers) {
      // e.g. no auxiliary threads
      return;
    }
    var nextId = 0;
    var flattenedTimerMap = {};
    var flattenedTimers = [];

    function traverse(timer, parentTimerNames) {
      var flattenedTimer = flattenedTimerMap[timer.name];
      if (!flattenedTimer) {
        flattenedTimer = {
          name: timer.name,
          totalNanos: timer.totalNanos,
          count: timer.count,
          active: timer.active,
          id: nextId++
        };
        flattenedTimerMap[timer.name] = flattenedTimer;
        flattenedTimers.push(flattenedTimer);
      } else if (parentTimerNames.indexOf(timer.name) === -1) {
        // only add to existing flattened timer if the timer isn't appearing under itself
        // (this is possible when they are separated by another timer)
        flattenedTimer.totalNanos += timer.totalNanos;
        flattenedTimer.active = flattenedTimer.active || timer.active;
        flattenedTimer.count += timer.count;
      }
      if (timer.childTimers) {
        $.each(timer.childTimers, function (index, nestedTimer) {
          traverse(nestedTimer, parentTimerNames.concat(timer.name));
        });
      }
    }

    // add the root node(s)
    if ($.isArray(breakdown.treeTimers)) {
      $.each(breakdown.treeTimers, function (index, item) {
        traverse(item, []);
      });
    } else {
      traverse(breakdown.treeTimers, []);
    }

    flattenedTimers.sort(function (a, b) {
      return b.totalNanos - a.totalNanos;
    });

    breakdown.flattenedTimers = flattenedTimers;
  }

  Handlebars.registerHelper('ifNonEmptyObject', function (value, options) {
    if (value && !$.isEmptyObject(value)) {
      return options.fn(this);
    }
    return options.inverse(this);
  });

  // allows empty string ""
  Handlebars.registerHelper('ifDisplayMessage', function (traceEntry, options) {
    if (traceEntry.message || traceEntry.queryMessage || !traceEntry.error) {
      return options.fn(this);
    }
    return options.inverse(this);
  });

  Handlebars.registerHelper('ifNotNA', function (value, options) {
    if (value !== -1) {
      return options.fn(this);
    }
    return options.inverse(this);
  });

  Handlebars.registerHelper('formatAllocatedBytes', function (bytes) {
    return formatBytes(bytes);
  });

  Handlebars.registerHelper('ifNotOne', function (num, options) {
    if (num !== 1) {
      return options.fn(this);
    }
    return options.inverse(this);
  });

  Handlebars.registerHelper('date', function (timestamp) {
    return moment(timestamp).format('YYYY-MM-DD h:mm:ss.SSS a (Z)');
  });

  Handlebars.registerHelper('nanosToMillis', function (nanos) {
    if (nanos) {
      return formatMillis(nanos / 1000000);
    } else {
      // protobuf trace entries do not json serialize 0 values, so they are undefined but should be rendered as zero
      return formatMillis(0);
    }
  });

  Handlebars.registerHelper('formatInteger', function (value) {
    return value.toLocaleString();
  });

  Handlebars.registerHelper('ifExistenceYes', function (existence, options) {
    if (existence === 'yes') {
      return options.fn(this);
    }
    return options.inverse(this);
  });

  Handlebars.registerHelper('ifExistenceExpired', function (existence, options) {
    if (existence === 'expired') {
      return options.fn(this);
    }
    return options.inverse(this);
  });

  Handlebars.registerHelper('ifExistenceNo', function (existence, options) {
    if (existence === 'no') {
      return options.fn(this);
    }
    return options.inverse(this);
  });

  Handlebars.registerHelper('ifAnyThreadInfo', function (trace, options) {
    if (trace.threadCpuNanos || trace.threadBlockedNanos || trace.threadWaitedNanos || trace.threadAllocatedBytes) {
      return options.fn(this);
    }
    return options.inverse(this);
  });

  Handlebars.registerHelper('headerDetailHtml', function (detail) {
    return messageDetailHtml(detail, true);
  });

  Handlebars.registerHelper('entryDetailHtml', function (detail) {
    return messageDetailHtml(detail);
  });

  Handlebars.registerHelper('initialMftStyle', function (id) {
    if (id > 10) {
      return ' style="display: none;"';
    } else {
      return '';
    }
  });

  function messageDetailHtml(detail, bold) {
    function maybeBoldPropName(propName) {
      if (bold) {
        return '<span class="gt-bold">' + propName + ':</span>';
      } else {
        return propName + ':';
      }
    }

    var ret = '';
    $.each(detail, function (propName, propVal) {
      if ($.isArray(propVal)) {
        // array values are supported to simulate multimaps, e.g. for http request parameters and http headers, both
        // of which can have multiple values for the same key
        $.each(propVal, function (i, propv) {
          var subdetail = {};
          subdetail[propName] = propv;
          ret += messageDetailHtml(subdetail, bold);
        });
      } else if (typeof propVal === 'object' && propVal !== null) {
        ret += maybeBoldPropName(propName) + '<br><div class="gt-indent2">' + messageDetailHtml(propVal) + '</div>';
      } else {
        // outer div with clearfix is needed when propVal is empty
        ret += '<div class="clearfix"><div style="float: left;">' + maybeBoldPropName(propName) + '&nbsp;</div>'
            + '<div class="gt-trace-attr-value">' + propVal + '</div></div>';
      }
    });
    return ret;
  }

  Handlebars.registerHelper('ifLongMessage', function (traceEntry, options) {
    var messageLength;
    if (traceEntry.queryMessage) {
      var sharedQueryText = traceEntry.queryMessage.sharedQueryText;
      if (sharedQueryText.fullTextSha1) {
        // query text is truncated, this is a "long message"
        messageLength = traceEntryLineLength + 1;
      } else {
        messageLength = traceEntry.message.length;
      }
    } else {
      messageLength = traceEntry.message.length;
    }
    if (messageLength > traceEntryLineLength) {
      return options.fn(this);
    }
    return options.inverse(this);
  });

  Handlebars.registerHelper('ifSqlMessage', function (message, options) {
    if (message.lastIndexOf('jdbc execution: ', 0) === 0) {
      return options.fn(this);
    }
    return options.inverse(this);
  });

  Handlebars.registerHelper('errorIndentClass', function (message) {
    if (message) {
      return 'gt-indent6';
    }
    return 'gt-indent2';
  });

  Handlebars.registerHelper('exceptionIndentClass', function (message) {
    if (message) {
      return 'gt-indent2';
    }
    return 'gt-indent4';
  });

  Handlebars.registerHelper('traceEntryIndentPx', function (traceEntry) {
    return 2 * monospaceCharWidth * (traceEntry.depth + 1);
  });

  Handlebars.registerHelper('timerIndent', function (timer) {
    return indent1 * timer.depth;
  });

  Handlebars.registerHelper('traceEntryBarLeft', function (startOffsetNanos) {
    var left = Math.floor(startOffsetNanos * traceEntryBarWidth / traceDurationNanos);
    // Math.min is in case startOffsetNanos is equal to traceDurationNanos
    return 2 * Math.min(left, traceEntryBarWidth - 1);
  });

  Handlebars.registerHelper('traceEntryBarWidth', function (traceEntry) {
    var left = Math.floor(traceEntry.startOffsetNanos * traceEntryBarWidth / traceDurationNanos);
    var right = Math.floor((traceEntry.startOffsetNanos + traceEntry.durationNanos - 1) * traceEntryBarWidth / traceDurationNanos);
    return 2 * Math.max(1, right - left + 1);
  });

  Handlebars.registerHelper('firstPart', function (traceEntry) {
    var totalChars = traceEntryLineLength - 2 * traceEntry.depth;
    var messageToSlice;
    if (traceEntry.queryMessage) {
      if (traceEntry.queryMessage.sharedQueryText.fullTextSha1) {
        messageToSlice = traceEntry.queryMessage.prefix + traceEntry.queryMessage.sharedQueryText.truncatedText;
      } else {
        messageToSlice = traceEntry.message;
      }
    } else {
      messageToSlice = traceEntry.message;
    }
    return messageToSlice.slice(0, Math.ceil(totalChars / 2));
  });

  Handlebars.registerHelper('lastPart', function (traceEntry) {
    var totalChars = traceEntryLineLength - 2 * traceEntry.depth;
    var messageToSlice;
    if (traceEntry.queryMessage) {
      if (traceEntry.queryMessage.sharedQueryText.fullTextSha1) {
        messageToSlice = traceEntry.queryMessage.sharedQueryText.truncatedEndText + traceEntry.queryMessage.suffix;
      } else {
        messageToSlice = traceEntry.message;
      }
    } else {
      messageToSlice = traceEntry.message;
    }
    return messageToSlice.slice(-Math.floor(totalChars / 2));
  });

  Handlebars.registerHelper('exceptionHtml', function (throwable) {
    // don't pre-wrap stack traces (using overflow-x: auto on container)
    var html = '<div style="white-space: pre;">';
    html += '<div style="font-weight: bold; white-space: pre-wrap;">';
    while (throwable) {
      var message = throwable.message;
      if (message) {
        message = ': ' + message.replace(/\n/g, '\n    ');
      }
      html += escapeHtml(throwable.className + message) + '\n</div>';
      var i;
      for (i = 0; i < throwable.stackTraceElements.length; i++) {
        html += 'at ' + escapeHtml(throwable.stackTraceElements[i]) + '\n';
      }
      if (throwable.framesInCommonWithEnclosing) {
        html += '... ' + throwable.framesInCommonWithEnclosing + ' more\n';
      }
      throwable = throwable.cause;
      if (throwable) {
        html += '\n<div style="font-weight: bold; white-space: pre-wrap;">Caused by: ';
      }
    }
    html += '</div>';
    return html;
  });

  Handlebars.registerHelper('locationStackTraceHtml', function (stackTraceElements) {
    // don't pre-wrap stack traces (using overflow-x: auto on container)
    var html = '<div class="gt-monospace" style="white-space: pre;">';
    var i;
    for (i = 0; i < stackTraceElements.length; i++) {
      html += escapeHtml(stackTraceElements[i]) + '\n';
    }
    html += '</div>';
    return html;
  });

  var mousedownPageX, mousedownPageY;

  $(document).on('mousedown', '.gt-unexpanded-content, .gt-expanded-content', function (e) {
    mousedownPageX = e.pageX;
    mousedownPageY = e.pageY;
  });

  $(document).on('click', '.gt-timers-view-toggle', function () {
    var $timers = $(this).parents('.gt-timers');
    $timers.children('.gt-timers-table').toggleClass('hide');
    // re-focus on visible element, otherwise up/down/pgup/pgdown/ESC don't work
    var $timersViewToggle = $timers.find('.gt-timers-view-toggle:visible');
    $timersViewToggle.attr('tabindex', -1);
    $timersViewToggle.css('outline', 'none');
    $timersViewToggle.focus();
  });

  $(document).on('click', '.gt-unexpanded-content, .gt-expanded-content', function (e, keyboard) {
    smartToggle($(this).parent(), e, keyboard);
  });

  function goToLogin(timedOut) {
    var $injector = angular.element(document.body).injector();
    var $rootScope = $injector.get('$rootScope');
    var login = $injector.get('login');
    $rootScope.$apply(function () {
      if (timedOut) {
        login.goToLogin('Your session has timed out');
      } else {
        login.goToLogin();
      }
    });
  }

  $(document).on('click', '.gt-entries-toggle', function () {
    var $selector = $('#entries');
    if ($selector.data('gtLoading')) {
      // handles rapid clicking when loading from url
      return;
    }
    if (!$selector.data('gtLoaded')) {
      var $traceParent = $(this).parents('.gt-trace-parent');
      var entries = $traceParent.data('gtEntries');
      if (entries) {
        // this is an export file
        $selector.data('gtLoaded', true);
        // first time opening
        initTraceEntryLineLength();
        var sharedQueryTexts = $traceParent.data('gtSharedQueryTexts');
        mergeInSharedQueryTexts(entries, sharedQueryTexts);
        flattenedTraceEntries = flattenTraceEntries(entries);
        // un-hide before building in case there are lots of trace entries, at least can see first few quickly
        $selector.removeClass('hide');
        renderNext(flattenedTraceEntries, 0);
      } else {
        // this is not an export file
        var agentId = $traceParent.data('gtAgentId');
        var traceId = $traceParent.data('gtTraceId');
        var checkLiveTraces = $traceParent.data('gtCheckLiveTraces');
        $selector.data('gtLoading', true);
        var $button = $(this);
        var spinner = Glowroot.showSpinner($button.parent().find('.gt-trace-detail-spinner'));
        var url = 'backend/trace/entries?agent-id=' + encodeURIComponent(agentId) + '&trace-id=' + traceId;
        if (checkLiveTraces) {
          url += '&check-live-traces=true';
        }
        $.get(url)
            .done(function (data) {
              if (data.overwritten) {
                $selector.append('<div style="padding: 1em;">The trace entries have expired, see'
                    + ' <a href="admin/storage#trace-capped-database-size">'
                    + 'Configuration &gt; Storage &gt; Trace detail data</a></div>');
              } else if (data.expired) {
                $selector.append('<div style="padding: 1em;">This trace has expired</div>');
              } else {
                // first time opening
                initTraceEntryLineLength();
                mergeInSharedQueryTexts(data.entries, data.sharedQueryTexts);
                var last = data.entries[data.entries.length - 1];
                // updating traceDurationNanos is needed for live traces
                traceDurationNanos = Math.max(traceDurationNanos, last.startOffsetNanos + last.durationNanos);
                flattenedTraceEntries = flattenTraceEntries(data.entries);
                // un-hide before building in case there are lots of trace entries, at least can see first few quickly
                $selector.removeClass('hide');
                renderNext(flattenedTraceEntries, 0);
              }
            })
            .fail(function (jqXHR) {
              if (jqXHR.status === 401) {
                goToLogin(jqXHR.responseJSON.timedOut);
              } else {
                $selector.append(
                    '<div class="gt-red" style="padding: 1em;">An error occurred retrieving the trace entries</div>');
              }
            })
            .always(function () {
              spinner.stop();
              $selector.data('gtLoading', false);
              $selector.data('gtLoaded', true);
            });
      }
    } else if ($selector.hasClass('hide')) {
      $selector.removeClass('hide');
    } else {
      $selector.addClass('hide');
    }
  });

  $(document).on('click', '.gt-trace-entry-toggle', function () {
    function toggleChildren(parentTraceEntry, collapse) {
      var i;
      for (i = 0; i < parentTraceEntry.childEntries.length; i++) {
        var entry = parentTraceEntry.childEntries[i];
        if (collapse) {
          $('#gtTraceEntry' + entry.index).hide();
        } else {
          $('#gtTraceEntry' + entry.index).show();
        }
        if (entry.childEntries && !entry.collapsed) {
          toggleChildren(entry, collapse);
        }
      }
    }

    var traceEntryIndex = $(this).data('gt-index');
    var traceEntry = flattenedTraceEntries[traceEntryIndex];
    traceEntry.collapsed = !traceEntry.collapsed;
    toggleChildren(traceEntry, traceEntry.collapsed);
    var $i = $(this).find('i');
    if (traceEntry.collapsed) {
      $i.removeClass('fa-minus-square-o');
      $i.addClass('fa-plus-square-o');
    } else {
      $i.removeClass('fa-plus-square-o');
      $i.addClass('fa-minus-square-o');
    }
  });

  $(document).on('click', '.gt-main-thread-profile-toggle', function () {
    var $traceParent = $(this).parents('.gt-trace-parent');
    var $button = $(this);
    var profile = $traceParent.data('gtMainThreadProfile');
    var url;
    if (!profile) {
      var agentId = $traceParent.data('gtAgentId');
      var traceId = $traceParent.data('gtTraceId');
      var checkLiveTraces = $traceParent.data('gtCheckLiveTraces');
      url = 'backend/trace/main-thread-profile?agent-id=' + encodeURIComponent(agentId) + '&trace-id=' + traceId;
      if (checkLiveTraces) {
        url += '&check-live-traces=true';
      }
    }
    profileToggle($button, '#mainThreadProfileOuter', profile, url);
  });

  $(document).on('click', '.gt-aux-thread-profile-toggle', function () {
    var $traceParent = $(this).parents('.gt-trace-parent');
    var $button = $(this);
    var profile = $traceParent.data('gtAuxThreadProfile');
    var url;
    if (!profile) {
      var agentId = $traceParent.data('gtAgentId');
      var traceId = $traceParent.data('gtTraceId');
      var checkLiveTraces = $traceParent.data('gtCheckLiveTraces');
      url = 'backend/trace/aux-thread-profile?agent-id=' + encodeURIComponent(agentId) + '&trace-id=' + traceId;
      if (checkLiveTraces) {
        url += '&check-live-traces=true';
      }
    }
    profileToggle($button, '#auxThreadProfileOuter', profile, url);
  });

  var MULTIPLE_ROOT_NODES = '<multiple root nodes>';

  function profileToggle($button, selector, profile, url) {
    var $selector = $(selector);
    if ($selector.data('gtLoading')) {
      // handles rapid clicking when loading from url
      return;
    }
    if (!$selector.data('gtLoaded')) {
      if (profile) {
        // this is an export file or transaction profile tab
        buildMergedStackTree(profile, $selector);
        $selector.removeClass('hide');
        $selector.data('gtLoaded', true);
      } else {
        $selector.data('gtLoading', true);
        var spinner = Glowroot.showSpinner($button.parent().find('.gt-trace-detail-spinner'));
        $.get(url)
            .done(function (data) {
              if (data.overwritten) {
                $selector.find('.gt-profile').html('<div style="padding: 1em;">The profile has expired, see'
                    + ' <a href="admin/storage#trace-capped-database-size">'
                    + 'Configuration &gt; Storage &gt; Trace detail data</a></div>');
              } else if (data.expired) {
                $selector.find('.gt-profile').html.append('<div style="padding: 1em;">This trace has expired</div>');
              } else {
                buildMergedStackTree(data, $selector);
              }
              $selector.removeClass('hide');
            })
            .fail(function (jqXHR) {
              if (jqXHR.status === 401) {
                goToLogin(jqXHR.responseJSON.timedOut);
              } else {
                $selector.find('.gt-profile').html(
                    '<div class="gt-red" style="padding: 1em 0;">An error occurred retrieving the profile</div>');
                $selector.removeClass('hide');
              }
            })
            .always(function () {
              spinner.stop();
              $selector.data('gtLoading', false);
              $selector.data('gtLoaded', true);
            });
      }
    } else if ($selector.hasClass('hide')) {
      $selector.removeClass('hide');
    } else {
      $selector.addClass('hide');
    }
  }

  function initTraceEntryLineLength() {
    // -170 for the left margin of the trace entry lines
    traceEntryLineLength = ($('#entries').width() - 220) / monospaceCharWidth;
    // min value of 60, otherwise not enough context provided by the elipsed line
    traceEntryLineLength = Math.max(traceEntryLineLength, 60);
    // max value of 240, since long queries are only initially retrieved with first 120 and last 120 characters
    traceEntryLineLength = Math.min(traceEntryLineLength, 240);
  }

  function mergeInSharedQueryTexts(entries, sharedQueryTexts) {
    $.each(entries, function (index, entry) {
      if (entry.queryMessage) {
        entry.queryMessage.sharedQueryText = sharedQueryTexts[entry.queryMessage.sharedQueryTextIndex];
        if (!entry.queryMessage.sharedQueryText.fullTextSha1) {
          entry.message = entry.queryMessage.prefix + entry.queryMessage.sharedQueryText.fullText
              + entry.queryMessage.suffix;
        }
      }
      if (entry.childEntries) {
        mergeInSharedQueryTexts(entry.childEntries, sharedQueryTexts);
      }
    });
  }

  function flattenTraceEntries(entries) {
    var flattenedTraceEntries = [];
    var traceEntryIndex = 0;

    function flattenAndRecurse(entries, depth) {
      var i;
      var entry;
      for (i = 0; i < entries.length; i++) {
        entry = entries[i];
        entry.collapsed = false;
        entry.depth = depth;
        flattenedTraceEntries.push(entry);
        entry.index = traceEntryIndex++;
        if (entry.childEntries) {
          flattenAndRecurse(entry.childEntries, depth + 1);
        }
      }
    }

    flattenAndRecurse(entries, 0);
    return flattenedTraceEntries;
  }

  function renderNext(entries, start) {
    // large numbers of trace entries (e.g. 20,000) render much faster when grouped into sub-divs
    var batchSize;
    var i;
    if (start === 0) {
      // first batch size is smaller to make the records show up on screen right away
      batchSize = 100;
    } else {
      // rest of batches are optimized for total throughput
      batchSize = 500;
    }
    var html = '';
    for (i = start; i < Math.min(start + batchSize, entries.length); i++) {
      html += JST['trace-entry'](entries[i]);
    }
    $('#entries').append(html);
    if (start + 100 < entries.length) {
      setTimeout(function () {
        renderNext(entries, start + batchSize);
      }, 10);
    }
  }

  function formatSql(unexpanded, expanded, queryText, suffix) {
    var comment;
    var sql;
    if (queryText.lastIndexOf('/*', 0) === 0) {
      var endOfCommentIndex = queryText.indexOf('*/') + 2;
      comment = queryText.substring(0, endOfCommentIndex) + '\n';
      sql = queryText.substring(endOfCommentIndex).trim();
    } else {
      comment = '';
      sql = queryText;
    }
    var formatted = SqlPrettyPrinter.format(sql);
    if (typeof formatted === 'object') {
      // intentional console logging
      // need conditional since console does not exist in IE9 unless dev tools is open
      if (window.console) {
        console.log(formatted.message);
        console.log(sql);
      }
    } else {
      if (comment.length) {
        var spaces = '';
        for (var i = 0; i < formatted.length; i++) {
          if (formatted[i] === ' ') {
            spaces += ' ';
          } else {
            break;
          }
        }
        formatted = spaces + comment + formatted;
      }
      var parameters = suffix.replace(/ => [0-9]+ rows?$/, '');
      var rows = suffix.substring(parameters.length + 1);
      var html = 'jdbc execution:\n\n';
      // simulating pre using span, because with pre tag, when selecting text and copy-pasting from firefox
      // there are extra newlines after the pre tag
      html += '<span class="gt-indent2 gt-inline-block" style="white-space: pre-wrap;">'
          + formatted + '</span>';
      if (parameters) {
        html += '\n\n<span class="gt-indent2">parameters:</span>\n\n'
            + '<span class="gt-indent2">  ' + parameters + '</span>';
      }
      if (rows) {
        html += '\n\n<span class="gt-indent2">rows:</span>\n\n'
            + '<span class="gt-indent2">  ' + rows + '</span>';
      }
      expanded.css('padding-bottom', '10px');
      var $clip = expanded.find('.gt-clip');
      $clip.css('top', '10px');
      $clip.css('right', '10px');
      var $message = expanded.find('.gt-pre-wrap');
      $message.html(html);
      $message.css('min-width', 0.6 * unexpanded.parent().width());
    }
  }

  function basicToggle(parent) {
    var expanded = parent.find('.gt-expanded-content');
    var unexpanded = parent.find('.gt-unexpanded-content');

    function doAfter() {
      unexpanded.toggleClass('hide');
      expanded.toggleClass('hide');
      if (expanded.width() >= expanded.parent().width()) {
        expanded.css('display', 'block');
      }
      // re-focus on visible element, otherwise up/down/pgup/pgdown/ESC don't work
      if (unexpanded.hasClass('hide')) {
        expanded.attr('tabindex', -1);
        expanded.css('outline', 'none');
        expanded.focus();
      } else {
        unexpanded.attr('tabindex', -1);
        unexpanded.css('outline', 'none');
        unexpanded.focus();
      }
    }

    if (expanded.hasClass('hide') && !expanded.data('gtExpandedPreviously')) {
      var $clipboardIcon = expanded.find('.fa-clipboard');
      var clipTextNode = expanded.find('.gt-clip-text');
      var clipboardContainer;
      if ($('#headerJson').length === 0) {
        // .modal-dialog is used instead of .modal for the clipboard (tooltip) container
        // so that the tooltip scrolls (briefly) with the page (until it hides when hover is lost)
        clipboardContainer = '#traceModal .modal-dialog';
      } else {
        clipboardContainer = 'body';
      }
      gtClipboard($clipboardIcon, clipboardContainer, function () {
        return clipTextNode[0];
      }, function () {
        var text = clipTextNode.text();
        // TODO deal with this hacky special case for SQL formatting
        if (text.lastIndexOf('jdbc execution:\n\n', 0) === 0) {
          text = text.substring('jdbc execution:\n\n'.length);
        }
        return text;
      });

      var expandedTraceEntryNode = expanded.find('.gt-expanded-trace-entry');
      var queryMessage;
      if (expandedTraceEntryNode.length) {
        var traceEntryIndex = expandedTraceEntryNode.data('gt-trace-entry-index');
        queryMessage = flattenedTraceEntries[traceEntryIndex].queryMessage;
      }
      if (queryMessage && queryMessage.sharedQueryText.fullTextSha1 && !queryMessage.sharedQueryText.fullText) {
        var $traceParent = parent.parents('.gt-trace-parent');
        var agentRollupId = $traceParent.data('gtAgentRollupId');
        var alreadyDoneAfter;
        var spinner = Glowroot.showSpinner(expanded.find('.gt-trace-detail-spinner'), function () {
          doAfter();
          alreadyDoneAfter = true;
        });
        $.get('backend/transaction/full-query-text?agent-rollup-id=' + encodeURIComponent(agentRollupId)
            + '&full-text-sha1=' + queryMessage.sharedQueryText.fullTextSha1)
            .done(function (data) {
              if (data.expired) {
                expandedTraceEntryNode.text('[the full query text has expired]');
              } else {
                expandedTraceEntryNode.text(queryMessage.prefix + data.fullText + queryMessage.suffix);
                if (queryMessage.prefix === 'jdbc execution: ') {
                  formatSql(unexpanded, expanded, data.fullText, queryMessage.suffix.trim());
                }
                // so other trace entries with same shared query text don't need to go to server
                queryMessage.sharedQueryText.fullText = data.fullText;
              }
            })
            .fail(function (jqXHR) {
              if (jqXHR.status === 401) {
                goToLogin(jqXHR.responseJSON.timedOut);
              } else {
                expandedTraceEntryNode.html('<div class="gt-red">An error occurred retrieving the full query text</div>');
              }
            })
            .always(function () {
              spinner.stop();
              if (!alreadyDoneAfter) {
                doAfter();
              }
            });
      } else if (queryMessage && queryMessage.sharedQueryText.fullTextSha1) {
        // already fetched full text for this sha1
        expandedTraceEntryNode.text(queryMessage.prefix + queryMessage.sharedQueryText.fullText + queryMessage.suffix);
        if (queryMessage.prefix === 'jdbc execution: ') {
          formatSql(unexpanded, expanded, queryMessage.sharedQueryText.fullText, queryMessage.suffix.trim());
        }
        doAfter();
      } else {
        // the call to formatSql() is needed here for data collected prior to 0.9.3
        var text = expandedTraceEntryNode.text().trim();
        if (text.lastIndexOf('jdbc execution: ', 0) === 0) {
          var afterPrefixStripped = text.substring('jdbc execution: '.length);
          var afterRowsStripped = afterPrefixStripped.replace(/ => [0-9]+ rows?$/, '');
          var queryText = afterRowsStripped.replace(/ \[.*?]$/, '');
          var suffix = afterPrefixStripped.substring(queryText.length + 1);
          formatSql(unexpanded, expanded, queryText, suffix);
        }
        doAfter();
      }
    } else {
      doAfter();
    }
    expanded.data('gtExpandedPreviously', true);
  }

  function smartToggle(parent, e, keyboard) {
    if (keyboard) {
      basicToggle(parent);
      return;
    }
    if (Math.abs(e.pageX - mousedownPageX) > 5 || Math.abs(e.pageY - mousedownPageY) > 5) {
      // not a simple single click, probably highlighting text
      return;
    }
    basicToggle(parent);
  }

  function escapeHtml(text) {
    return Handlebars.Utils.escapeExpression(text);
  }

  // these ids needs to be unique across both main thread profile and aux thread profile
  var nextUniqueId = 0;

  function buildMergedStackTree(profile, selector) {

    var rootNode = {
      stackTraceElement: '',
      sampleCount: 0,
      ellipsedSampleCount: 0,
      childNodes: []
    };

    $.each(profile.rootNodes, function (index, node) {
      rootNode.sampleCount += node.sampleCount;
      rootNode.ellipsedSampleCount += node.ellipsedSampleCount;
      if (!node.ellipsedSampleCount || node.sampleCount > node.ellipsedSampleCount) {
        rootNode.childNodes.push(node);
      }
    });

    // root node is always synthetic root node
    if (rootNode.childNodes && rootNode.childNodes.length === 1 && !rootNode.ellipsedSampleCount) {
      // strip off synthetic root node
      rootNode = rootNode.childNodes[0];
    } else {
      rootNode.stackTraceElement = MULTIPLE_ROOT_NODES;
    }

    function initNodeIds() {

      function initNodeId(node) {
        node.id = nextUniqueId++;
        if (node.childNodes) {
          var i;
          for (i = 0; i < node.childNodes.length; i++) {
            initNodeId(node.childNodes[i]);
          }
        }
      }

      initNodeId(rootNode);
    }

    function filter(includes, underMatchingNode) {
      var includeUppers = [];
      var i;
      for (i = 0; i < includes.length; i++) {
        includeUppers.push(includes[i].toUpperCase());
      }

      function highlightAndEscapeHtml(text) {
        if (text === MULTIPLE_ROOT_NODES) {
          // don't highlight any part of "<multiple root nodes>"
          return escapeHtml(text);
        }
        if (includeUppers.length === 0) {
          return escapeHtml(text);
        }
        var highlightRanges = [];
        var index;
        for (i = 0; i < includeUppers.length; i++) {
          index = text.toUpperCase().indexOf(includeUppers[i]);
          if (index !== -1) {
            highlightRanges.push([index, index + includeUppers[i].length]);
          }
        }
        if (highlightRanges.length === 0) {
          return escapeHtml(text);
        }
        highlightRanges.sort(function (range1, range2) {
          return range1[0] - range2[0];
        });
        var firstHighlightRange = highlightRanges[0];
        return escapeHtml(text.substring(0, firstHighlightRange[0])) + '<strong>'
            + escapeHtml(text.substring(firstHighlightRange[0], firstHighlightRange[1]))
            + '</strong>' + highlightAndEscapeHtml(text.substring(firstHighlightRange[1]));
      }

      function filterNode(node, underMatchingNode) {
        var nodes = [node];
        while (node.childNodes && node.childNodes.length === 1 && !node.leafThreadState
        // the below condition is to make the 100% block at the top really mean exactly 100% (no ellipsed nodes)
        && (node.sampleCount === node.childNodes[0].sampleCount || node.sampleCount < profile.unfilteredSampleCount)
        && (!SHOW_ELLIPSED_NODE_MARKERS || !node.ellipsedSampleCount)) {
          node = node.childNodes[0];
          nodes.push(node);
        }
        function matchIncludes(text) {
          var i;
          var textUpper = text.toUpperCase();
          for (i = 0; i < includeUppers.length; i++) {
            if (textUpper.indexOf(includeUppers[i]) !== -1) {
              return true;
            }
          }
          return false;
        }

        var i;
        var currMatchingNode = false;
        var stackTraceElement;
        for (i = 0; i < nodes.length; i++) {
          stackTraceElement = nodes[i].stackTraceElement;
          // don't match any part of "<multiple root nodes>"
          if (stackTraceElement !== MULTIPLE_ROOT_NODES && matchIncludes(stackTraceElement)) {
            currMatchingNode = true;
            break;
          }
        }
        // only the last node in nodes can be a leaf
        var lastNode = nodes[nodes.length - 1];
        if (lastNode.leafThreadState && matchIncludes(lastNode.leafThreadState)) {
          currMatchingNode = true;
        }
        if (currMatchingNode || underMatchingNode) {
          for (i = 0; i < nodes.length; i++) {
            nodes[i].filteredSampleCount = nodes[i].sampleCount;
            nodes[i].filteredEllipsedSampleCount = nodes[i].ellipsedSampleCount;
          }
          if (lastNode.childNodes) {
            for (i = 0; i < lastNode.childNodes.length; i++) {
              filterNode(lastNode.childNodes[i], true);
            }
          }
        } else {
          for (i = 0; i < nodes.length; i++) {
            nodes[i].filteredSampleCount = 0;
            nodes[i].filteredEllipsedSampleCount = false;
          }
          if (lastNode.childNodes) {
            for (i = 0; i < lastNode.childNodes.length; i++) {
              lastNode.filteredSampleCount += filterNode(lastNode.childNodes[i]);
            }
          }
          if (lastNode.ellipsedSampleCount) {
            lastNode.filteredSampleCount += lastNode.ellipsedSampleCount;
          }
        }
        if (lastNode.ellipsedSampleCount && lastNode.filteredEllipsedSampleCount) {
          $('#gtProfileNodeEllipsed' + lastNode.id).show();
        } else if (lastNode.ellipsedSampleCount) {
          $('#gtProfileNodeEllipsed' + lastNode.id).hide();
        }
        if (lastNode.filteredSampleCount === 0) {
          // TODO hide all nodes
          $('#gtProfileNode' + lastNode.id).hide();
          return 0;
        }
        for (i = 0; i < nodes.length; i++) {
          var highlighted = highlightAndEscapeHtml(nodes[i].stackTraceElement);
          $('#gtProfileNodeText' + nodes[i].id).html(highlighted);
          if (nodes.length > 1 && i === nodes.length - 1) {
            $('#gtProfileNodeTextUnexpanded' + lastNode.id).html(highlighted);
          }
        }
        var samplePercentage = (lastNode.filteredSampleCount / profile.unfilteredSampleCount) * 100;
        $('#gtProfileNodePercent' + lastNode.id).text(formatPercent(samplePercentage) + '%');
        $('#gtProfileNode' + lastNode.id).show();
        if (nodes.length > 1) {
          var nodeTextParent = $('#gtProfileNodeText' + lastNode.id).parent();
          var nodeTextParentParent = $('#gtProfileNodeText' + lastNode.id).parent().parent();
          var unexpanded = nodeTextParentParent.find('.gt-unexpanded-content');
          if (currMatchingNode && includes.length) {
            unexpanded.addClass('hide');
            nodeTextParent.removeClass('hide');
          } else {
            unexpanded.removeClass('hide');
            nodeTextParent.addClass('hide');
          }
        }
        if (lastNode.leafThreadState) {
          $('#gtProfileNodeThreadState' + lastNode.id).html(highlightAndEscapeHtml(lastNode.leafThreadState));
        }
        return lastNode.filteredSampleCount;
      }

      // 2nd arg, starts automatically "underMatchingNode" when no includes
      filterNode(rootNode, underMatchingNode || !includes.length);
    }

    function generateHtml(timer) {

      if (!rootNode.childNodes) {
        // special case of empty result
        return '';
      }

      function curr(node, level) {
        var nodeSampleCount;
        if (timer) {
          nodeSampleCount = node.timerCounts[timer] || 0;
        } else {
          nodeSampleCount = node.sampleCount;
        }
        if (nodeSampleCount === 0) {
          return '';
        }
        var nodes = [node];
        while (node.childNodes && node.childNodes.length === 1 && !node.leafThreadState
        // the below condition is to make the 100% block at the top really mean exactly 100% (no ellipsed nodes)
        && (node.sampleCount === node.childNodes[0].sampleCount || node.sampleCount < profile.unfilteredSampleCount)
        && (!SHOW_ELLIPSED_NODE_MARKERS || !node.ellipsedSampleCount)) {
          node = node.childNodes[0];
          nodes.push(node);
        }
        if (!timer) {
          // the displayed percentage for this chain is based on the last node
          // (this is noticeable with large truncation percentages where the first/last node in a chain can have
          // noticeably different sample counts)
          nodeSampleCount = nodes[nodes.length - 1].sampleCount;
        }
        var ret = '<span id="gtProfileNode' + node.id + '">';
        ret += '<span class="gt-inline-block gt-width6" style="margin-left: ' + (2 * level * indent1) + 'px;"';
        ret += ' id="gtProfileNodePercent' + node.id + '">';
        var samplePercentage = (nodeSampleCount / profile.unfilteredSampleCount) * 100;
        ret += formatPercent(samplePercentage);
        // the space after the % is actually important when highlighting a block of stack trace
        // elements in the ui and copy pasting into the eclipse java stack trace console, because the
        // space gives separation between the percentage and the stack trace element and so eclipse is
        // still able to understand the stack trace
        ret += '% </span>';
        if (nodes.length === 1) {
          ret += '<span style="visibility: hidden;"><strong>...</strong> </span>';
          ret += '<span class="gt-inline-block gt-pad1profile">';
          ret += '<span class="gt-monospace" id="gtProfileNodeText' + node.id + '">';
          ret += escapeHtml(node.stackTraceElement);
          ret += '</span></span><br>';
        } else {
          ret += '<span>';
          ret += '<span class="gt-inline-block gt-unexpanded-content" style="vertical-align: top;">';
          ret += '<span class="gt-link-color"><strong>...</strong> </span>';
          ret += '<span class="gt-monospace" id="gtProfileNodeTextUnexpanded' + nodes[nodes.length - 1].id + '">';
          ret += escapeHtml(nodes[nodes.length - 1].stackTraceElement) + '</span><br></span>';
          ret += '<span style="visibility: hidden;"><strong>...</strong> </span>';
          ret += '<span class="gt-inline-block gt-expanded-content hide" style="vertical-align: top;">';
          $.each(nodes, function (index, node) {
            ret += '<span class="gt-monospace" id="gtProfileNodeText' + node.id + '">'
                + escapeHtml(node.stackTraceElement) + '</span><br>';
          });
          ret += '</span></span><br>';
        }
        if (node.leafThreadState) {
          ret += '<span class="gt-inline-block gt-width4" style="margin-left: ' + (level + 5) * indent2
              + 'px;"></span>';
          ret += '<span style="visibility: hidden;"><strong>...</strong> </span>';
          ret += '<span class="gt-inline-block gt-pad1profile gt-monospace" id="gtProfileNodeThreadState';
          ret += node.id + '">';
          ret += escapeHtml(node.leafThreadState);
          ret += '</span><br>';
        }
        if (node.childNodes) {
          var childNodes = node.childNodes;
          // order child nodes by sampleCount (descending)
          childNodes.sort(function (a, b) {
            if (timer) {
              return (b.timerCounts[timer] || 0) - (a.timerCounts[timer] || 0);
            } else {
              return b.sampleCount - a.sampleCount;
            }
          });
          for (var i = 0; i < childNodes.length; i++) {
            ret += curr(childNodes[i], level + 1);
          }
        }
        if (SHOW_ELLIPSED_NODE_MARKERS && node.ellipsedSampleCount) {
          var ellipsedSamplePercentage = (node.ellipsedSampleCount / rootNode.sampleCount) * 100;
          ret += '<div id="gtProfileNodeEllipsed' + node.id + '">';
          ret += '<span class="gt-inline-block gt-width4" style="margin-left: ' + (level + 5) * indent2
              + 'px;"></span>';
          ret += '<span style="visibility: hidden;"><strong>...</strong> </span>';
          ret += '<span class="gt-inline-block gt-pad1profile">(one or more branches ~ ';
          ret += formatPercent(ellipsedSamplePercentage);
          ret += '%)</span></div>';
        }
        if (!SHOW_ELLIPSED_NODE_MARKERS && !node.childNodes && !node.leafThreadState) {
          ret += '<div id="gtProfileNodeEllipsed' + node.id + '">';
          ret += '<span class="gt-inline-block gt-width4" style="margin-left: ' + (level + 5) * indent2
              + 'px;"></span>';
          ret += '<span style="visibility: hidden;"><strong>...</strong> </span>';
          ret += '<span class="gt-inline-block gt-pad1profile" style="font-style: italic;">truncated branches';
          ret += '</span></div>';
        }
        ret += '</span>';
        return ret;
      }

      return curr(rootNode, 0);
    }

    var $selector = $(selector);
    // first time only, process merged stack tree and populate dropdown
    // build initial merged stack tree
    initNodeIds();
    var html = generateHtml();
    $selector.find('.gt-profile').html(html);

    // set up text filter
    var $profileTextFilter = $selector.find('.gt-profile-text-filter');
    var $profileTextFilterRefresh = $selector.find('.gt-profile-text-filter-refresh');
    var timer;
    $profileTextFilter.off('input.gtProfileFilter');
    if (!$profileTextFilterRefresh.length) {
      $profileTextFilter.on('input.gtProfileFilter', function () {
        // primarily timer is used to deal with lagging when holding down backspace to clear out filter
        clearTimeout(timer);
        timer = setTimeout(function () {
          // update merged stack tree based on filter
          var filterText = $profileTextFilter.val();
          if (filterText) {
            filter([filterText]);
          } else {
            filter([]);
          }
        }, 50);
      });
    }
    // apply initial filter text if any (e.g. user changes Last 30 min to Last 60 min) triggering profile refresh
    // but filter text stays the same (which seems good)
    var filterText = $profileTextFilter.val();
    var parseResult = gtParseIncludesExcludes(filterText);
    if (!parseResult.error && (parseResult.includes.length || parseResult.excludes.length)) {
      filter(parseResult.includes, true);
      $selector.data('gtTextFilterOverride', true);
    }
  }

  function formatBytes(bytes) {
    if (isNaN(parseFloat(bytes)) || !isFinite(bytes)) {
      return '-';
    }
    if (bytes === 0) {
      // no unit needed
      return '0';
    }
    var units = ['bytes', 'KB', 'MB', 'GB', 'TB', 'PB'];
    var number = Math.floor(Math.log(bytes) / Math.log(1024));
    var num = bytes / Math.pow(1024, number);
    if (number === 0) {
      return num.toFixed(0) + ' bytes';
    } else {
      return num.toFixed(1) + ' ' + units[number];
    }
  }

  function formatMillis(millis) {
    if (Math.abs(millis) < 0.0000005) {
      // less than 0.5 nanoseconds
      return '0.0';
    }
    if (Math.abs(millis) < 0.000001) {
      // between 0.5 and 1 nanosecond (round up)
      return '0.000001';
    }
    if (Math.abs(millis) < 0.00001) {
      // less than 10 nanoseconds
      return millis.toPrecision(1);
    }
    if (Math.abs(millis) < 1) {
      return millis.toPrecision(2);
    }
    return formatWithExactlyOneFractionalDigit(millis);
  }

  function formatCount(count) {
    if (count === undefined) {
      return '';
    }
    if (Math.abs(count) < 0.1) {
      return count.toPrecision(1);
    }
    return formatWithExactlyOneFractionalDigit(count);
  }

  function formatPercent(percent) {
    if (percent === 100) {
      return '100';
    }
    if (percent > 99.9) {
      // don't round up to 100 since that looks incorrect in UI
      return '99.9';
    }
    if (percent === 0) {
      return '0';
    }
    if (percent < 0.1) {
      // don't round down to 0 since that looks incorrect in UI
      return '0.1';
    }
    return formatCount(percent);
  }

  function formatWithExactlyOneFractionalDigit(value) {
    return (Math.round(value * 10) / 10).toLocaleString(undefined, {minimumFractionDigits: 1});
  }

  function registerShowMoreHandler(breakdown) {
    $('#' + breakdown.prefix + 'ttShowMore').click(function () {
      breakdown.limit *= 2;
      updateLimit(breakdown);
    });
    $('#' + breakdown.prefix + 'ftShowMore').click(function () {
      breakdown.limit *= 2;
      updateLimit(breakdown);
    });
  }

  function registerShowLessHandler(breakdown) {
    $('#' + breakdown.prefix + 'ttShowLess').click(function () {
      breakdown.limit /= 2;
      updateLimit(breakdown);
    });
    $('#' + breakdown.prefix + 'ftShowLess').click(function () {
      breakdown.limit /= 2;
      while (breakdown.limit >= breakdown.flattenedTimers.length) {
        // show less should always leave displayed list less than full list
        breakdown.limit /= 2;
      }
      updateLimit(breakdown);
    });
  }

  function registerShowAllHandler(breakdown) {
    $('#' + breakdown.prefix + 'ttShowAll').click(function () {
      while (breakdown.limit < breakdown.timers.length) {
        breakdown.limit *= 2;
      }
      updateLimit(breakdown);
    });
    $('#' + breakdown.prefix + 'ftShowAll').click(function () {
      while (breakdown.limit < breakdown.timers.length) {
        breakdown.limit *= 2;
      }
      updateLimit(breakdown);
    });
  }

  return {
    renderTrace: function (traceHeader, agentId, traceId, checkLiveTraces, $selector) {

      traceHeader.mainBreakdown = {
        treeTimers: traceHeader.mainThreadRootTimer,
        prefix: 'm',
        limit: 10
      };
      traceHeader.auxBreakdown = {
        treeTimers: traceHeader.auxThreadRootTimers,
        prefix: 'a',
        limit: 10
      };
      // initializing timers needs to occur before rendering
      initTimers(traceHeader.mainBreakdown);
      initTimers(traceHeader.auxBreakdown);
      initFlattenedTimers(traceHeader.mainBreakdown);
      initFlattenedTimers(traceHeader.auxBreakdown);

      initTotalNanosList(traceHeader.mainBreakdown);
      initTotalNanosList(traceHeader.auxBreakdown);

      initLimit(traceHeader.mainBreakdown);
      initLimit(traceHeader.auxBreakdown);

      var html = JST.trace(traceHeader);
      $selector.html(html);
      $selector.addClass('gt-trace-parent');
      if (agentId !== undefined) {
        $selector.data('gtAgentId', agentId);
        $selector.data('gtTraceId', traceId);
        $selector.data('gtCheckLiveTraces', checkLiveTraces);
      }
      traceDurationNanos = traceHeader.durationNanos;

      registerShowMoreHandler(traceHeader.mainBreakdown);
      registerShowMoreHandler(traceHeader.auxBreakdown);

      registerShowLessHandler(traceHeader.mainBreakdown);
      registerShowLessHandler(traceHeader.auxBreakdown);

      registerShowAllHandler(traceHeader.mainBreakdown);
      registerShowAllHandler(traceHeader.auxBreakdown);
    },
    renderTraceFromExport: function (traceHeader, $selector, entries, sharedQueryTexts, mainThreadProfile,
                                     auxThreadProfile) {
      $selector.data('gtEntries', entries);
      $selector.data('gtSharedQueryTexts', sharedQueryTexts);
      $selector.data('gtMainThreadProfile', mainThreadProfile);
      $selector.data('gtAuxThreadProfile', auxThreadProfile);
      this.renderTrace(traceHeader, undefined, undefined, undefined, false, $selector);
    },
    formatBytes: formatBytes,
    formatMillis: formatMillis,
    formatCount: formatCount,
    profileToggle: profileToggle
  };
})();
