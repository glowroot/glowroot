/*
 * Copyright 2012 the original author or authors.
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
var traceSummaryTemplateText = ''
+ '{{#if error}}'
+ '  <b>ERROR</b><br>'
+ '{{/if}}'
+ '{{#if active}}'
+ '  <b>ACTIVE {{#if stuck}}/ STUCK{{/if}}</b><br>'
+ '{{^}}'
+ '  {{#if stuck}}<b>{{#if completed}}UN{{/if}}STUCK</b><br>{{/if}}'
+ '{{/if}}'
+ '{{#if background}}'
+ '  <b>background</b><br>'
+ '{{/if}}'
+ '<div class="second-line-indent">'
+ '  {{description}}'
+ '  {{#ifShowExport}}'
+ '    <span style="width: 1em; display: inline-block"></span>'
+ '    <a href="trace/export/{{id}}">export</a>'
+ '  {{/ifShowExport}}'
+ '</div>'
+ 'start: {{date start}}<br>'
+ 'duration: {{nanosToMillis duration}}{{#if active}}..{{/if}} milliseconds<br>'
+ '{{#each attributes}}'
+ '  <div class="second-line-indent">{{name}}: {{value}}</div>'
+ '{{/each}}'
+ '{{#if userId}}<div class="second-line-indent">user ID: {{userId}}</div>{{/if}}'
+ '{{#if error}}<div class="second-line-indent"><b>error: {{error.text}}</b></div>{{/if}}'
+ 'breakdown (in milliseconds):<br>'
+ '<table class="metrics-table" style="margin-left: 1em; border-spacing:0">'
+ '  <thead>'
+ '    <tr>'
+ '      <td></td>'
+ '      <td>total</td>'
+ '      <td>min</td>'
+ '      <td>max</td>'
+ '      <td>count</td>'
+ '    </tr>'
+ '  </thead>'
+ '  <tbody>'
+ '    {{#each metrics}}'
+ '      <tr>'
+ '        <td style="text-align: left">{{name}}</td>'
+ '        <td>{{nanosToMillis total}}{{#if active}}..{{/if}}</td>'
+ '        <td>{{nanosToMillis min}}{{#if minActive}}..{{/if}}</td>'
+ '        <td>{{nanosToMillis max}}{{#if maxActive}}..{{/if}}</td>'
+ '        <td>{{count}}</td>'
+ '      </tr>'
+ '    {{/each}}'
+ '  </tbody>'
+ '</table>'
var traceDetailTemplateText = ''
+ '{{#if spans}}'
+ '  {{#ifRolledOver spans}}'
+ '    <div>spans <i>rolled over</i></div>'
+ '  {{^}}'
+ '    <a href="#" onclick="toggleSpans(); return false">spans</a> ({{spans.length}})<br>'
+ '    <div id="sps"></div>'
+ '  {{/ifRolledOver}}'
+ '{{/if}}'
+ '{{#if coarseMergedStackTree}}'
+ '  {{#ifRolledOver coarseMergedStackTree}}'
+ '    <div>coarse merged stack tree <i>rolled over</i></div>'
+ '  {{^}}'
+ '    <a href="#" onclick="toggleCoarseMergedStackTree(); return false">'
+ '        coarse merged stack tree</a> ({{coarseMergedStackTree.sampleCount}})<br>'
+ '    <div id="mstCoarseOuter" style="display: none; white-space: nowrap">'
+ '      <select class="input-large" id="mstCoarseFilter" onchange="this.blur()"'
+ '          style="margin-left: 1em; margin-bottom: 0em"></select><br>'
+ '      <div id="mstCoarseUninterestingOuter" style="display: none">'
+ '        <a id="mstCoarseUninterestingLink" href="#" style="margin-left: 1em"'
+ '            onclick="toggleUninteresting(\'Coarse\'); return false">expand common base</a>'
+ '        <div id="mstCoarseUninteresting" style="display: none"></div>'
+ '      </div>'
+ '      <div id="mstCoarseInteresting"></div>'
+ '    </div>'
+ '  {{/ifRolledOver}}'
+ '{{/if}}'
+ '{{#if fineMergedStackTree}}'
+ '  {{#ifRolledOver fineMergedStackTree}}'
+ '    <div>fine merged stack tree <i>rolled over</i><div>'
+ '  {{^}}'
+ '    <a href="#" onclick="toggleFineMergedStackTree(); return false">'
+ '        fine merged stack tree</a> ({{fineMergedStackTree.sampleCount}})<br>'
+ '    <div id="mstFineOuter" style="display: none; white-space: nowrap">'
+ '      <select class="input-large" id="mstFineFilter" onchange="this.blur()"'
+ '          style="margin-left: 1em; margin-bottom: 0em"></select><br>'
+ '      <div id="mstFineUninterestingOuter" style="display: none">'
+ '        <a id="mstFineUninterestingLink" href="#" style="margin-left: 1em"'
+ '            onclick="toggleUninteresting(\'Fine\'); return false">expand common base</a>'
+ '        <div id="mstFineUninteresting" style="display: none"></div>'
+ '      </div>'
+ '      <div id="mstFineInteresting"></div>'
+ '    </div>'
+ '  {{/ifRolledOver}}'
+ '{{/if}}'
var spansTemplateText = ''
+ '<div style="float: left; margin-left: 1em; width: 3em; text-align: right">'
+ '    +{{nanosToMillis offset}}'
+ '  </div>'
+ '  <div style="margin-left: {{margin nestingLevel}}em">'
+ '    <div style="width: 2em; float: left; text-align: right">'
+ '      {{nanosToMillis duration}}{{#if active}}..{{/if}}'
+ '    </div>'
+ '    <div style="margin-left: 4em">'
+ '      {{#ifLongDescription message.text}}'
+ '        <div class="sp spexpandable">'
+ '          {{#first80 message.text}}{{/first80}}'
+ '          <span class="spmiddle">...</span>'
+ '          <span class="spmiddle spmiddlex" style="display: none">'
+ '            {{#middle message.text}}{{/middle}}'
+ '          </span>'
+ '          {{#last80 message.text}}{{/last80}}'
+ '        </div>'
+ '      {{^}}'
+ '        <div class="sp">'
+ '          {{message.text}}'
+ '        </div>'
+ '      {{/ifLongDescription}}'
+ '      <br>'
+ '      {{#if message.detail}}'
+ '        <a style="margin-left: 1em" href="#"'
+ '            onclick="$(\'#cm{{index}}\').toggle(); return false">'
+ '          detail'
+ '        </a>'
+ '        <br>'
+ '        <div id="cm{{index}}" style="display: none">'
+ '          <div style="margin-left: 1em">'
+ '            {{#messageDetailHtml message.detail}}{{/messageDetailHtml}}'
+ '          </div>'
+ '        </div>'
+ '      {{/if}}'
+ '      {{#if error}}'
+ '        <b>{{error.text}}</b>'
+ '        <br>'
+ '        {{#if error.detail}}'
+ '          <div style="margin-left: 1em">'
+ '            {{#messageDetailHtml error.detail}}{{/messageDetailHtml}}'
+ '          </div>'
+ '        {{/if}}'
+ '        {{#if error.exceptionBlockId}}'
+ '          <div style="margin-left: 1em">'
+ '            <a href="#" onclick="viewException(\'{{error.exceptionBlockId}}\'); return false">'
+ '              exception'
+ '            </a>'
+ '          </div>'
+ '        {{/if}}'
+ '      {{/if}}'
+ '      {{#if stackTraceBlockId}}'
+ '        <a href="#" onclick="viewStackTrace(\'{{stackTraceBlockId}}\'); return false">'
+ '          span stack trace'
+ '        </a>'
+ '        <br>'
+ '      {{/if}}'
+ '    </div>'
+ '  </div>'
Handlebars.registerHelper('date', function(timestamp) {
  return moment(timestamp).format('L h:mm:ss A (Z)')
})
Handlebars.registerHelper('nanosToMillis', function(nanos) {
  return (nanos / 1000000).toFixed(1)
})
Handlebars.registerHelper('messageDetailHtml', function(detail) {
  function messageDetailHtml(detail) {
    var ret = ''
    $.each(detail, function(propName, propVal) {
      ret += propName + ':'
      if (typeof propVal == 'object') {
        ret += '<br>'
        ret += '<div style="margin-left: 1em">'
        ret += messageDetailHtml(propVal)
        ret += '</div>'
      } else {
        ret += ' ' + propVal + '<br>'
      }
    })
    return ret
  }
  return messageDetailHtml(detail)
})
Handlebars.registerHelper('ifLongDescription', function(description, options) {
  if (description.length > 160) {
    return options.fn(this)
  } else {
    return options.inverse(this)
  }
})
Handlebars.registerHelper('ifRolledOver', function(value, options) {
  if (value == 'rolled over') {
    return options.fn(this)
  } else {
    return options.inverse(this)
  }
})
Handlebars.registerHelper('margin', function(nestingLevel) {
  return 5 + nestingLevel
})
Handlebars.registerHelper('first80', function(description) {
  return description.slice(0, 80)
})
Handlebars.registerHelper('last80', function(description) {
  if (description.length <= 80) {
    return ""
  } else {
    var n = Math.min(description.length - 80, 80)
    return description.slice(-n)
  }
})
Handlebars.registerHelper('middle', function(description) {
  return description.slice(80, -80);
})
Handlebars.registerHelper('ifShowExport', function(options) {
  if (typeof exportPage == 'undefined') {
    return options.fn(this)
  } else {
    return options.inverse(this)
  }
})
var summaryTrace
var detailTrace
var traceSummaryTemplate
var traceDetailTemplate
var spansTemplate
$(document).ready(function() {
  traceSummaryTemplate = Handlebars.compile(traceSummaryTemplateText)
  traceDetailTemplate = Handlebars.compile(traceDetailTemplateText)
  spansTemplate = Handlebars.compile(spansTemplateText)
})
function toggleSpans() {
  if ($('#sps').html() && $('#sps').is(':visible')) {
    $('#sps').hide()
  } else {
    $('#sps').show()
    if (! $('#sps').html()) {
      renderNext(detailTrace.spans, 0)
    }
  }
}
// sort of ok for clickSpanTimer to be global since it is temporary (cleared after 250 milliseconds)
var clickSpanTimer
// sort of ok for pageX and pageY to be global since they are just temporary bridge between
// mousedown event and subsequent click event
var mousedownSpanPageX, mousedownSpanPageY
function mousedownSpan(div, e) {
  mousedownSpanPageX = e.mousedownSpanPageX
  mousedownSpanPageY = e.mousedownSpanPageY
}
function clickSpan(div, e) {
  if (Math.abs(e.pageX - mousedownSpanPageX) > 5 || Math.abs(e.pageY - mousedownSpanPageY) > 5) {
    // not a simple single click, probably highlighting text
    return
  }
  if (clickSpanTimer) {
    // double click, probably highlighting text
    clearTimeout(clickSpanTimer)
    clickSpanTimer = undefined
    return
  }
  if ($(div).find('.spmiddlex').is(':visible')) {
    // delay on hiding in order to not contract on double click text highlighting 
    clickSpanTimer = setTimeout(function() {
      $(div).find('.spmiddle').toggle()
      clickSpanTimer = undefined
    }, 250)
  } else {
    // no delay on expanding because it makes it feel sluggish
    // (at the expense of double click text highlighting also expanding the span)
    $(div).find('.spmiddle').toggle()
    // but still create clickSpanTimer to prevent double click from expanding and then contracting
    clickSpanTimer = setTimeout(function() { clickSpanTimer = undefined }, 500)
    return
  }
}
function renderNext(spans, start) {
  // large numbers of spans (e.g. 20,000) render much faster when grouped into sub-divs
  var html = '<div id="block' + start + '">'
  for (var i = start; i < Math.min(start + 100, spans.length); i++) {
    html += spansTemplate(spans[i])
  }
  html += '</div>'
  $('#sps').append(html)
  $('#block' + start + ' .spexpandable').mousedown(function(e) {mousedownSpan($(this), e)})
  $('#block' + start + ' .spexpandable').click(function(e) {clickSpan($(this), e)})
  if (start + 100 < spans.length) {
    setTimeout(function() { renderNext(spans, start + 100) }, 10)
  }
}
function toggleUninteresting(granularity) {
  if ($('#mst' + granularity + 'Uninteresting').is(':visible')) {
    $('#mst' + granularity + 'UninterestingLink').html('expand common base')
    $('#mst' + granularity + 'Uninteresting').hide()
  } else {
    $('#mst' + granularity + 'UninterestingLink').html('shrink common base')
    $('#mst' + granularity + 'Uninteresting').show()
  }
}
function toggleCoarseMergedStackTree() {
  toggleMergedStackTree(detailTrace.coarseMergedStackTree, 'Coarse')
}
function toggleFineMergedStackTree() {
  toggleMergedStackTree(detailTrace.fineMergedStackTree, 'Fine')
}
function toggleMergedStackTree(rootNode, granularity) {
  function curr(node, level, metricName) {
    var rootNodeSampleCount
    var nodeSampleCount
    if (metricName) {
      rootNodeSampleCount = rootNode.metricNameCounts[metricName] || 0
      nodeSampleCount = node.metricNameCounts[metricName] || 0
      if (nodeSampleCount == 0) {
        return ''
      }
    } else {
      rootNodeSampleCount = rootNode.sampleCount
      nodeSampleCount = node.sampleCount
    }
    if (nodeSampleCount < rootNodeSampleCount) {
      level++
    }
    var ret = '<span style="display: inline-block; width: 4em; margin-left: ' + ((level / 3) + 1)
        + 'em">'
    var samplePercentage = (nodeSampleCount / rootNodeSampleCount) * 100
    ret += samplePercentage.toFixed(1)
    ret += '%</span>'
    ret += node.stackTraceElement + '<br>'
    if (node.leafThreadState) {
      ret += '<span style="display: inline-block; width: 4em; margin-left: ' + ((level / 3) + 1)
          + 'em">'
      ret += samplePercentage.toFixed(1)
      ret += '%</span> '
      ret += node.leafThreadState
      ret += '<br>'
    }
    if (node.childNodes) {
      var childNodes = node.childNodes
      // order child nodes by sampleCount (descending)
      childNodes.sort(function(a, b) {
        if (metricName) {
          return (b.metricNameCounts[metricName] || 0) - (a.metricNameCounts[metricName] || 0)
        } else {
          return b.sampleCount - a.sampleCount
        }
      })
      for (var i = 0; i < childNodes.length; i++) {
        ret += curr(childNodes[i], level, metricName)
      }
    }
    return ret
  }
  if ($('#mst' + granularity + 'Outer').is(':visible')) {
    $('#mst' + granularity + 'Outer').hide()
  } else {
    if (! $('#mst' + granularity).html()) {
      // first time only, process merged stack tree and populate dropdown
      processMergedStackTree(rootNode)
      // build tree
      var tree = { name : '', childNodes : {} }
      $.each(rootNode.metricNameCounts, function(metricName, count) {
        // only really need to look at leafs (' / other') to hit all nodes
        if (metricName.match(/ \/ other$/)) {
          var parts = metricName.split(' / ')
          var node = tree
          var partialName = ''
          $.each(parts, function(i, part) {
            if (i > 0) {
              partialName += ' / '
            }
            partialName += part
            if (!node.childNodes[part]) {
              node.childNodes[part] = { name : partialName, childNodes : {} }
            }
            node = node.childNodes[part]
          })
        }
      })
      function nodesDepthFirst(node) {
        var all = [ node ]
        // order by count desc
        var childNodes = []
        $.each(node.childNodes, function(name, childNode) {
          childNodes.push(childNode)
        })
        childNodes.sort(function(a, b) {
          return rootNode.metricNameCounts[b.name] - rootNode.metricNameCounts[a.name]
        })
        if (childNodes.length == 1 && childNodes[0].name.match(/ \/ other$/)) {
          // skip if single 'other' node (in which case it will be represented by current node)
          return all
        }
        $.each(childNodes, function(i, childNode) {
          all = all.concat(nodesDepthFirst(childNode))
        })
        return all
      }
      var orderedNodes = nodesDepthFirst(tree)
      // remove the root '' since all nodes are already under the single root span metric
      orderedNodes.splice(0, 1)
      // build filter dropdown
      $('#mst' + granularity + 'Filter').html('')
      $.each(orderedNodes, function(i, node) {
        $('#mst' + granularity + 'Filter').append($('<option />').val(node.name)
            .text(node.name + ' (' + rootNode.metricNameCounts[node.name] + ')'))
      })
      var i = 0
      var interestingRootNode = rootNode
      var uninterestingHtml = ''
      while (true) {
        uninterestingHtml += '<span style="display: inline-block; width: 4em; margin-left: 1em">'
            + '100.0%</span>' + interestingRootNode.stackTraceElement + '<br>'
        if (! interestingRootNode.childNodes || interestingRootNode.childNodes.length != 1) {
          break
        }
        var childNode = interestingRootNode.childNodes[0]
        if (childNode.leafThreadState) {
          break
        }
        interestingRootNode = childNode
        i++
      }
      $('#mst' + granularity + 'Filter').change(function() {
        // update merged stack tree based on filter
        var interestingHtml = curr(interestingRootNode, 0, $(this).val())
        $('#mst' + granularity + 'UninterestingOuter').show()
        $('#mst' + granularity + 'Uninteresting').html(uninterestingHtml)
        $('#mst' + granularity + 'Interesting').html(interestingHtml)
      })
      // build initial merged stack tree
      var interestingHtml = curr(interestingRootNode, 0)
      $('#mst' + granularity + 'UninterestingOuter').show()
      $('#mst' + granularity + 'Uninteresting').html(uninterestingHtml)
      $('#mst' + granularity + 'Interesting').html(interestingHtml)
    }
    $('#mst' + granularity + 'Outer').show()
  }
}
// TODO move inside toggleMergedStackTree enclosure
function processMergedStackTree(rootNode) {
  function calculateMetricNameCounts(node) {
    var mergedCounts = {}
    if (node.leafThreadState) {
      var partial = ''
      $.each(node.metricNames, function(i, metricName) {
        if (i > 0) {
          partial += ' / '
        }
        partial += metricName
        mergedCounts[partial] = node.sampleCount
      })
      mergedCounts[partial + ' / other'] = node.sampleCount
    }
    if (node.childNodes) {
      var childNodes = node.childNodes
      for (var i = 0; i < childNodes.length; i++) {
        var metricNameCounts = calculateMetricNameCounts(childNodes[i])
        $.each(metricNameCounts, function(metricName, count) {
          if (mergedCounts[metricName]) {
            mergedCounts[metricName] += count
          } else {
            mergedCounts[metricName] = count
          }
        })
      }
    }
    node.metricNameCounts = mergedCounts
    return mergedCounts
  }
  calculateMetricNameCounts(rootNode)
}
var newline = navigator.platform.indexOf('Win') == -1 ? "\n" : "\r\n"
function viewStackTrace(stackTraceBlockId) {
  $.getJSON('block/' + stackTraceBlockId, function(stackTrace) {
    var html = ''
    var clippyText = ''
    for (var i = 0; i < stackTrace.length; i++) {
      html += stackTrace[i] + '<br>'
      clippyText += stackTrace[i] + newline
    }
    displayModal('Stack Trace', html, clippyText)
  })
}
function viewException(exceptionBlockId) {
  $.getJSON('block/' + exceptionBlockId, function(exception) {
    var html = '<b>'
    var clippyText = ''
    while (exception) {
      html += exception.display + '</b><br>'
      clippyText += exception.display + newline
      for (var i = 0; i < exception.stackTrace.length; i++) {
        html += '<span style="width: 4em; display: inline-block"></span>at '
          + exception.stackTrace[i] + '<br>'
        clippyText += '    at ' + exception.stackTrace[i] + newline
      }
      if (exception.framesInCommon) {
        html += '... ' + exception.framesInCommon + ' more<br>'
        clippyText += '... ' + exception.framesInCommon + ' more' + newline
      }
      exception = exception.cause
      if (exception) {
        html += "<b>Caused by: "
        clippyText += "Caused by: "
      }
    }
    displayModal('Exception', html, clippyText)
  })
}
function displayModal(title, html, clippyText) {
  $('#modalTitle').text(title)
  $('#modalBody').html(html)
  // clippy swf must be re-initialized each time with the (updated) text, but it cannot be
  // re-initialized on the same element so a disposable inner element is created each time
  $('#modalClippy').html('<span id="modalClippyDisposable"></span>')
  $('#modalClippyDisposable').data('text', clippyText)
  $('#modalClippyDisposable').clippy({
    clippy_path: 'libs/clippy-jquery/0.1-nightly-20120701/clippy.swf'
  })
  $('#modal').modal('show')
}
