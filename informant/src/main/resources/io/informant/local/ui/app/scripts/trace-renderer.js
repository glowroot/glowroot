/*
 * Copyright 2012-2013 the original author or authors.
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

/* global $, Handlebars, moment, JST */
/* jshint strict: false */

// not using angular so that this can be embedded into export.html
// without needing to embed large angular.js as well
var TraceRenderer = (function () {
  // indent1 must be sync'd with $indent1 sass variable in trace-span.less
  var indent1 = 1; // em

  Handlebars.registerHelper('eachKeyValuePair', function (map, options) {
    var buffer = '';
    if (map) {
      $.each(map, function (key, value) {
        buffer += options.fn({ key: key, value: value });
      });
    }
    return buffer;
  });

  Handlebars.registerHelper('eachMetricOrdered', function (metrics, options) {
    // mutating original list seems fine here
    metrics.sort(function (a, b) {
      return b.total - a.total;
    });
    var buffer = '';
    $.each(metrics, function (index, metric) {
      buffer += options.fn(metric);
    });
    return buffer;
  });

  Handlebars.registerHelper('eachGarbageCollectorInfoOrdered', function (gcInfos, options) {
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

  Handlebars.registerHelper('ifMoreThanOne', function (num, options) {
    if (num > 1) {
      return options.fn(this);
    }
    return options.inverse(this);
  });

  Handlebars.registerHelper('date', function (timestamp) {
    // TODO internationalize time format
    return moment(timestamp).format('h:mm:ss.SSS a (Z)');
  });

  Handlebars.registerHelper('nanosToMillis', function (nanos) {
    return (nanos / 1000000).toFixed(1);
  });

  Handlebars.registerHelper('messageDetailHtml', function (detail) {
    var messageDetailHtml = function (detail) {
      var ret = '';
      $.each(detail, function (propName, propVal) {
        // need to check not null since typeof null == 'object'
        if (propVal !== null && typeof propVal === 'object') {
          ret += propName + ':<br><div class="indent1">' + messageDetailHtml(propVal) + '</div>';
        } else {
          ret += '<div class="break-word second-line-indent">' + propName + ': ' + propVal + '</div>';
        }
      });
      return ret;
    };
    return messageDetailHtml(detail);
  });

  Handlebars.registerHelper('ifLongMessage', function (message, options) {
    if (message.length > spanLineLength) {
      return options.fn(this);
    }
    return options.inverse(this);
  });

  Handlebars.registerHelper('errorIndentClass', function (message) {
    if (message) {
      return 'indent2';
    }
    return '';
  });

  Handlebars.registerHelper('exceptionIndentClass', function (message) {
    if (message) {
      return 'indent1';
    }
    return 'indent2';
  });

  Handlebars.registerHelper('addIndent2', function (width) {
    return width + 2 * indent1;
  });

  Handlebars.registerHelper('spanIndent', function (span) {
    return indent1 * (1 + span.nestingLevel) + span.offsetColumnWidth;
  });

  Handlebars.registerHelper('firstPart', function (message) {
    // -3 to leave room in the middle for ' ... '
    return message.slice(0, spanLineLength / 2 - 3);
  });

  Handlebars.registerHelper('lastPart', function (message) {
    // -3 to leave room in the middle for ' ... '
    return message.slice(-(spanLineLength / 2 - 3));
  });

  Handlebars.registerHelper('exceptionHtml', function (exception) {
    var html = '<strong>';
    while (exception) {
      html += exception.display + '</strong><br>';
      var i;
      for (i = 0; i < exception.stackTrace.length; i++) {
        html += '<div class="stack-trace-element">at ' + exception.stackTrace[i] + '</div>';
      }
      if (exception.framesInCommon) {
        html += '... ' + exception.framesInCommon + ' more<br>';
      }
      exception = exception.cause;
      if (exception) {
        html += '<strong>Caused by: ';
      }
    }
    return html;
  });

  Handlebars.registerHelper('stackTraceHtml', function (stackTrace) {
    var html = '';
    var i;
    for (i = 0; i < stackTrace.length; i++) {
      html += stackTrace[i] + '<br>';
    }
    return html;
  });

  $(document).on('click', 'button.download-trace', function () {
    window.location = 'export/' + $(this).data('trace-id');
  });
  var mousedownSpanPageX, mousedownSpanPageY;
  $(document).mousedown(function (e) {
    mousedownSpanPageX = e.pageX;
    mousedownSpanPageY = e.pageY;
  });
  $(document).on('click', '.unexpanded-content, .expanded-content', function (e, keyboard) {
    smartToggle($(this).parent(), e, keyboard);
  });
  $(document).on('click', '.sps-toggle', function () {
    var detailTrace = $(this).parents('.trace-parent').data('trace');
    toggleSpans(detailTrace);
  });
  $(document).on('click', '.mst-coarse-toggle', function () {
    var detailTrace = $(this).parents('.trace-parent').data('trace');
    toggleMergedStackTree(detailTrace.coarseMergedStackTree, $('#mstCoarseOuter'));
  });
  $(document).on('click', '.mst-fine-toggle', function () {
    var detailTrace = $(this).parents('.trace-parent').data('trace');
    toggleMergedStackTree(detailTrace.fineMergedStackTree, $('#mstFineOuter'));
  });

  var spanLineLength;

  function initSpanLineLength() {
    // using an average character (width-wise) 'o'
    $('body').prepend('<span class="offscreen" id="bodyFontCharWidth">o</span>');
    var charWidth = $('#bodyFontCharWidth').width();
    // -100 for the left margin of the span lines
    spanLineLength = ($('#sps').width() - 100) / charWidth;
    // min value of 80, otherwise not enough context provided by the elipsed line
    spanLineLength = Math.max(spanLineLength, 80);
  }

  function toggleSpans(detailTrace) {
    var $sps = $('#sps');
    if (!$sps.html()) {
      // first time opening
      initSpanLineLength();
      $sps.removeClass('hide');
      renderNext(detailTrace.spans, 0);
    } else {
      $sps.toggleClass('hide');
    }
  }

  function renderNext(spans, start) {
    // large numbers of spans (e.g. 20,000) render much faster when grouped into sub-divs
    var batchSize;
    if (start === 0) {
      // first batch size is smaller to make the records show up on screen right away
      batchSize = 100;
    } else {
      // rest of batches are optimized for total throughput
      batchSize = 500;
    }
    var html = '<div id="block' + start + '">';
    var i;
    for (i = start; i < Math.min(start + batchSize, spans.length); i++) {
      var maxDurationMillis = (spans[0].duration / 1000000).toFixed(1);
      spans[i].offsetColumnWidth = maxDurationMillis.length / 2 + indent1;
      html += JST.span(spans[i]);
    }
    html += '</div>';
    $('#sps').append(html);
    if (start + 100 < spans.length) {
      setTimeout(function () {
        renderNext(spans, start + batchSize);
      }, 10);
    }
  }

  function basicToggle(parent) {
    var expanded = parent.find('.expanded-content');
    var unexpanded = parent.find('.unexpanded-content');
    unexpanded.toggleClass('hide');
    expanded.toggleClass('hide');
    if (unexpanded.hasClass('hide')) {
      expanded.focus();
    } else {
      unexpanded.focus();
    }
  }

  function smartToggle(parent, e, keyboard) {
    if (keyboard) {
      basicToggle(parent);
      return;
    }
    if (Math.abs(e.pageX - mousedownSpanPageX) > 5 || Math.abs(e.pageY - mousedownSpanPageY) > 5) {
      // not a simple single click, probably highlighting text
      return;
    }
    parent.find('.expanded-content').toggleClass('hide');
    parent.find('.unexpanded-content').toggleClass('hide');
  }

  function toggleMergedStackTree(rootNode, selector) {
    function escapeHtml(html) {
      return html.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    function curr(node, level, metricName) {
      var rootNodeSampleCount;
      var nodeSampleCount;
      if (metricName) {
        rootNodeSampleCount = rootNode.metricNameCounts[metricName] || 0;
        nodeSampleCount = node.metricNameCounts[metricName] || 0;
        if (nodeSampleCount === 0) {
          return '';
        }
      } else {
        rootNodeSampleCount = rootNode.sampleCount;
        nodeSampleCount = node.sampleCount;
      }
      if (nodeSampleCount < rootNodeSampleCount) {
        level++;
      }
      var ret = '<span class="inline-block" style="width: 4em; margin-left: ' + ((level / 3)) + 'em;">';
      var samplePercentage = (nodeSampleCount / rootNodeSampleCount) * 100;
      ret += samplePercentage.toFixed(1);
      // the space after the % is actually important when highlighting a block of stack trace
      // elements in the ui and copy pasting into the eclipse java stack trace console, because the
      // space gives separation between the percentage and the stack trace element and so eclipse is
      // still able to understand the stack trace
      ret += '% </span>';
      ret += escapeHtml(node.stackTraceElement) + '<br>';
      if (node.leafThreadState) {
        // each indent is 1/3em, so adding extra .333em to indent thread state
        ret += '<span class="inline-block" style="width: 4.333em; margin-left: ' + ((level / 3)) + 'em;">';
        ret += '</span>';
        ret += escapeHtml(node.leafThreadState);
        ret += '<br>';
      }
      if (node.childNodes) {
        var childNodes = node.childNodes;
        // order child nodes by sampleCount (descending)
        childNodes.sort(function (a, b) {
          if (metricName) {
            return (b.metricNameCounts[metricName] || 0) - (a.metricNameCounts[metricName] || 0);
          }
          return b.sampleCount - a.sampleCount;
        });
        var i;
        for (i = 0; i < childNodes.length; i++) {
          ret += curr(childNodes[i], level, metricName);
        }
      }
      return ret;
    }

    var $selector = $(selector);
    if (!$selector.hasClass('hide')) {
      $selector.addClass('hide');
    } else {
      if (!$selector.find('.mst-interesting').html()) {
        // first time only, process merged stack tree and populate dropdown
        calculateMetricNameCounts(rootNode);
        // build tree
        var tree = { name: '', childNodes: {} };
        $.each(rootNode.metricNameCounts, function (metricName) {
          // only really need to look at leafs (' / other') to hit all nodes
          if (metricName.match(/ \/ other$/)) {
            var parts = metricName.split(' / ');
            var node = tree;
            var partialName = '';
            $.each(parts, function (i, part) {
              if (i > 0) {
                partialName += ' / ';
              }
              partialName += part;
              if (!node.childNodes[part]) {
                node.childNodes[part] = { name: partialName, childNodes: {} };
              }
              node = node.childNodes[part];
            });
          }
        });
        var nodesDepthFirst = function (node) {
          var all = [ node ];
          // order by count desc
          var childNodes = [];
          $.each(node.childNodes, function (name, childNode) {
            childNodes.push(childNode);
          });
          childNodes.sort(function (a, b) {
            return rootNode.metricNameCounts[b.name] - rootNode.metricNameCounts[a.name];
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
        // remove the root '' since all nodes are already under the single root span metric
        orderedNodes.splice(0, 1);
        // build filter dropdown
        $selector.find('.mst-filter').html('');
        $.each(orderedNodes, function (i, node) {
          $selector.find('.mst-filter').append($('<option />').val(node.name)
              .text(node.name + ' (' + rootNode.metricNameCounts[node.name] + ')'));
        });
        var interestingRootNode = rootNode;
        var uninterestingHtml = '';
        while (true) {
          if (interestingRootNode.childNodes.length > 1) {
            break;
          }
          var childNode = interestingRootNode.childNodes[0];
          if (childNode.leafThreadState) {
            interestingRootNode = rootNode;
            uninterestingHtml = '';
            break;
          }
          // the space after the % is actually important when highlighting a block of stack trace
          // elements in the ui and copy pasting into the eclipse java stack trace console, because
          // the space gives separation between the percentage and the stack trace element and so
          // eclipse is still able to understand the stack trace
          uninterestingHtml += '<span class="inline-block" style="width: 4em;">100.0% </span>' +
              interestingRootNode.stackTraceElement + '<br>';
          interestingRootNode = childNode;
        }
        $selector.find('.mst-filter').change(function () {
          // update merged stack tree based on filter
          var interestingHtml = curr(interestingRootNode, 0, $(this).val());
          $selector.find('.mst-interesting').html(interestingHtml);
        });
        // build initial merged stack tree
        var interestingHtml = curr(interestingRootNode, 0);
        if (uninterestingHtml) {
          $selector.find('.mst-common .expanded-content').html(uninterestingHtml);
          $selector.find('.mst-common').removeClass('hide');
        }
        $selector.find('.mst-interesting').html(interestingHtml);
      }
      $selector.removeClass('hide');
    }
  }

  function calculateMetricNameCounts(node) {
    var mergedCounts = {};
    if (node.leafThreadState) {
      var partial = '';
      $.each(node.metricNames, function (i, metricName) {
        if (i > 0) {
          partial += ' / ';
        }
        partial += metricName;
        mergedCounts[partial] = node.sampleCount;
      });
      mergedCounts[partial + ' / other'] = node.sampleCount;
    }
    if (node.childNodes) {
      var childNodes = node.childNodes;
      var i;
      var processMetric = function (metricName, count) {
        if (mergedCounts[metricName]) {
          mergedCounts[metricName] += count;
        } else {
          mergedCounts[metricName] = count;
        }
      };
      for (i = 0; i < childNodes.length; i++) {
        var metricNameCounts = calculateMetricNameCounts(childNodes[i]);
        $.each(metricNameCounts, processMetric);
      }
    }
    node.metricNameCounts = mergedCounts;
    return mergedCounts;
  }

  return {
    renderSummary: function (summaryTrace) {
      return JST['trace-summary'](summaryTrace);
    },
    renderDetail: function (detailTrace, selector) {
      var $selector = $(selector);
      var html = JST['trace-summary'](detailTrace) + '<br>' + JST['trace-detail'](detailTrace);
      $selector.html(html);
      $selector.addClass('trace-parent');
      $selector.data('trace', detailTrace);
    }
  };
})();
