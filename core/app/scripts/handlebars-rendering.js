/*
 * Copyright 2012-2015 the original author or authors.
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
/* global $, Handlebars, JST, moment, Glowroot, SqlPrettyPrinter, gtClipboard, console */

// IMPORTANT: DO NOT USE ANGULAR IN THIS FILE
// that would require adding angular to trace-export.js
// and that would significantly increase the size of the exported trace files

var HandlebarsRendering;

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

  Handlebars.registerHelper('eachTimerOrdered', function (timers, options) {
    var buffer = '';

    function traverse(timer, nestingLevel) {
      timer.nestingLevel = nestingLevel;
      buffer += options.fn(timer);
      if (timer.nestedTimers) {
        timer.nestedTimers.sort(function (a, b) {
          return b.total - a.total;
        });
        $.each(timer.nestedTimers, function (index, nestedTimer) {
          traverse(nestedTimer, nestingLevel + 1);
        });
      }
    }

    // add the root node
    traverse(timers, 0);
    return buffer;
  });

  Handlebars.registerHelper('eachTimerFlattenedOrdered', function (timers, options) {
    var flattenedTimerMap = {};
    var flattenedTimers = [];

    function traverse(timer, parentTimerNames) {
      var flattenedTimer = flattenedTimerMap[timer.name];
      if (!flattenedTimer) {
        flattenedTimer = {
          name: timer.name,
          total: timer.total,
          count: timer.count,
          active: timer.active
        };
        flattenedTimerMap[timer.name] = flattenedTimer;
        flattenedTimers.push(flattenedTimer);
      } else if (parentTimerNames.indexOf(timer.name) === -1) {
        // only add to existing flattened timer if the timer isn't appearing under itself
        // (this is possible when they are separated by another timer)
        flattenedTimer.total += timer.total;
        flattenedTimer.active = flattenedTimer.active || timer.active;
        flattenedTimer.count += timer.count;
      }
      if (timer.nestedTimers) {
        $.each(timer.nestedTimers, function (index, nestedTimer) {
          traverse(nestedTimer, parentTimerNames.concat(timer));
        });
      }
    }

    // add the root node
    traverse(timers, []);

    flattenedTimers.sort(function (a, b) {
      return b.total - a.total;
    });
    var buffer = '';
    $.each(flattenedTimers, function (index, timer) {
      buffer += options.fn(timer);
    });
    return buffer;
  });

  Handlebars.registerHelper('ifExists', function (value, options) {
    if (value !== undefined) {
      return options.fn(this);
    }
    return options.inverse(this);
  });

  Handlebars.registerHelper('formatAllocatedBytes', function (bytes) {
    return formatBytes(bytes);
  });

  Handlebars.registerHelper('eachGcInfoOrdered', function (gcInfos, options) {
    // mutating original list seems fine here
    gcInfos.sort(function (a, b) {
      return b.collectionTime - a.collectionTime;
    });
    var buffer = '';
    $.each(gcInfos, function (index, gcInfo) {
      buffer += options.fn(gcInfo);
    });
    return buffer;
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
    return formatMillis(nanos / 1000000);
  });

  Handlebars.registerHelper('microsToMillis', function (micros) {
    return formatMillis(micros / 1000);
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
    if (trace.threadCpuTime || trace.threadBlockedTime || trace.threadWaitedTime || trace.threadAllocatedBytes) {
      return options.fn(this);
    }
    return options.inverse(this);
  });

  // this is for the trace header customDetail
  Handlebars.registerHelper('customDetailHtml', function (detail) {
    return messageDetailHtml(detail, true);
  });

  Handlebars.registerHelper('messageDetailHtml', function (detail) {
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
        ret += '<div style="float: left;">' + maybeBoldPropName(propName) + '&nbsp;</div>'
            + '<div class="gt-trace-attr-value">' + propVal + '</div>';
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
    return indent1 * (1 + traceEntry.nestingLevel);
  });

  Handlebars.registerHelper('timerIndent', function (timer) {
    return indent1 * timer.nestingLevel;
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
    var html = '<div class="gt-preserve-newlines">';
    html += '<strong>';
    while (throwable) {
      html += '<span class="gt-break-word gt-preserve-newlines">' + escapeHtml(throwable.display)
          + '</span></strong>\n';
      var i;
      for (i = 0; i < throwable.stackTrace.length; i++) {
        html += 'at ' + escapeHtml(throwable.stackTrace[i]) + '\n';
      }
      if (throwable.framesInCommon) {
        html += '... ' + throwable.framesInCommon + ' more\n';
      }
      throwable = throwable.cause;
      if (throwable) {
        html += '<strong>Caused by: ';
      }
    }
    html += '</div>';
    return html;
  });

  Handlebars.registerHelper('stackTraceHtml', function (stackTrace) {
    var html = '<div class="gt-preserve-newlines">';
    var i;
    for (i = 0; i < stackTrace.length; i++) {
      html += escapeHtml(stackTrace[i]) + '\n';
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
        // un-hide before building in case there are lots of trace entries, at least can see first few quickly
        $selector.removeClass('hide');
        renderNext(traceEntries, 0);
      } else {
        // this is not an export file
        var traceId = $traceParent.data('gtTraceId');
        $selector.data('gtLoading', true);
        var loaded;
        var spinner;
        var $button = $(this);
        setTimeout(function () {
          if (!loaded) {
            spinner = Glowroot.showSpinner($button.parent().find('.gt-trace-detail-spinner'));
          }
        }, 100);
        $.get('backend/trace/entries?trace-id=' + traceId)
            .done(function (data) {
              if (data.overwritten) {
                $('#sps').append('<div style="padding: 1em;">The trace entries have expired, see' +
                    ' <a href="config/storage#capped-database-size">' +
                    'Configuration &gt; Storage &gt; Capped database size</a></div>');
              } else if (data.expired) {
                $('#sps').append('<div style="padding: 1em;">This trace has expired</div>');
              } else {
                // first time opening
                initTraceEntryLineLength();
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

  $(document).on('click', '.gt-profile-toggle', function () {
    var $traceParent = $(this).parents('.gt-trace-parent');
    var $button = $(this);
    var profile = $traceParent.data('gtProfile');
    var url;
    if (!profile) {
      url = 'backend/trace/profile' + '?trace-id=' + $traceParent.data('gtTraceId');
    }
    profileToggle($button, '#profileOuter', profile, url);
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
                    ' <a href="config/storage#capped-database-size">' +
                    'Configuration &gt; Storage &gt; Capped database size</a></div>');
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
    // -100 for the left margin of the trace entry lines
    traceEntryLineLength = ($('#sps').width() - 100) / charWidth;
    // min value of 80, otherwise not enough context provided by the elipsed line
    traceEntryLineLength = Math.max(traceEntryLineLength, 80);
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
        var entryDuration = traceEntries[i].duration;
        if (entryDuration) {
          maxDuration = Math.max(maxDuration, entryDuration);
        }
      }
      durationColumnWidth = formatMillis(maxDuration / 1000000).length / 2 + indent1;
    } else {
      // rest of batches are optimized for total throughput
      batchSize = 500;
    }
    var html = '<div id="block' + start + '">';
    var maxOffset;
    // find the last entry offset, not including trace entry exceeded/extended markers which have no offset
    for (i = traceEntries.length - 1; i >= 0; i--) {
      maxOffset = traceEntries[i].offset;
      if (maxOffset) {
        break;
      }
    }
    var offsetColumnWidth = formatMillis(maxOffset / 1000000).length / 2 + indent1;
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
          console.log(formatted.message);
          console.log(sql);
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
          var $message = expanded.find('.gt-preserve-newlines');
          $message.html(html);
          $message.css('min-width', 0.6 * unexpanded.width());
        }
      }
      expanded.data('gtExpandedPreviously', true);
    }
    unexpanded.toggleClass('hide');
    expanded.toggleClass('hide');
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

  function buildMergedStackTree(rootNode, selector) {

    // root node is always synthetic root node
    if (rootNode.childNodes.length === 1) {
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

    function filter(filterText) {
      var filterTextUpper = filterText.toUpperCase();
      if (filterText.length === 1) {
        // don't match anything with filter length 1 char
        filterTextUpper = '';
      }

      function highlightAndEscapeHtml(text) {
        if (text === MULTIPLE_ROOT_NODES) {
          // don't highlight any part of "<multiple root nodes>"
          return escapeHtml(text);
        }
        if (!filterTextUpper) {
          return text;
        }
        var index = text.toUpperCase().indexOf(filterTextUpper);
        if (index === -1) {
          return text;
        }
        return escapeHtml(text.substring(0, index)) + '<strong>' +
            escapeHtml(text.substring(index, index + filterTextUpper.length)) +
            '</strong>' + highlightAndEscapeHtml(text.substring(index + filterTextUpper.length));
      }

      function filterNode(node, underMatchingNode) {
        var nodes = [node];
        while (node.childNodes && node.childNodes.length === 1 && !node.leafThreadState && !node.ellipsed) {
          node = node.childNodes[0];
          nodes.push(node);
        }
        var i;
        var currMatchingNode = false;
        var stackTraceElement;
        for (i = 0; i < nodes.length; i++) {
          stackTraceElement = nodes[i].stackTraceElement;
          // don't match any part of "<multiple root nodes>"
          if (stackTraceElement !== MULTIPLE_ROOT_NODES
              && stackTraceElement.toUpperCase().indexOf(filterTextUpper) !== -1) {
            currMatchingNode = true;
            break;
          }
        }
        // only the last node in nodes can be a leaf
        var lastNode = nodes[nodes.length - 1];
        if (lastNode.leafThreadState && lastNode.leafThreadState.toUpperCase().indexOf(filterTextUpper) !== -1) {
          currMatchingNode = true;
        }
        if (currMatchingNode || underMatchingNode) {
          for (i = 0; i < nodes.length; i++) {
            nodes[i].filteredSampleCount = nodes[i].sampleCount;
            nodes[i].filteredEllipsed = nodes[i].ellipsed;
          }
          if (lastNode.childNodes) {
            for (i = 0; i < lastNode.childNodes.length; i++) {
              filterNode(lastNode.childNodes[i], true);
            }
          }
        } else {
          for (i = 0; i < nodes.length; i++) {
            nodes[i].filteredSampleCount = 0;
            nodes[i].filteredEllipsed = false;
          }
          if (lastNode.childNodes) {
            for (i = 0; i < lastNode.childNodes.length; i++) {
              lastNode.filteredSampleCount += filterNode(lastNode.childNodes[i]);
            }
          }
        }
        if (lastNode.ellipsed && lastNode.filteredEllipsed) {
          $('#gtProfileNodeEllipsed' + lastNode.id).show();
        } else if (lastNode.ellipsed) {
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
        var samplePercentage = (lastNode.filteredSampleCount / rootNode.sampleCount) * 100;
        $('#gtProfileNodePercent' + lastNode.id).text(samplePercentage.toFixed(1) + '%');
        $('#gtProfileNode' + lastNode.id).show();
        if (nodes.length > 1) {
          var nodeTextParent = $('#gtProfileNodeText' + lastNode.id).parent();
          var nodeTextParentParent = $('#gtProfileNodeText' + lastNode.id).parent().parent();
          var unexpanded = nodeTextParentParent.find('.gt-unexpanded-content');
          if (currMatchingNode && filterTextUpper) {
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

      filterNode(rootNode);
    }

    function generateHtml(timer) {

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
        while (node.childNodes && node.childNodes.length === 1 && !node.leafThreadState && !node.ellipsed) {
          node = node.childNodes[0];
          nodes.push(node);
        }
        var ret = '<span id="gtProfileNode' + node.id + '">';
        ret += '<span class="gt-inline-block" style="width: 4em; margin-left: ' + level + 'em;"';
        ret += ' id="gtProfileNodePercent' + node.id + '">';
        var samplePercentage = (nodeSampleCount / rootNode.sampleCount) * 100;
        ret += samplePercentage.toFixed(1);
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
            ret += '<span id="gtProfileNodeText' + node.id + '">' + escapeHtml(node.stackTraceElement) + '</span><br>';
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
        if (node.ellipsed) {
          ret += '<div id="gtProfileNodeEllipsed' + node.id + '">';
          ret += '<span class="gt-inline-block" style="width: 4em; margin-left: ' + (level + 1) + 'em;"></span>';
          ret += '<span style="visibility: hidden;"><strong>...</strong> </span>';
          ret += '<span class="gt-inline-block" style="padding: 1px 1em;">(some branches < 0.1%)</span></div>';
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

    var mergedCounts = calculateTimerCounts(rootNode, []);
    // set up text filter
    var $profileTextFilter = $selector.find('.gt-profile-text-filter');
    var timer;
    $profileTextFilter.off('input.gtProfileFilter');
    $profileTextFilter.on('input.gtProfileFilter', function () {
      // primarily timer is used to deal with lagging when holding down backspace to clear out filter
      clearTimeout(timer);
      timer = setTimeout(function () {
        // update merged stack tree based on filter
        var filterText = $profileTextFilter.val();
        filter(filterText);
      }, 50);
    });
    // apply initial filter text if any (e.g. user changes Last 30 min to Last 60 min) triggering profile refresh
    // but filter text stays the same (which seems good)
    var filterText = $profileTextFilter.val();
    if (filterText) {
      filter(filterText);
    }
    var $profileFilter = $selector.find('.gt-profile-filter');
    var $switchFilterButton = $profileFilter.parent().find(':button');
    if (!$.isEmptyObject(mergedCounts)) {
      // build tree
      var tree = {name: '', childNodes: {}};
      $.each(rootNode.timerCounts, function (timer) {
        // only really need to look at leafs (' / other') to hit all nodes
        if (timer.match(/ \/ other$/)) {
          var parts = timer.split(' / ');
          var node = tree;
          var partialName = '';
          $.each(parts, function (i, part) {
            if (i > 0) {
              partialName += ' / ';
            }
            partialName += part;
            if (!node.childNodes[part]) {
              node.childNodes[part] = {name: partialName, childNodes: {}};
            }
            node = node.childNodes[part];
          });
        }
      });
      var nodesDepthFirst = function (node) {
        var all = [node];
        // order by count desc
        var childNodes = [];
        $.each(node.childNodes, function (name, childNode) {
          childNodes.push(childNode);
        });
        childNodes.sort(function (a, b) {
          return rootNode.timerCounts[b.name] - rootNode.timerCounts[a.name];
        });
        if (childNodes.length === 1 && childNodes[0].name.match(/ \/ other$/)) {
          // skip if single 'other' node (in which case it will be represented by current node)
          return all;
        }
        $.each(childNodes, function (i, childNode) {
          all = all.concat(nodesDepthFirst(childNode));
        });
        return all;
      };

      var orderedNodes = nodesDepthFirst(tree);
      if (Object.keys(tree.childNodes).length === 1) {
        // remove the root '' since all nodes are already under the single root timer
        orderedNodes.splice(0, 1);
      } else {
        var sampleCount = 0;
        $.each(tree.childNodes, function (name, childNode) {
          sampleCount += rootNode.timerCounts[childNode.name];
        });
        rootNode.timerCounts[tree.name] = sampleCount;
      }
      // build filter dropdown
      $.each(orderedNodes, function (i, node) {
        var name = node.name || MULTIPLE_ROOT_NODES;
        $profileFilter.append($('<option />').val(node.name)
            .text(name + ' (' + rootNode.timerCounts[node.name] + ')'));
      });
      $profileFilter.change(function () {
        // update merged stack tree based on filter
        var html = generateHtml($(this).val());
        $selector.find('.gt-profile').html(html);
      });
      // remove previous click handler, e.g. when range filter is changed
      $switchFilterButton.off('click').click(function () {
        $profileTextFilter.toggleClass('hide');
        $profileFilter.toggleClass('hide');
        if ($profileFilter.is(':visible')) {
          $selector.data('gtTextFilterOverride', false);
          $profileTextFilter.val('');
          filter('');
          $switchFilterButton.text('Switch to text filter');
        } else {
          $selector.data('gtTextFilterOverride', true);
          $profileFilter.find('option:first-child').attr('selected', 'selected');
          var html = generateHtml();
          $selector.find('.gt-profile').html(html);
          $switchFilterButton.text('Switch to dropdown filter');
        }
      });
    }
    if ($.isEmptyObject(mergedCounts) || $selector.data('gtTextFilterOverride')) {
      $profileTextFilter.removeClass('hide');
    } else {
      $profileFilter.removeClass('hide');
      $switchFilterButton.removeClass('hide');
    }
  }

  function calculateTimerCounts(node, timerNameStack) {
    var numTimerNamesAdded = 0;
    if (node.timerNames && node.timerNames.length) {
      $.each(node.timerNames, function (i, timerName) {
        if (timerName !== timerNameStack[timerNameStack.length - 1]) {
          timerNameStack.push(timerName);
          numTimerNamesAdded++;
        }
      });
    }
    var mergedCounts = {};
    if (node.leafThreadState && timerNameStack.length) {
      var partial = '';
      $.each(timerNameStack, function (i, timerName) {
        if (i > 0) {
          partial += ' / ';
        }
        partial += timerName;
        mergedCounts[partial] = node.sampleCount;
      });
      mergedCounts[partial + ' / other'] = node.sampleCount;
    }
    if (node.childNodes) {
      var childNodes = node.childNodes;
      var i;
      var processTimer = function (timer, count) {
        if (mergedCounts[timer]) {
          mergedCounts[timer] += count;
        } else {
          mergedCounts[timer] = count;
        }
      };
      for (i = 0; i < childNodes.length; i++) {
        var timerCounts = calculateTimerCounts(childNodes[i], timerNameStack);
        $.each(timerCounts, processTimer);
      }
    }
    node.timerCounts = mergedCounts;
    if (numTimerNamesAdded !== 0) {
      timerNameStack.splice(-numTimerNamesAdded, numTimerNamesAdded);
    }
    return mergedCounts;
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
    return (bytes / Math.pow(1024, Math.floor(number))).toFixed(1) + ' ' + units[number];
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
    if (Math.abs(number) < 0.1) {
      return number.toPrecision(1);
    }
    return number.toFixed(1);
  }

  return {
    renderTrace: function (trace, $selector) {
      var html = JST.trace(trace);
      $selector.html(html);
      $selector.addClass('gt-trace-parent');
      $selector.data('gtTraceId', trace.id);
    },
    renderTraceFromExport: function (trace, $selector, traceEntries, profile) {
      $selector.data('gtTraceEntries', traceEntries);
      $selector.data('gtProfile', profile);
      this.renderTrace(trace, $selector);
    },
    formatBytes: formatBytes,
    formatMillis: formatMillis,
    formatCount: formatCount,
    profileToggle: profileToggle
  };
})();
