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
+ '<div class="second-line-indent">'
+ '  {{description}}'
+ '  {{#if showExport}}'
+ '    <span style="width: 1em; display: inline-block"></span>'
+ '    <a href="trace/export/{{id}}">export</a>'
+ '  {{/if}}'
+ '</div>'
+ 'start: {{date start}}<br>'
+ 'duration: {{nanosToMillis duration}}{{#if active}}..{{/if}} milliseconds<br>'
+ '{{#each attributes}}'
+ '  <div class="second-line-indent">{{name}}: {{value}}</div>'
+ '{{/each}}'
+ '{{#if username}}<div class="second-line-indent">username: {{username}}</div>{{/if}}'
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
+ '    <a href="#" onclick="toggleSpan(); return false">spans</a> ({{spans.length}})<br>'
+ '    <div id="sp"></div>'
+ '  {{/ifRolledOver}}'
+ '{{/if}}'
+ '{{#if coarseMergedStackTree}}'
+ '  {{#ifRolledOver coarseMergedStackTree}}'
+ '    <div>coarse merged stack tree <i>rolled over</i></div>'
+ '  {{^}}'
+ '    <a href="#" onclick="toggleCoarseMergedStackTree(); return false">'
+ '        coarse merged stack tree</a> ({{coarseMergedStackTree.sampleCount}})<br>'
+ '    <div id="mst_coarse_outer" style="display: none; white-space: nowrap">'
+ '      <select style="margin-left: 1em; margin-bottom: 0em" class="input-large"'
+ '          id="mst_coarse_filter"></select><br>'
+ '      <div id="mst_coarse_uninteresting_outer" style="display: none">'
+ '        <a style="margin-left: 1em" href="#" id="mst_coarse_uninteresting_link"'
+ '            onclick="toggleUninteresting(\'coarse\'); return false">expand common base</a>'
+ '        <div id="mst_coarse_uninteresting" style="display: none"></div>'
+ '      </div>'
+ '      <div id="mst_coarse_interesting"></div>'
+ '    </div>'
+ '  {{/ifRolledOver}}'
+ '{{/if}}'
+ '{{#if fineMergedStackTree}}'
+ '  {{#ifRolledOver fineMergedStackTree}}'
+ '    <div>fine merged stack tree <i>rolled over</i><div>'
+ '  {{^}}'
+ '    <a href="#" onclick="toggleFineMergedStackTree(); return false">'
+ '        fine merged stack tree</a> ({{fineMergedStackTree.sampleCount}})<br>'
+ '    <div id="mst_fine_outer" style="display: none; white-space: nowrap">'
+ '      <select style="margin-left: 1em; margin-bottom: 0em" class="input-large"'
+ '          id="mst_fine_filter"></select><br>'
+ '      <div id="mst_fine_uninteresting_outer" style="display: none">'
+ '        <a style="margin-left: 1em" href="#" id="mst_fine_uninteresting_link"'
+ '            onclick="toggleUninteresting(\'fine\'); return false">expand common base</a>'
+ '        <div id="mst_fine_uninteresting" style="display: none"></div>'
+ '      </div>'
+ '      <div id="mst_fine_interesting"></div>'
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
+ '        <span class="sp_{{index}}">'
+ '          <a href="#" onclick="$(\'.sp_{{index}}\').toggle(); return false"'
+ '              style="color: #333">'
+ '            {{#short message.text}}{{/short}}'
+ '          </a>'
+ '        </span>'
+ '        <span class="sp_{{index}}" style="display: none">'
+ '          <a href="#" onclick="$(\'.sp_{{index}}\').toggle(); return false">'
+ '            {{message.text}}'
+ '          </a>'
+ '        </span>'
+ '      {{^}}'
+ '        {{message.text}}'
+ '      {{/ifLongDescription}}'
+ '      <br>'
+ '      {{#if message.detail}}'
+ '        <a style="margin-left: 1em" href="#"'
+ '            onclick="$(\'#cm_{{index}}\').toggle(); return false">'
+ '          detail'
+ '        </a>'
+ '        <br>'
+ '        <div id="cm_{{index}}" style="display: none">'
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
+ '        {{#if error.stackTraceHash}}'
+ '          <div style="margin-left: 1em">'
+ '            <a href="#" onclick="viewStackTrace(\'{{error.stackTraceHash}}\'); return false">'
+ '              error stack trace'
+ '            </a>'
+ '          </div>'
+ '        {{/if}}'
+ '      {{/if}}'
+ '      {{#if stackTraceHash}}'
+ '        <a href="#" onclick="viewStackTrace(\'{{stackTraceHash}}\'); return false">'
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
Handlebars.registerHelper('short', function(description) {
  if (description.length <= 160) {
    return description
  } else {
    return description.slice(0, 80)
      + " <span style='color: #b12930; font-weight: bold'>...</span> " + description.slice(-80)
  }
})
Handlebars.registerHelper('showExport', function() {
  return typeof exportPage == 'undefined'
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
function toggleSpan() {
  if ($('#sp').html() && $('#sp').is(':visible')) {
    $('#sp').hide()
  } else {
    $('#sp').show()
    if (! $('#sp').html()) {
      renderNext(detailTrace.spans, 0)
    }
  }
}
function renderNext(spans, start) {
  // large numbers of spans (e.g. 20,000) render much faster when grouped into sub-divs
  var html = '<div>'
  for (var i = start; i < Math.min(start + 100, spans.length); i++) {
    html += spansTemplate(spans[i])
  }
  html += '</div>'
  $('#sp').append(html)
  if (start + 100 < spans.length) {
    setTimeout(function() { renderNext(spans, start + 100) }, 10)
  }
}
function toggleUninteresting(granularity) {
  if ($('#mst_' + granularity + '_uninteresting').is(':visible')) {
    $('#mst_' + granularity + '_uninteresting_link').html('expand common base')
    $('#mst_' + granularity + '_uninteresting').hide()
  } else {
    $('#mst_' + granularity + '_uninteresting_link').html('shrink common base')
    $('#mst_' + granularity + '_uninteresting').show()
  }
}
function toggleCoarseMergedStackTree() {
  toggleMergedStackTree(detailTrace.coarseMergedStackTree, 'coarse')
}
function toggleFineMergedStackTree() {
  toggleMergedStackTree(detailTrace.fineMergedStackTree, 'fine')
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
  if ($('#mst_' + granularity + '_outer').is(':visible')) {
    $('#mst_' + granularity + '_outer').hide()
  } else {
    if (! $('#mst_' + granularity).html()) {
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
      $('#mst_' + granularity + '_filter').html('')
      $.each(orderedNodes, function(i, node) {
        $('#mst_' + granularity + '_filter').append($('<option />').val(node.name)
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
      $('#mst_' + granularity + '_filter').change(function() {
        // update merged stack tree based on filter
        var interestingHtml = curr(interestingRootNode, 0, $(this).val())
        $('#mst_' + granularity + '_uninteresting_outer').show()
        $('#mst_' + granularity + '_uninteresting').html(uninterestingHtml)
        $('#mst_' + granularity + '_interesting').html(interestingHtml)
      })
      // build initial merged stack tree
      var interestingHtml = curr(interestingRootNode, 0)
      $('#mst_' + granularity + '_uninteresting_outer').show()
      $('#mst_' + granularity + '_uninteresting').html(uninterestingHtml)
      $('#mst_' + granularity + '_interesting').html(interestingHtml)
    }
    $('#mst_' + granularity + '_outer').show()
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
function viewStackTrace(stackTraceHash) {
  $.getJSON('stacktrace/' + stackTraceHash, function(stackTraceElements) {
    $('#stacktrace').html('')
    for (var i = 0; i < stackTraceElements.length; i++) {
      $('#stacktrace').append(stackTraceElements[i] + '<br>')
    }
    $('#stacktracemodal').modal('show')
  })
}
