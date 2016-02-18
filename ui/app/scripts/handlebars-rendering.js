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

// IMPORTANT: DO NOT USE ANGULAR IN THIS FILE
// that would require adding angular to trace-export.js
// and that would significantly increase the size of the exported trace files

// Glowroot dependency is used for spinner, but is not used in export file
/* global $, Handlebars, JST, moment, Glowroot, SqlPrettyPrinter, gtClipboard, gtParseIncludesExcludes, console */

// IMPORTANT: DO NOT USE ANGULAR IN THIS FILE
// that would require adding angular to trace-export.js
// and that would significantly increase the size of the exported trace files

var HandlebarsRendering;

// showing ellipsed node markers makes the rendered profile tree much much larger since it cannot contract otherwise
// identical branches, and this makes navigation more difficult, as well as makes rendering much slower
var SHOW_ELLIPSED_NODE_MARKERS = false;

HandlebarsRendering = (function () {
  // indent1 must be sync'd with $indent1 variable in common-trace.less
  var indent1 = 1; // em

  var traceEntryLineLength;

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

  Handlebars.registerHelper('eachTimerOrdered', function (rootTimer, options) {
    var buffer = '';

    function traverse(timer, depth) {
      timer.depth = depth;
      buffer += options.fn(timer);
      if (timer.childTimers) {
        timer.childTimers.sort(function (a, b) {
          return b.totalNanos - a.totalNanos;
        });
        $.each(timer.childTimers, function (index, nestedTimer) {
          traverse(nestedTimer, depth + 1);
        });
      }
    }

    // add the root node(s)
    if ($.isArray(rootTimer)) {
      $.each(rootTimer, function (index, item) {
        traverse(item, 0);
      });
    } else {
      traverse(rootTimer, 0);
    }
    return buffer;
  });

  Handlebars.registerHelper('eachTimerFlattenedOrdered', function (rootTimer, options) {
    var flattenedTimerMap = {};
    var flattenedTimers = [];

    function traverse(timer, parentTimerNames) {
      var flattenedTimer = flattenedTimerMap[timer.name];
      if (!flattenedTimer) {
        flattenedTimer = {
          name: timer.name,
          totalNanos: timer.totalNanos,
          count: timer.count,
          active: timer.active
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
          traverse(nestedTimer, parentTimerNames.concat(timer));
        });
      }
    }

    // add the root node(s)
    if ($.isArray(rootTimer)) {
      $.each(rootTimer, function (index, item) {
        traverse(item, []);
      });
    } else {
      traverse(rootTimer, []);
    }

    flattenedTimers.sort(function (a, b) {
      return b.totalNanos - a.totalNanos;
    });
    var buffer = '';
    $.each(flattenedTimers, function (index, timer) {
      buffer += options.fn(timer);
    });
    return buffer;
  });

  Handlebars.registerHelper('ifNonEmptyObject', function (value, options) {
    if (value && !$.isEmptyObject(value)) {
      return options.fn(this);
    }
    return options.inverse(this);
  });

  // allows empty string ""
  Handlebars.registerHelper('ifDisplayMessage', function (traceEntry, options) {
    if (traceEntry.message || !traceEntry.error) {
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
        ret += maybeBoldPropName(propName) + '<br><div class="gt-indent1">' + messageDetailHtml(propVal) + '</div>';
      } else {
        // outer div with clearfix is needed when propVal is empty
        ret += '<div class="clearfix"><div style="float: left;">' + maybeBoldPropName(propName) + '&nbsp;</div>'
            + '<div class="gt-trace-attr-value">' + propVal + '</div></div>';
      }
    });
    return ret;
  }

  Handlebars.registerHelper('ifLongMessage', function (message, options) {
    if (message.length > traceEntryLineLength) {
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
      return 'gt-indent2';
    }
    return '';
  });

  Handlebars.registerHelper('exceptionIndentClass', function (message) {
    if (message) {
      return 'gt-indent1';
    }
    return 'gt-indent2';
  });

  Handlebars.registerHelper('addIndent2', function (width) {
    return width + 2 * indent1;
  });

  Handlebars.registerHelper('traceEntryIndent', function (traceEntry) {
    return indent1 * (1 + traceEntry.depth);
  });

  Handlebars.registerHelper('timerIndent', function (timer) {
    return indent1 * timer.depth;
  });

  Handlebars.registerHelper('firstPart', function (message) {
    // -3 to leave room in the middle for ' ... '
    return message.slice(0, traceEntryLineLength / 2 - 3);
  });

  Handlebars.registerHelper('lastPart', function (message) {
    // -3 to leave room in the middle for ' ... '
    return message.slice(-(traceEntryLineLength / 2 - 3));
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
    var html = '<div style="white-space: pre;">';
    var i;
    for (i = 0; i < stackTraceElements.length; i++) {
      html += escapeHtml(stackTraceElements[i]) + '\n';
    }
    html += '</div>';
    return html;
  });

  var mousedownPageX, mousedownPageY;

  $(document).mousedown(function (e) {
    mousedownPageX = e.pageX;
    mousedownPageY = e.pageY;
  });

  $(document).on('click', '.gt-timers-view-toggle', function () {
    var $timers = $(this).parents('.gt-timers');
    $timers.children('table').toggleClass('hide');
    // re-focus on visible element, otherwise up/down/pgup/pgdown/ESC don't work
    var $timersViewToggle = $timers.find('.gt-timers-view-toggle:visible');
    $timersViewToggle.attr('tabindex', -1);
    $timersViewToggle.css('outline', 'none');
    $timersViewToggle.focus();
  });

  $(document).on('click', '.gt-unexpanded-content, .gt-expanded-content', function (e, keyboard) {
    smartToggle($(this).parent(), e, keyboard);
  });

  $(document).on('click', '.gt-sps-toggle', function () {
    var $selector = $('#sps');
    if ($selector.data('gtLoading')) {
      // handles rapid clicking when loading from url
      return;
    }
    if (!$selector.data('gtLoaded')) {
      var $traceParent = $(this).parents('.gt-trace-parent');
      var traceEntries = $traceParent.data('gtTraceEntries');
      if (traceEntries) {
        // this is an export file
        $selector.data('gtLoaded', true);
        // first time opening
        initTraceEntryLineLength();
        traceEntries = flattenTraceEntries(traceEntries);
        // un-hide before building in case there are lots of trace entries, at least can see first few quickly
        $selector.removeClass('hide');
        renderNext(traceEntries, 0);
      } else {
        // this is not an export file
        var agentId = $traceParent.data('gtAgentId');
        var traceId = $traceParent.data('gtTraceId');
        var checkLiveTraces = $traceParent.data('gtCheckLiveTraces');
        $selector.data('gtLoading', true);
        var loaded;
        var spinner;
        var $button = $(this);
        setTimeout(function () {
          if (!loaded) {
            spinner = Glowroot.showSpinner($button.parent().find('.gt-trace-detail-spinner'));
          }
        }, 100);
        var url = 'backend/trace/entries?agent-id=' + agentId + '&trace-id=' + traceId;
        if (checkLiveTraces) {
          url += '&check-live-traces=true';
        }
        $.get(url)
            .done(function (data) {
              if (data.overwritten) {
                $('#sps').append('<div style="padding: 1em;">The trace entries have expired, see' +
                    ' <a href="config/storage#trace-capped-database-size">' +
                    'Configuration &gt; Storage &gt; Trace detail data</a></div>');
              } else if (data.expired) {
                $('#sps').append('<div style="padding: 1em;">This trace has expired</div>');
              } else {
                // first time opening
                initTraceEntryLineLength();
                data = flattenTraceEntries(data);
                // un-hide before building in case there are lots of trace entries, at least can see first few quickly
                $selector.removeClass('hide');
                renderNext(data, 0);
              }
            })
            .fail(function () {
              $('#sps').append(
                  '<div class="gt-red" style="padding: 1em;">An error occurred retrieving the trace entries</div>');
            })
            .always(function () {
              loaded = true;
              if (spinner) {
                spinner.stop();
              }
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

  $(document).on('click', '.gt-main-thread-profile-toggle', function () {
    var $traceParent = $(this).parents('.gt-trace-parent');
    var $button = $(this);
    var profile = $traceParent.data('gtMainThreadProfile');
    var url;
    if (!profile) {
      var agentId = $traceParent.data('gtAgentId');
      var traceId = $traceParent.data('gtTraceId');
      var checkLiveTraces = $traceParent.data('gtCheckLiveTraces');
      url = 'backend/trace/main-thread-profile?agent-id=' + agentId + '&trace-id=' + traceId;
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
      url = 'backend/trace/aux-thread-profile?agent-id=' + agentId + '&trace-id=' + traceId;
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
        var loaded;
        var spinner;
        setTimeout(function () {
          if (!loaded) {
            spinner = Glowroot.showSpinner($button.parent().find('.gt-trace-detail-spinner'));
          }
        }, 100);
        $.get(url)
            .done(function (data) {
              if (data.overwritten) {
                $selector.find('.gt-profile').html('<div style="padding: 1em;">The profile has expired, see' +
                    ' <a href="config/storage#trace-capped-database-size">' +
                    'Configuration &gt; Storage &gt; Trace detail data</a></div>');
              } else if (data.expired) {
                $selector.find('.gt-profile').html.append('<div style="padding: 1em;">This trace has expired</div>');
              } else {
                buildMergedStackTree(data, $selector);
              }
              $selector.removeClass('hide');
            })
            .fail(function () {
              $selector.find('.gt-profile').html(
                  '<div class="gt-red" style="padding: 1em 0;">An error occurred retrieving the profile</div>');
              $selector.removeClass('hide');
            })
            .always(function () {
              loaded = true;
              if (spinner) {
                spinner.stop();
              }
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
    // using an average character (width-wise) 'o'
    $('body').prepend('<span class="gt-offscreen" id="bodyFontCharWidth">o</span>');
    var charWidth = $('#bodyFontCharWidth').width();
    // -170 for the left margin of the trace entry lines
    traceEntryLineLength = ($('#sps').width() - 170) / charWidth;
    // min value of 80, otherwise not enough context provided by the elipsed line
    traceEntryLineLength = Math.max(traceEntryLineLength, 80);
  }

  function flattenTraceEntries(traceEntries) {
    var flattenedTraceEntries = [];

    function flattenAndRecurse(traceEntries, depth) {
      var i;
      var traceEntry;
      for (i = 0; i < traceEntries.length; i++) {
        traceEntry = traceEntries[i];
        traceEntry.depth = depth;
        flattenedTraceEntries.push(traceEntry);
        if (traceEntry.childEntries) {
          flattenAndRecurse(traceEntry.childEntries, depth + 1);
        }
      }
    }

    flattenAndRecurse(traceEntries, 0);
    return flattenedTraceEntries;
  }

  function renderNext(traceEntries, start, durationColumnWidth) {
    // large numbers of trace entries (e.g. 20,000) render much faster when grouped into sub-divs
    var batchSize;
    var i;
    if (start === 0) {
      // first batch size is smaller to make the records show up on screen right away
      batchSize = 100;
      var maxDuration = 0;
      // find the largest entry duration, not including trace entry exceeded/extended markers which have no duration
      for (i = 0; i < traceEntries.length; i++) {
        var entryDuration = traceEntries[i].durationNanos;
        if (entryDuration) {
          if (traceEntries[i].active) {
            entryDuration *= 10; // need space for the ..
          }
          maxDuration = Math.max(maxDuration, entryDuration);
        }
      }
      durationColumnWidth = formatMillis(maxDuration / 1000000).length / 2 + indent1;
    } else {
      // rest of batches are optimized for total throughput
      batchSize = 500;
    }
    var html = '<div id="block' + start + '">';
    var maxStartOffsetNanos;
    // find the last entry offset, not including trace entry exceeded/extended markers which have no offset
    for (i = traceEntries.length - 1; i >= 0; i--) {
      maxStartOffsetNanos = traceEntries[i].startOffsetNanos;
      if (maxStartOffsetNanos) {
        break;
      }
    }
    var offsetColumnWidth = formatMillis(maxStartOffsetNanos / 1000000).length / 2 + indent1;
    for (i = start; i < Math.min(start + batchSize, traceEntries.length); i++) {
      traceEntries[i].offsetColumnWidth = offsetColumnWidth;
      traceEntries[i].durationColumnWidth = durationColumnWidth;
      html += JST['trace-entry'](traceEntries[i]);
    }
    html += '</div>';
    $('#sps').append(html);
    if (start + 100 < traceEntries.length) {
      setTimeout(function () {
        renderNext(traceEntries, start + batchSize, durationColumnWidth);
      }, 10);
    }
  }

  function basicToggle(parent) {
    var expanded = parent.find('.gt-expanded-content');
    var unexpanded = parent.find('.gt-unexpanded-content');
    if (expanded.hasClass('hide') && !expanded.data('gtExpandedPreviously')) {
      var $clipboardIcon = expanded.find('.fa-clipboard');
      // mouseenter and mouseleave events are to deal with hover style being removed from expanded div
      // see https://github.com/zeroclipboard/zeroclipboard/issues/536
      $clipboardIcon.on('mouseenter', function () {
        expanded.css('background-color', '#ddd');
      });
      expanded.on('mouseleave', function () {
        expanded.css('background-color', '');
      });
      var expandedTextNode = expanded.children('div');
      gtClipboard($clipboardIcon, function () {
        return expandedTextNode[0];
      }, function () {
        var text = expandedTextNode.text().trim();
        // TODO deal with this hacky special case for SQL formatting
        if (text.lastIndexOf('jdbc execution:\n\n', 0) === 0) {
          text = text.substring('jdbc execution:\n\n'.length);
        }
        return text;
      });

      // TODO deal with this hacky special case for SQL formatting
      var text = expandedTextNode.text().trim();
      if (text.lastIndexOf('jdbc execution: ', 0) === 0) {
        var beforeRowsStripped = text.substring('jdbc execution: '.length);
        var beforeParamsStripped = beforeRowsStripped.replace(/ => [0-9]+ rows?$/, '');
        var sql = beforeParamsStripped.replace(/ \[.*?]$/, '');
        var formatted = SqlPrettyPrinter.format(sql);
        if (typeof formatted === 'object') {
          // intentional console logging
          // need conditional since console does not exist in IE9 unless dev tools is open
          if (window.console) {
            console.log(formatted.message);
            console.log(sql);
          }
        } else {
          var rows = beforeRowsStripped.substring(beforeParamsStripped.length);
          var parameters = beforeParamsStripped.substring(sql.length);
          var html = 'jdbc execution:\n\n';
          // simulating pre using span, because with pre tag, when selecting text and copy-pasting from firefox
          // there are extra newlines after the pre tag
          html += '<span style="display: inline-block; margin: 0; white-space: pre-wrap; font-family: monospace;">'
              + formatted + '</span>';
          if (parameters) {
            // the absolutely positioned &nbsp; is just for the copy to clipboard
            html += '\n\nparameters:\n\n<span style="position: absolute;">&nbsp;</span>'
                + '<span style="display: inline-block; margin: 0; padding-left: 15px;">' + parameters + '</span>';
          }
          if (rows) {
            // the absolutely positioned &nbsp; is just for the copy to clipboard
            html += '\n\nrows:\n\n<span style="position: absolute;">&nbsp;</span>'
                + '<span style="display: inline-block; margin: 0; padding-left: 15px;">' + rows + '</span>';
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
      expanded.data('gtExpandedPreviously', true);
    }
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
      var nodeId = 1;

      function initNodeId(node) {
        node.id = nodeId++;
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
        return escapeHtml(text.substring(0, firstHighlightRange[0])) + '<strong>' +
            escapeHtml(text.substring(firstHighlightRange[0], firstHighlightRange[1])) +
            '</strong>' + highlightAndEscapeHtml(text.substring(firstHighlightRange[1]));
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
        ret += '<span class="gt-inline-block" style="width: 4em; margin-left: ' + level + 'em;"';
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
          ret += '<span class="gt-inline-block" style="padding: 1px 1em;">';
          ret += '<span id="gtProfileNodeText' + node.id + '">' + escapeHtml(node.stackTraceElement);
          ret += '</span></span><br>';
        } else {
          ret += '<span>';
          ret += '<span class="gt-inline-block gt-unexpanded-content" style="vertical-align: top;">';
          ret += '<span class="gt-link-color"><strong>...</strong> </span>';
          ret += '<span id="gtProfileNodeTextUnexpanded' + nodes[nodes.length - 1].id + '">';
          ret += escapeHtml(nodes[nodes.length - 1].stackTraceElement) + '</span><br></span>';
          ret += '<span style="visibility: hidden;"><strong>...</strong> </span>';
          ret += '<span class="gt-inline-block gt-expanded-content hide" style="vertical-align: top;">';
          $.each(nodes, function (index, node) {
            ret += '<span id="gtProfileNodeText' + node.id + '">' + escapeHtml(node.stackTraceElement)
                + '</span><br>';
          });
          ret += '</span></span><br>';
        }
        if (node.leafThreadState) {
          ret += '<span class="gt-inline-block" style="width: 4em; margin-left: ' + (level + 2) + 'em;"></span>';
          ret += '<span style="visibility: hidden;"><strong>...</strong> </span>';
          ret += '<span class="gt-inline-block" style="padding: 1px 1em;"';
          ret += ' id="gtProfileNodeThreadState' + node.id + '">';
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
          ret += '<span class="gt-inline-block" style="width: 4em; margin-left: ' + (level + 1) + 'em;"></span>';
          ret += '<span style="visibility: hidden;"><strong>...</strong> </span>';
          ret += '<span class="gt-inline-block" style="padding: 1px 1em;">(one or more branches ~ ';
          ret += formatPercent(ellipsedSamplePercentage);
          ret += '%)</span></div>';
        }
        if (!SHOW_ELLIPSED_NODE_MARKERS && !node.childNodes && !node.leafThreadState) {
          ret += '<div id="gtProfileNodeEllipsed' + node.id + '">';
          ret += '<span class="gt-inline-block" style="width: 4em; margin-left: ' + (level + 1) + 'em;"></span>';
          ret += '<span style="visibility: hidden;"><strong>...</strong> </span>';
          ret += '<span class="gt-inline-block" style="padding: 1px 1em;">(truncated branches)';
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
    var num = bytes / Math.pow(1024, Math.floor(number));
    if (number === 0) {
      return num.toFixed(0) + ' bytes';
    } else {
      return num.toFixed(1) + ' ' + units[number];
    }
  }

  function formatMillis(number) {
    if (Math.abs(number) < 0.0000005) {
      // less than 0.5 nanoseconds
      return '0.0';
    }
    if (Math.abs(number) < 0.000001) {
      // between 0.5 and 1 nanosecond (round up)
      return '0.000001';
    }
    if (Math.abs(number) < 0.00001) {
      // less than 10 nanoseconds
      return number.toPrecision(1);
    }
    if (Math.abs(number) < 1) {
      return number.toPrecision(2);
    }
    return number.toFixed(1);
  }

  function formatCount(number) {
    if (number === undefined) {
      return '';
    }
    if (Math.abs(number) < 0.1) {
      return number.toPrecision(1);
    }
    return number.toFixed(1);
  }

  function formatPercent(number) {
    if (number === 100) {
      return '100';
    }
    if (number > 99.9) {
      // don't round up to 100 since that looks incorrect in UI
      return '99.9';
    }
    if (number === 0) {
      return '0';
    }
    if (number < 0.1) {
      // don't round down to 0 since that looks incorrect in UI
      return '0.1';
    }
    return formatCount(number);
  }

  return {
    renderTrace: function (traceHeader, agentId, traceId, checkLiveTraces, $selector) {
      var html = JST.trace(traceHeader);
      $selector.html(html);
      $selector.addClass('gt-trace-parent');
      if (agentId !== undefined) {
        $selector.data('gtAgentId', agentId);
        $selector.data('gtTraceId', traceId);
        $selector.data('gtCheckLiveTraces', checkLiveTraces);
      }
    },
    renderTraceFromExport: function (traceHeader, $selector, traceEntries, mainThreadProfile, auxThreadProfile) {
      $selector.data('gtTraceEntries', traceEntries);
      $selector.data('gtMainThreadProfile', mainThreadProfile);
      $selector.data('gtAuxThreadProfile', auxThreadProfile);
      this.renderTrace(traceHeader, undefined, undefined, false, $selector);
    },
    formatBytes: formatBytes,
    formatMillis: formatMillis,
    formatCount: formatCount,
    profileToggle: profileToggle
  };
})();
