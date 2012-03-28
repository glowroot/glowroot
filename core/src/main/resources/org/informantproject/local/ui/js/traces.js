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
+ '{{#if username}}<div class="second-line-indent">username: {{username}}</div>{{/if}}'
+ '{{#each attributes}}'
+ '  <div class="second-line-indent">{{name}}: {{value}}</div>'
+ '{{/each}}'
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
+ '        <td>{{nanosToMillis total}}</td>'
+ '        <td>{{nanosToMillis min}}</td>'
+ '        <td>{{nanosToMillis max}}</td>'
+ '        <td>{{count}}</td>'
+ '      </tr>'
+ '    {{/each}}'
+ '  </tbody>'
+ '</table>'
var traceDetailTemplateText = ''
+ '{{#if spans}}'
+ '  <a href="#" onclick="toggleSpan(\'{{id}}\'); return false">spans</a> ({{spans.length}})<br>'
+ '  <div id="sp_{{id}}" style="display: none"></div>'
+ '{{/if}}'
+ '{{#if mergedStackTree.sampleCount}}'
+ '  <a href="#" onclick="toggleMergedStackTree(\'{{id}}\'); return false">merged stack tree</a>'
+ '  ({{mergedStackTree.sampleCount}})<br>'
+ '  <div id="mst_outer_{{id}}" style="display: none; white-space: nowrap">'
+ '    <select style="margin-left: 1em; margin-bottom: 0em" class="input-large"'
+ '        id="mst_filter_{{id}}"></select>'
+ '    <br>'
+ '    <div id="mst_uninteresting_outer_{{id}}" style="display: none">'
+ '      <a style="margin-left: 1em" href="#" id="mst_uninteresting_link_{{id}}"'
+ '          onclick="toggleUninteresting(\'{{id}}\'); return false">'
+ '        expand common base'
+ '      </a>'
+ '      <div id="mst_uninteresting_{{id}}" style="display: none"></div>'
+ '    </div>'
+ '    <div id="mst_interesting_{{id}}"></div>'
+ '  </div>'
+ '{{/if}}'
var spansTemplateText = ''
+ '{{#spans}}'
+ '<div style="float: left; margin-left: 1em; width: 3em; text-align: right">'
+ '    +{{nanosToMillis offset}}'
+ '  </div>'
+ '  <div style="margin-left: {{margin level}}em">'
+ '    <div style="width: 3em; float: left; text-align: right">'
+ '      {{nanosToMillis duration}}{{#if active}}..{{/if}}&nbsp;'
+ '    </div>'
+ '    <div style="margin-left: 4em">'
+ '      {{#ifLongDescription description}}'
+ '        {{! have to use ../.. to get to the parent context from inside of a block'
+ '            helper, see https://github.com/wycats/handlebars.js/issues/196 }}'
+ '        <span class="sp_{{../../id}}_{{index}}">'
+ '          <a href="#" onclick="$(\'.sp_{{../../id}}_{{index}}\').toggle(); return false"'
+ '              style="color: #333">'
+ '            {{#short description}}{{/short}}'
+ '          </a>'
+ '        </span>'
+ '        <span class="sp_{{../../id}}_{{index}}" style="display: none">'
+ '          <a href="#" onclick="$(\'.sp_{{../../id}}_{{index}}\').toggle(); return false">'
+ '            {{description}}'
+ '          </a>'
+ '        </span>'
+ '      {{^}}'
+ '        {{description}}'
+ '      {{/ifLongDescription}}'
+ '      {{#if contextMap}}'
+ '        <br>'
+ '        <a style="margin-left: 1em" href="#"'
+ '            onclick="$(\'#cm_{{../../id}}_{{index}}\').toggle(); return false">'
+ '          context map'
+ '        </a>'
+ '        <br>'
+ '        <div id="cm_{{../../id}}_{{index}}" style="display: none">'
+ '          <div style="margin-left: 1em">{{#contextMapHtml contextMap}}{{/contextMapHtml}}</div>'
+ '        </div>'
+ '      {{/if}}'
+ '      {{#if stackTraceHash}}'
+ '        <a href="#" onclick="viewStackTrace(\'{{stackTraceHash}}\'); return false">'
+ '          view stack trace'
+ '        </a>'
+ '      {{/if}}'
+ '    </div>'
+ '  </div>'
+ '{{/spans}}'
Handlebars.registerHelper('date', function(timestamp) {
  return new Date(timestamp).format('m/d/yy h:MM:ss.l TT (Z)')
})
Handlebars.registerHelper('nanosToMillis', function(nanos) {
  return (nanos / 1000000).toFixed(1)
})
Handlebars.registerHelper('contextMapHtml', function(contextMap) {
  function contextMapHtml(contextMap) {
    var ret = ''
    $.each(contextMap, function(propName, propVal) {
      ret += propName + ':'
      if (typeof propVal == 'object') {
        ret += '<br>'
        ret += '<div style="margin-left: 1em">'
        ret += contextMapHtml(propVal)
        ret += '</div>'
      } else {
        ret += ' ' + propVal + '<br>'
      }
    })
    return ret
  }
  return contextMapHtml(contextMap)
})
Handlebars.registerHelper('ifLongDescription', function(description, options) {
  if (description.length > 160) {
    return options.fn(this)
  } else {
    return options.inverse(this)
  }
})
Handlebars.registerHelper('margin', function(level) {
  return 5 + level
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
function toggleSpan(id) {
  if ($('#sp_' + id).is(':visible')) {
    $('#sp_' + id).hide()
  } else {
    if (! $('#sp_' + id).html()) {
      var html = spansTemplate(detailTrace)
      $(html).appendTo('#sp_' + id)
    }
    $('#sp_' + id).show()
  }
}
function toggleUninteresting(id) {
  if ($('#mst_uninteresting_' + id).is(':visible')) {
    $('#mst_uninteresting_link_' + id).html('expand common base')
    $('#mst_uninteresting_' + id).hide()
  } else {
    $('#mst_uninteresting_link_' + id).html('shrink common base')
    $('#mst_uninteresting_' + id).show()
  }
}
function toggleMergedStackTree(id) {
  var rootNode = detailTrace.mergedStackTree
  function curr(node, level, spanName) {
    var rootNodeSampleCount
    var nodeSampleCount
    if (spanName) {
      rootNodeSampleCount = rootNode.spanNameCounts[spanName] || 0
      nodeSampleCount = node.spanNameCounts[spanName] || 0
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
      ret += '%</span>'
      ret += '&nbsp;' + node.leafThreadState
      ret += '<br>'
    }
    if (node.childNodes) {
      var childNodes = node.childNodes
      // order child nodes by sampleCount (descending)
      childNodes.sort(function(a, b) {
        if (spanName) {
          return (b.spanNameCounts[spanName] || 0) - (a.spanNameCounts[spanName] || 0)
        } else {
          return b.sampleCount - a.sampleCount
        }
      })
      for (var i = 0; i < childNodes.length; i++) {
        ret += curr(childNodes[i], level, spanName)
      }
    }
    return ret
  }
  if ($('#mst_outer_' + id).is(':visible')) {
    $('#mst_outer_' + id).hide()
  } else {
    if (! $('#mst_' + id).html()) {
      // first time only, process merged stack tree and populate dropdown
      processMergedStackTree()
      // build tree
      var tree = { name : '', childNodes : {} }
      $.each(rootNode.spanNameCounts, function(spanName, count) {
        // only really need to look at leafs (' / other') to hit all nodes
        if (spanName.match(/ \/ other$/)) {
          var parts = spanName.split(' / ')
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
          return rootNode.spanNameCounts[b.name] - rootNode.spanNameCounts[a.name]
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
      $('#mst_filter_' + id).html('')
      $.each(orderedNodes, function(i, node) {
        $('#mst_filter_' + id).append($('<option />').val(node.name).text(node.name + ' ('
            + rootNode.spanNameCounts[node.name] + ')'))
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
      $('#mst_filter_' + id).change(function() {
        // update merged stack tree based on filter
        var interestingHtml = curr(interestingRootNode, 0, $(this).val())
        $('#mst_uninteresting_outer_' + id).show()
        $('#mst_uninteresting_' + id).html(uninterestingHtml)
        $('#mst_interesting_' + id).html(interestingHtml)
      })
      // build initial merged stack tree
      var interestingHtml = curr(interestingRootNode, 0)
      $('#mst_uninteresting_outer_' + id).show()
      $('#mst_uninteresting_' + id).html(uninterestingHtml)
      $('#mst_interesting_' + id).html(interestingHtml)
    }
    $('#mst_outer_' + id).show()
  }
}
function processMergedStackTree() {
  var rootNode = detailTrace.mergedStackTree
  function calculateSpanNameCounts(node) {
    var mergedCounts = {}
    if (node.leafThreadState) {
      var partial = ''
      $.each(node.spanNames, function(i, spanName) {
        if (i > 0) {
          partial += ' / '
        }
        partial += spanName
        mergedCounts[partial] = node.sampleCount
      })
      mergedCounts[partial + ' / other'] = node.sampleCount
    }
    if (node.childNodes) {
      var childNodes = node.childNodes
      for (var i = 0; i < childNodes.length; i++) {
        var spanNameCounts = calculateSpanNameCounts(childNodes[i])
        $.each(spanNameCounts, function(spanName, count) {
          if (mergedCounts[spanName]) {
            mergedCounts[spanName] += count
          } else {
            mergedCounts[spanName] = count
          }
        })
      }
    }
    node.spanNameCounts = mergedCounts
    return mergedCounts
  }
  calculateSpanNameCounts(rootNode)
}
function viewStackTrace(stackTraceHash) {
  $.getJSON('/stacktrace/' + stackTraceHash, function(stackTraceElements) {
    $('#stacktrace').html('')
    for (var i = 0; i < stackTraceElements.length; i++) {
      $('#stacktrace').append(stackTraceElements[i] + '<br>')
    }
    $('#stacktracemodal').modal('show')
  })
}
