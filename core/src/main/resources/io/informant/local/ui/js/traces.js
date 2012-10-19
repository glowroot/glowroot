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
+ '  <strong>ERROR</strong><br>'
+ '{{/if}}'
+ '{{#if active}}'
+ '  <strong>ACTIVE {{#if stuck}}/ STUCK{{/if}}</strong><br>'
+ '{{^}}'
+ '  {{#if stuck}}<strong>{{#if completed}}UN{{/if}}STUCK</strong><br>{{/if}}'
+ '{{/if}}'
+ '{{#if background}}'
+ '  <strong>background</strong><br>'
+ '{{/if}}'
+ '<div class="second-line-indent">'
+ '  {{description}}'
+ '  {{#if showExport}}'
// unfortunately vertical padding isn't applied to inline elements, so "button" is a little small
// but it seems worth keeping this inline and it is a less used "button" anyways
+ '    <a class="indent1 pad1 rounded4" href="explorer/export/{{id}}">export</a>'
+ '  {{/if}}'
+ '</div>'
+ 'start: {{date start}}<br>'
+ 'duration: {{nanosToMillis duration}}{{#if active}}..{{/if}} milliseconds<br>'
+ '{{#each attributes}}'
+ '  <div class="second-line-indent">{{name}}: {{value}}</div>'
+ '{{/each}}'
+ '{{#if userId}}<div class="second-line-indent">user ID: {{userId}}</div>{{/if}}'
+ '{{#if error}}<div class="second-line-indent"><strong>error: {{error.text}}</strong></div>{{/if}}'
+ 'breakdown (in milliseconds):<br>'
+ '<table class="metrics-table indent1" style="border-spacing:0">'
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
+ '    <div>spans <em>rolled over</em></div>'
+ '  {{^}}'
+ '    <span tabindex="0" class="lightbtn clickable pad1" onclick="toggleSpans()">'
+ '      <span class="red">spans</span> ({{spans.length}})'
+ '    </span><br>'
+ '    <div id="sps"></div>'
+ '  {{/ifRolledOver}}'
+ '{{/if}}'
   // todo combine merged stack tree into template to consolidate code between coarse and fine mst
+ '{{#if coarseMergedStackTree}}'
+ '  {{#ifRolledOver coarseMergedStackTree}}'
+ '    <div>coarse-grained profile <em>rolled over</em></div>'
+ '  {{^}}'
+ '    <span tabindex="0" class="lightbtn clickable pad1" onclick="toggleCoarseMergedStackTree()">'
+ '      <span class="red">coarse-grained profile</span> ({{coarseMergedStackTree.sampleCount}})'
+ '    </span><br>'
+ '    <div class="nowrap indent1 hide" id="mstCoarseOuter">'
+ '      <select class="mst-filter input-large" onchange="this.blur()"'
             // taking 2px off of bottom because of extra 2px added to expandable element below
+ '          style="margin: 4px 4px 2px 4px"></select><br>'
+ '      <div class="overflow">'
+ '        <span tabindex="0" class="expandable mst-common clickable pad1 hide"'
                // the bottom margin is needed to trump .expandable class' margin-bottom: 4px since
                // no buffer is needed when this is expanded since nothing can be expanded directly
                // below, the 1px padding is to give space for the focus highlight
+ '             style="margin: 2px 1px 0 1px">'
+ '          <span class="unexpanded-content red">common base</span>'
+ '          <span class="expanded-content hide"></span>'
+ '        </span>'
+ '        <div class="mst-interesting indent1"></div>'
+ '      </div>'
+ '    </div>'
+ '  {{/ifRolledOver}}'
+ '{{/if}}'
+ '{{#if fineMergedStackTree}}'
+ '  {{#ifRolledOver fineMergedStackTree}}'
+ '    <div>fine-grained profile <em>rolled over</em><div>'
+ '  {{^}}'
+ '    <span tabindex="0" class="lightbtn clickable pad1" onclick="toggleFineMergedStackTree()">'
+ '      <span class="red">fine-grained profile</span> ({{fineMergedStackTree.sampleCount}})'
+ '    </span><br>'
+ '    <div class="nowrap indent1 hide" id="mstFineOuter">'
+ '      <select class="mst-filter input-large" onchange="this.blur()"'
             // taking 2px off of bottom because of extra 2px added to expandable element below
+ '          style="margin: 4px 4px 2px 4px"></select><br>'
+ '      <div class="overflow">'
+ '        <span tabindex="0" class="expandable mst-common clickable pad1 hide"'
                // the bottom margin is needed to trump .expandable class' margin-bottom: 4px since
                // no buffer is needed when this is expanded since nothing can be expanded directly
                // below, the 1px padding is to give space for the focus highlight
+ '             style="margin: 2px 1px 0 1px">'
+ '          <span class="unexpanded-content red">common base</span>'
+ '          <span class="expanded-content hide"></span>'
+ '        </span><br>'
+ '        <div class="mst-interesting indent1"></div>'
+ '      </div>'
+ '    </div>'
+ '  {{/ifRolledOver}}'
+ '{{/if}}'
var spansTemplateText = ''
+ '<div class="indent1" style="float: left; width: 3em; text-align: right">'
+ '    +{{nanosToMillis offset}}'
+ '  </div>'
+ '  <div style="margin-left: {{margin nestingLevel}}em">'
+ '    <div style="width: 2em; float: left; text-align: right">'
+ '      {{nanosToMillis duration}}{{#if active}}..{{/if}}'
+ '    </div>'
+ '    <div style="margin-left: 3em">'
+ '      {{#ifLongDescription message.text}}'
+ '        <div>'
+ '          <span tabindex="0" class="expandable clickable overflow">'
+ '            <span class="inlineblock pad1">'
                 // lack of spacing between first/middle/last text is important in these lines
+ '              {{first80 message.text}}<span class="unexpanded-content"> ... </span'
+ '                 ><span class="expanded-content hide">{{middle message.text}}</span'
+ '                 >{{last80 message.text}}'
+ '            </span>'
+ '          </span>'
+ '        </div>'
+ '      {{^}}'
+ '        <div class="indent1">'
+ '          {{message.text}}'
+ '        </div>'
+ '      {{/ifLongDescription}}'
+ '      {{#if message.detail}}'
+ '        <div class="indent2">'
+ '          <span tabindex="0" class="expandable clickable overflow">'
+ '            <span class="unexpanded-content red pad1">detail</span>'
+ '            <span class="expanded-content inlineblock pad1 hide">'
+ '              {{{messageDetailHtml message.detail}}}'
+ '            </span>'
+ '          </span>'
+ '        </div>'
+ '      {{/if}}'
+ '      {{#if error}}'
+ '        <div class="indent2">'
+ '          <strong><span class="indent1">{{error.text}}</span></strong>'
+ '          <br>'
+ '          {{#if error.detail}}'
+ '            <div class="indent1">'
+ '              {{{messageDetailHtml error.detail}}}'
+ '            </div>'
+ '          {{/if}}'
+ '          {{#if error.exception}}'
+ '            <div class="indent1">'
+ '              <span tabindex="0" class="expandable clickable overflow">'
+ '                <span class="unexpanded-content red pad1">exception</span>'
+ '                <span class="expanded-content inlineblock nowrap pad1 hide">'
+ '                  {{{exceptionHtml error.exception}}}'
+ '                </span>'
+ '              </span>'
+ '            </div>'
+ '          {{/if}}'
+ '        </div>'
+ '      {{/if}}'
+ '      {{#if stackTrace}}'
+ '        <div class="indent2">'
+ '          <span tabindex="0" class="expandable clickable overflow">'
+ '            <span class="unexpanded-content red pad1">span stack trace</span>'
+ '            <span class="expanded-content inlineblock nowrap pad1 hide">'
+ '              {{{stackTraceHtml stackTrace}}}'
+ '            </span>'
+ '          </span>'
+ '        </div>'
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
      // need to check not null since typeof null == 'object'
      if (propVal != null && typeof propVal == 'object') {
        ret += propName + ':'
        ret += '<br>'
        ret += '<span class="indent1 inlineblock">'
        ret += messageDetailHtml(propVal)
        ret += '</span><br>'
      } else {
        ret += '<span class="inlineblock" style="text-indent: -1em; margin-left: 1em">'
        ret += propName + ': ' + propVal
        ret += '</span><br>'
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
  } else if (description.length <= 160) {
    return description.slice(-(description.length - 80))
  } else {
    // leave room for ' ... '
    return description.slice(-75)
  }
})
Handlebars.registerHelper('middle', function(description) {
  if (description.length <= 160) {
    return ""
  } else {
    return description.slice(80, -75)
  }
})
Handlebars.registerHelper('exceptionHtml', function(exception) {
  var html = '<strong>'
  while (exception) {
    html += exception.display + '</strong><br>'
    for (var i = 0; i < exception.stackTrace.length; i++) {
      html += '<span class="inlineblock" style="width: 4em"></span>at '
        + exception.stackTrace[i] + '<br>'
    }
    if (exception.framesInCommon) {
      html += '... ' + exception.framesInCommon + ' more<br>'
    }
    exception = exception.cause
    if (exception) {
      html += "<strong>Caused by: "
    }
  }
  return html
})
Handlebars.registerHelper('stackTraceHtml', function(stackTrace) {
  var html = ''
  for (var i = 0; i < stackTrace.length; i++) {
    html += stackTrace[i] + '<br>'
  }
  return html
})
var summaryTrace, detailTrace
var traceSummaryTemplate, traceDetailTemplate, spansTemplate
var smartToggleTimer
var mousedownSpanPageX, mousedownSpanPageY
$(document).ready(function() {
  traceSummaryTemplate = Handlebars.compile(traceSummaryTemplateText)
  traceDetailTemplate = Handlebars.compile(traceDetailTemplateText)
  spansTemplate = Handlebars.compile(spansTemplateText)
  $(document).mousedown(function(e) {
    mousedownSpanPageX = e.pageX
    mousedownSpanPageY = e.pageY
  })
  $(document).on('keydown', '.clickable', function(e) {
    if (e.keyCode == 13) {
      // enter key, for clicking when focus is on .clickable
      // pass extra arg to click handler in case click handler wants to treat keyboard clicks
      // differently from mouse clicks
      $(this).trigger('click', true)
    }
  })
  $(document).on('click', '.expandable', function(e, keyboard) {
    smartToggle($(this).find('.unexpanded-content'), $(this).find('.expanded-content'), $(this), e,
        keyboard)
  })
})
function toggleSpans() {
  if (! $('#sps').html()) {
    // first time opening
    $('#sps').removeClass('hide')
    renderNext(detailTrace.spans, 0)
  } else {
    $('#sps').toggleClass('hide')
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
  if (start + 100 < spans.length) {
    setTimeout(function() { renderNext(spans, start + 100) }, 10)
  }
}
function basicToggle(unexpandedSelector, expandedSelector, outerSelector) {
  $(unexpandedSelector).toggleClass('hide')
  $(expandedSelector).toggleClass('hide')
  $(outerSelector).toggleClass('expanded')
}
function smartToggle(unexpandedSelector, expandedSelector, outerSelector, e, keyboard) {
  if (keyboard) {
    basicToggle(unexpandedSelector, expandedSelector, outerSelector)
    return
  }
  if (Math.abs(e.pageX - mousedownSpanPageX) > 5 || Math.abs(e.pageY - mousedownSpanPageY) > 5) {
    // not a simple single click, probably highlighting text
    return
  }
  if (smartToggleTimer) {
    // double click, probably highlighting text
    clearTimeout(smartToggleTimer)
    smartToggleTimer = undefined
    return
  }
  if ($(unexpandedSelector).hasClass('hide')) {
    // slight delay on hiding in order to not contract on double click text highlighting
    smartToggleTimer = setTimeout(function() {
      $(unexpandedSelector).removeClass('hide')
      $(expandedSelector).addClass('hide')
      $(outerSelector).removeClass('expanded')
      smartToggleTimer = undefined
    }, 250)
  } else {
    // no delay on expanding because it makes it feel sluggish
    // (at the expense of double click text highlighting also expanding the span)
    $(unexpandedSelector).addClass('hide')
    $(expandedSelector).removeClass('hide')
    $(outerSelector).addClass('expanded')
    // but still create smartToggleTimer to prevent double click from expanding and then contracting
    smartToggleTimer = setTimeout(function() { smartToggleTimer = undefined }, 500)
  }
}
function toggleCoarseMergedStackTree() {
  toggleMergedStackTree(detailTrace.coarseMergedStackTree, $('#mstCoarseOuter'))
}
function toggleFineMergedStackTree() {
  toggleMergedStackTree(detailTrace.fineMergedStackTree, $('#mstFineOuter'))
}
function toggleMergedStackTree(rootNode, selector) {
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
    var ret = '<span class="inlineblock" style="width: 4em; margin-left: ' + ((level / 3)) + 'em">'
    var samplePercentage = (nodeSampleCount / rootNodeSampleCount) * 100
    ret += samplePercentage.toFixed(1)
    ret += '%</span>'
    ret += node.stackTraceElement + '<br>'
    if (node.leafThreadState) {
      ret += '<span class="inlineblock" style="width: 4em; margin-left: ' + ((level / 3)) + 'em">'
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
  if (!$(selector).hasClass('hide')) {
    $(selector).addClass('hide')
  } else {
    if (! $(selector).find('.mst-interesting').html()) {
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
      $(selector).find('.mst-filter').html('')
      $.each(orderedNodes, function(i, node) {
        $(selector).find('.mst-filter').append($('<option />').val(node.name)
            .text(node.name + ' (' + rootNode.metricNameCounts[node.name] + ')'))
      })
      var i = 0
      var interestingRootNode = rootNode
      var uninterestingHtml = ''
      while (true) {
        if (! interestingRootNode.childNodes || interestingRootNode.childNodes.length != 1) {
          break
        }
        var childNode = interestingRootNode.childNodes[0]
        if (childNode.leafThreadState) {
          break
        }
        uninterestingHtml += '<span class="inlineblock" style="width: 4em">100.0%</span>'
            + interestingRootNode.stackTraceElement + '<br>'
        interestingRootNode = childNode
        i++
      }
      $(selector).find('.mst-filter').change(function() {
        // update merged stack tree based on filter
        var interestingHtml = curr(interestingRootNode, 0, $(this).val())
        $(selector).find('.mst-common .expanded-content').html(uninterestingHtml)
        $(selector).find('.mst-common').removeClass('hide')
        $(selector).find('.mst-interesting').html(interestingHtml)
      })
      // build initial merged stack tree
      var interestingHtml = curr(interestingRootNode, 0)
      $(selector).find('.mst-common .expanded-content').html(uninterestingHtml)
      $(selector).find('.mst-common').removeClass('hide')
      $(selector).find('.mst-interesting').html(interestingHtml)
    }
    $(selector).removeClass('hide')
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
