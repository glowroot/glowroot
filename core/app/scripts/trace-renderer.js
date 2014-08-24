/*
 * Copyright 2012-2014 the original author or authors.
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
/* global $, Handlebars, JST, moment, Glowroot, alert */

// IMPORTANT: DO NOT USE ANGULAR IN THIS FILE
// that would require adding angular to trace-export.js
// and that would significantly increase the size of the exported trace files

var TraceRenderer;

TraceRenderer = (function () {
  // indent1 must be sync'd with $indent1 variable in trace-span.less
  var indent1 = 1; // em

  Handlebars.registerHelper('eachKeyValuePair', function (map, options) {
    var buffer = '';
    if (map) {
      $.each(map, function (key, values) {
        $.each(values, function (index, value) {
          buffer += options.fn({ key: key, value: value });
        });
      });
    }
    return buffer;
  });


  Handlebars.registerHelper('eachMetricOrdered', function (metrics, options) {
    var buffer = '';

    function traverse(metric, nestingLevel) {
      metric.nestingLevel = nestingLevel;
      buffer += options.fn(metric);
      if (metric.nestedMetrics) {
        metric.nestedMetrics.sort(function (a, b) {
          return b.total - a.total;
        });
        $.each(metric.nestedMetrics, function (index, nestedMetric) {
          traverse(nestedMetric, nestingLevel + 1);
        });
      }
    }

    // add the root node
    traverse(metrics, 0);
    return buffer;
  });

  Handlebars.registerHelper('eachMetricFlattenedOrdered', function (metrics, options) {
    var flattenedMetricMap = {};
    var flattenedMetrics = [];

    function traverse(metric, parentMetricNames) {
      var flattenedMetric = flattenedMetricMap[metric.name];
      if (!flattenedMetric) {
        flattenedMetric = {
          name: metric.name,
          total: metric.total,
          min: metric.min,
          max: metric.max,
          count: metric.count,
          active: metric.active,
          minActive: metric.minActive,
          maxActive: metric.maxActive
        };
        flattenedMetricMap[metric.name] = flattenedMetric;
        flattenedMetrics.push(flattenedMetric);
      } else if (parentMetricNames.indexOf(metric.name) === -1) {
        // only add to existing flattened metric if the trace metric isn't appearing under itself
        // (this is possible when they are separated by another trace metric)
        flattenedMetric.total += metric.total;
        flattenedMetric.active = flattenedMetric.active || metric.active;
        if (metric.min < flattenedMetric.min) {
          flattenedMetric.min = metric.min;
          flattenedMetric.minActive = metric.minActive;
        }
        if (metric.max > flattenedMetric.max) {
          flattenedMetric.max = metric.max;
          flattenedMetric.maxActive = metric.maxActive;
        }
        flattenedMetric.count += metric.count;
      }
      if (metric.nestedMetrics) {
        $.each(metric.nestedMetrics, function (index, nestedMetric) {
          traverse(nestedMetric, parentMetricNames.concat(metric));
        });
      }
    }

    // add the root node
    traverse(metrics, []);

    flattenedMetrics.sort(function (a, b) {
      return b.total - a.total;
    });
    var buffer = '';
    $.each(flattenedMetrics, function (index, metric) {
      buffer += options.fn(metric);
    });
    return buffer;
  });

  Handlebars.registerHelper('eachTransactionMetricOrdered', function (transactionMetrics, options) {
    var buffer = '';

    function traverse(transactionMetrics, nestingLevel) {
      transactionMetrics.nestingLevel = nestingLevel;
      buffer += options.fn(transactionMetrics);
      if (transactionMetrics.nestedMetrics) {
        transactionMetrics.nestedMetrics.sort(function (a, b) {
          return b.totalMicros - a.totalMicros;
        });
        $.each(transactionMetrics.nestedMetrics, function (index, nestedMetric) {
          traverse(nestedMetric, nestingLevel + 1);
        });
      }
    }

    // add the root node
    traverse(transactionMetrics, 0);
    return buffer;
  });

  Handlebars.registerHelper('eachTransactionMetricFlattenedOrdered', function (metrics, options) {
    var flattenedMetricMap = {};
    var flattenedMetrics = [];

    function traverse(metric, parentMetricNames) {
      var flattenedMetric = flattenedMetricMap[metric.name];
      if (!flattenedMetric) {
        flattenedMetric = {
          name: metric.name,
          totalMicros: metric.totalMicros,
          count: metric.count
        };
        flattenedMetricMap[metric.name] = flattenedMetric;
        flattenedMetrics.push(flattenedMetric);
      } else if (parentMetricNames.indexOf(metric.name) === -1) {
        // only add to existing flattened metric if the transaction metric isn't appearing under itself
        // (this is possible when they are separated by another transaction metric)
        flattenedMetric.totalMicros += metric.totalMicros;
        flattenedMetric.count += metric.count;
      }
      if (metric.nestedMetrics) {
        $.each(metric.nestedMetrics, function (index, nestedMetric) {
          traverse(nestedMetric, parentMetricNames.concat(metric));
        });
      }
    }

    // add the root node
    traverse(metrics, []);

    flattenedMetrics.sort(function (a, b) {
      return b.totalMicros - a.totalMicros;
    });
    var buffer = '';
    $.each(flattenedMetrics, function (index, metric) {
      buffer += options.fn(metric);
    });
    return buffer;
  });

  Handlebars.registerHelper('ifThreadInfo', function (threadInfo, options) {
    if (threadInfo &&
        (threadInfo.threadCpuTime || threadInfo.threadBlockedTime || threadInfo.threadWaitedTime ||
            threadInfo.threadAllocatedBytes)) {
      return options.fn(this);
    }
    return options.inverse(this);
  });

  Handlebars.registerHelper('ifExists', function (value, options) {
    if (value !== undefined) {
      return options.fn(this);
    }
    return options.inverse(this);
  });

  Handlebars.registerHelper('formatAllocatedBytes', function (bytes) {
    var units = ['bytes', 'KB', 'MB', 'GB', 'TB'];
    var number = Math.floor(Math.log(bytes) / Math.log(1024));
    return (bytes / Math.pow(1024, Math.floor(number))).toFixed(2) + ' ' + units[number];
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

  Handlebars.registerHelper('ifMoreThanOne', function (num, options) {
    if (num > 1) {
      return options.fn(this);
    }
    return options.inverse(this);
  });

  Handlebars.registerHelper('date', function (timestamp) {
    // TODO internationalize time format
    return moment(timestamp).format('YYYY-MM-DD h:mm:ss.SSS a (Z)');
  });

  Handlebars.registerHelper('nanosToMillis', function (nanos) {
    return (nanos / 1000000).toFixed(1);
  });

  Handlebars.registerHelper('microsToMillis', function (micros) {
    return (micros / 1000).toFixed(1);
  });

  Handlebars.registerHelper('divideMicrosByCount', function (micros, count) {
    // and convert to millis
    return ((micros / count) / 1000).toFixed(1);
  });

  Handlebars.registerHelper('divideCountByCount', function (count1, count2) {
    return (count1 / count2).toFixed(1);
  });

  Handlebars.registerHelper('ifExistenceExpired', function (existence, options) {
    if (existence === 'expired') {
      return options.fn(this);
    }
    return options.inverse(this);
  });

  Handlebars.registerHelper('ifExistenceYes', function (existence, options) {
    if (existence === 'yes') {
      return options.fn(this);
    }
    return options.inverse(this);
  });

  Handlebars.registerHelper('messageDetailHtml', function (detail) {
    var messageDetailHtml = function (detail) {
      var ret = '';
      $.each(detail, function (propName, propVal) {
        if ($.isArray(propVal)) {
          // array values are supported to simulate multimaps, e.g. for http request parameters and http headers, both
          // of which can have multiple values for the same key
          $.each(propVal, function (i, propVal) {
            ret += '<div class="break-word second-line-indent">' + propName + ': ' + propVal + '</div>';
          });
        } else if (typeof propVal === 'object' && propVal !== null) {
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
    return indent1 * (1 + span.nestingLevel);
  });

  Handlebars.registerHelper('metricIndent', function (metric) {
    return indent1 * metric.nestingLevel;
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

  // TODO register these handlers on trace modal each time one is opened instead of globally on $(document)
  // TODO (and make sure it still works on export files)
  $(document).on('click', 'button.download-trace', function () {
    var $traceParent = $(this).parents('.trace-parent');
    var traceId = $traceParent.data('traceId');
    window.location = 'export/trace/' + traceId;
  });
  $(document).on('click', 'button.download-transaction', function () {
    var $transactionParent = $(this).parents('.transaction-parent');
    var queryString = $transactionParent.data('queryString');
    window.location = 'export/transaction?' + queryString + '&truncateLeafPercentage=0.002';
  });
  var mousedownPageX, mousedownPageY;
  $(document).mousedown(function (e) {
    mousedownPageX = e.pageX;
    mousedownPageY = e.pageY;
  });
  $(document).on('click', '.metric-view-toggle', function () {
    $(this).parents('.trace-metrics').children('table').toggleClass('hide');
  });
  $(document).on('click', '.unexpanded-content, .expanded-content', function (e, keyboard) {
    smartToggle($(this).parent(), e, keyboard);
  });
  $(document).on('click', '.sps-toggle', function () {
    var $selector = $('#sps');
    if ($selector.data('loading')) {
      // handles rapid clicking when loading from url
      return;
    }
    if (!$selector.data('loaded')) {
      var $traceParent = $(this).parents('.trace-parent');
      var spans = $traceParent.data('spans');
      if (spans) {
        // this is an export file
        $selector.data('loaded', true);
        // first time opening
        initSpanLineLength();
        // un-hide before building in case there are lots of spans, at least can see first few quickly
        $selector.removeClass('hide');
        renderNext(spans, 0);
      } else {
        // this is not an export file
        var traceId = $traceParent.data('traceId');
        $selector.data('loading', true);
        var loaded;
        var spinner;
        var $button = $(this);
        setTimeout(function () {
          if (!loaded) {
            spinner = Glowroot.showSpinner($button.parent().find('.trace-detail-spinner'));
          }
        }, 100);
        $.get('backend/trace/spans?trace-id=' + traceId)
            .done(function (data) {
              // first time opening
              initSpanLineLength();
              // un-hide before building in case there are lots of spans, at least can see first few quickly
              $selector.removeClass('hide');
              renderNext(data, 0);
            })
            .fail(function (jqXHR, textStatus, errorThrown) {
              // TODO handle this better
              alert('Error occurred: ' + textStatus);
            })
            .always(function () {
              loaded = true;
              if (spinner) {
                spinner.stop();
              }
              $selector.data('loading', false);
              $selector.data('loaded', true);
            });
      }
    } else if ($selector.hasClass('hide')) {
      $selector.removeClass('hide');
    } else {
      $selector.addClass('hide');
    }
  });
  $(document).on('click', '.profile-toggle', function () {
    var $traceParent = $(this).parents('.trace-parent');
    var $button = $(this);
    var traceId = $traceParent.data('traceId');
    profileToggle($button, $traceParent, '#profileOuter', 'profile',
            'backend/trace/profile' + '?trace-id=' + traceId);
  });
  $(document).on('click', '.outlier-profile-toggle', function () {
    var $traceParent = $(this).parents('.trace-parent');
    var $button = $(this);
    var traceId = $traceParent.data('traceId');
    profileToggle($button, $traceParent, '#outlierProfileOuter', 'outlierProfile',
            'backend/trace/outlier-profile' + '?trace-id=' + traceId);
  });
  $(document).on('click', '.transaction-profile-toggle', function () {
    var $transactionParent = $(this).parents('.transaction-parent');
    var $button = $(this);
    var queryString = $transactionParent.data('queryString');
    profileToggle($button, $transactionParent, '#profileOuter', 'profile',
            'backend/transaction/profile?' + queryString);
  });

  function profileToggle($button, $parent, selector, parentDataAttribute, url) {
    var $selector = $(selector);
    if ($selector.data('loading')) {
      // handles rapid clicking when loading from url
      return;
    }
    if (!$selector.data('loaded')) {
      var profile = $parent.data(parentDataAttribute);
      if (profile) {
        // this is an export file
        buildMergedStackTree(profile, $selector);
        $selector.removeClass('hide');
        $selector.data('loaded', true);
      } else {
        $selector.data('loading', true);
        var loaded;
        var spinner;
        setTimeout(function () {
          if (!loaded) {
            spinner = Glowroot.showSpinner($button.parent().find('.trace-detail-spinner'));
          }
        }, 100);
        $.get(url)
            .done(function (data) {
              buildMergedStackTree(data, $selector);
              $selector.removeClass('hide');
            })
            .fail(function (jqXHR, textStatus, errorThrown) {
              // TODO handle this better
              alert('Error occurred: ' + textStatus);
            })
            .always(function () {
              loaded = true;
              if (spinner) {
                spinner.stop();
              }
              $selector.data('loading', false);
              $selector.data('loaded', true);
            });
      }
    } else if ($selector.hasClass('hide')) {
      $selector.removeClass('hide');
    } else {
      $selector.addClass('hide');
    }
  }

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
    if (Math.abs(e.pageX - mousedownPageX) > 5 || Math.abs(e.pageY - mousedownPageY) > 5) {
      // not a simple single click, probably highlighting text
      return;
    }
    parent.find('.expanded-content').toggleClass('hide');
    parent.find('.unexpanded-content').toggleClass('hide');
  }

  function buildMergedStackTree(rootNode, selector) {
    function escapeHtml(html) {
      return html.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    function curr(node, level, metric) {
      var rootNodeSampleCount;
      var nodeSampleCount;
      if (metric) {
        rootNodeSampleCount = rootNode.metricCounts[metric] || 0;
        nodeSampleCount = node.metricCounts[metric] || 0;
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
      if (node.ellipsed) {
        // each indent is 1/3em, so adding extra .333em to indent thread state
        ret += '<span class="inline-block" style="width: 4.666em; margin-left: ' + ((level / 3)) + 'em;">';
        ret += '</span>...<br>';
      }
      if (node.childNodes) {
        var childNodes = node.childNodes;
        // order child nodes by sampleCount (descending)
        childNodes.sort(function (a, b) {
          if (metric) {
            return (b.metricCounts[metric] || 0) - (a.metricCounts[metric] || 0);
          }
          return b.sampleCount - a.sampleCount;
        });
        var i;
        for (i = 0; i < childNodes.length; i++) {
          ret += curr(childNodes[i], level, metric);
        }
      }
      return ret;
    }

    var $selector = $(selector);
    // first time only, process merged stack tree and populate dropdown
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
    // build initial merged stack tree
    var interestingHtml = curr(interestingRootNode, 0);
    if (uninterestingHtml) {
      $selector.find('.profile-common .expanded-content').html(uninterestingHtml);
      $selector.find('.profile-common').removeClass('hide');
    }
    $selector.find('.profile-interesting').html(interestingHtml);

    var mergedCounts = calculateMetricCounts(rootNode);
    if (!$.isEmptyObject(mergedCounts)) {
      // build tree
      var tree = { name: '', childNodes: {} };
      $.each(rootNode.metricCounts, function (metric) {
        // only really need to look at leafs (' / other') to hit all nodes
        if (metric.match(/ \/ other$/)) {
          var parts = metric.split(' / ');
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
          return rootNode.metricCounts[b.name] - rootNode.metricCounts[a.name];
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
      // remove the root '' since all nodes are already under the single root trace metric
      orderedNodes.splice(0, 1);
      // build filter dropdown
      var $profileFilter = $selector.find('.profile-filter');
      $profileFilter.removeClass('hide');
      $.each(orderedNodes, function (i, node) {
        $profileFilter.append($('<option />').val(node.name)
            .text(node.name + ' (' + rootNode.metricCounts[node.name] + ')'));
      });
      $profileFilter.change(function () {
        // update merged stack tree based on filter
        var interestingHtml = curr(interestingRootNode, 0, $(this).val());
        $selector.find('.profile-interesting').html(interestingHtml);
      });
    }
  }

  function calculateMetricCounts(node) {
    var mergedCounts = {};
    if (node.leafThreadState && node.metricNames.length) {
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
      var processMetric = function (metric, count) {
        if (mergedCounts[metric]) {
          mergedCounts[metric] += count;
        } else {
          mergedCounts[metric] = count;
        }
      };
      for (i = 0; i < childNodes.length; i++) {
        var metricCounts = calculateMetricCounts(childNodes[i]);
        $.each(metricCounts, processMetric);
      }
    }
    node.metricCounts = mergedCounts;
    return mergedCounts;
  }

  return {
    renderTrace: function (trace, $selector) {
      var html = JST.trace(trace);
      $selector.html(html);
      $selector.addClass('trace-parent');
      $selector.data('traceId', trace.id);
    },
    renderTraceFromExport: function (trace, $selector, spans, profile, outlierProfile) {
      $selector.data('spans', spans);
      $selector.data('profile', profile);
      $selector.data('outlierProfile', outlierProfile);
      this.renderTrace(trace, $selector);
    },
    renderTransaction: function (transaction, $selector, queryString) {
      var html = JST.transaction(transaction);
      $selector.html(html);
      $selector.addClass('transaction-parent');
      $selector.data('queryString', queryString);
    },
    renderTransactionFromExport: function (transaction, $selector, profile) {
      $selector.data('profile', profile);
      this.renderTransaction(transaction, $selector);
    }
  };
})();
