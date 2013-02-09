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
+ '  {{headline}}'
+ '  {{#if showExport}}'
// unfortunately vertical padding isn't applied to inline elements, so "button" is a little small
// but it seems worth keeping this inline and it is a less used "button" anyways
+ '    <a class="indent1 pad1 rounded4" href="explorer/export/{{id}}">export</a>'
+ '  {{/if}}'
+ '</div>'
+ 'start: {{date start}}<br>'
+ 'duration: {{nanosToMillis duration}}{{#if active}}..{{/if}} milliseconds<br>'
+ '{{#eachKeyValuePair attributes}}'
+ '  <div class="second-line-indent">{{key}}: {{value}}</div>'
+ '{{/eachKeyValuePair}}'
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
+ '    <span class="lightbtn pad1" onclick="toggleSpans()">'
+ '      <span class="red">spans</span> ({{spans.length}})'
+ '    </span><br>'
+ '    <div id="sps"></div>'
+ '  {{/ifRolledOver}}'
+ '{{/if}}'
   // TODO combine merged stack tree into template to consolidate code between coarse and fine mst
+ '{{#if coarseMergedStackTree}}'
+ '  {{#ifRolledOver coarseMergedStackTree}}'
+ '    <div>coarse-grained profile <em>rolled over</em></div>'
+ '  {{^}}'
+ '    <span class="lightbtn pad1" onclick="toggleCoarseMergedStackTree()">'
+ '      <span class="red">coarse-grained profile</span> ({{coarseMergedStackTree.sampleCount}})'
+ '    </span><br>'
+ '    <div class="nowrap indent1 hide" id="mstCoarseOuter">'
+ '      <select class="mst-filter input-large" onchange="this.blur()" style="margin: 4px">'
+ '      </select><br>'
+ '      <div class="mst-common hide">'
+ '        <span class="unexpanded-content red">common base</span>'
           // standard expanded-content bottom margin is not needed since nothing can be expanded
           // directly below
           // using span so background will stretch beyond page border if needed
+ '        <span class="expanded-content inlineblock hide" style="margin-bottom: 0">'
+ '        </span>'
+ '      </div>'
+ '      <div class="mst-interesting indent1"></div>'
+ '    </div>'
+ '  {{/ifRolledOver}}'
+ '{{/if}}'
+ '{{#if fineMergedStackTree}}'
+ '  {{#ifRolledOver fineMergedStackTree}}'
+ '    <div>fine-grained profile <em>rolled over</em><div>'
+ '  {{^}}'
+ '    <span class="lightbtn pad1" onclick="toggleFineMergedStackTree()">'
+ '      <span class="red">fine-grained profile</span> ({{fineMergedStackTree.sampleCount}})'
+ '    </span><br>'
+ '    <div class="nowrap indent1 hide" id="mstFineOuter">'
+ '      <select class="mst-filter input-large" onchange="this.blur()" style="margin: 4px">'
+ '      </select><br>'
+ '      <div class="mst-common hide">'
+ '        <span class="unexpanded-content red">common base</span>'
           // standard expanded-content bottom margin is not needed since nothing can be expanded
           // directly below
           // using span so background will stretch beyond page border if needed
+ '        <span class="expanded-content hide" style="margin-bottom: 0">'
+ '        </span>'
+ '      </div>'
+ '      <div class="mst-interesting indent1"></div>'
+ '    </div>'
+ '  {{/ifRolledOver}}'
+ '{{/if}}'
var spansTemplateText = ''
+ '<div class="inlineblock indent1 unexpanded-vertical-padding"'
+ '    style="float: left; width: {{offsetColumnWidth}}em; text-align: right">'
+ '  +{{nanosToMillis offset}}'
+ '</div>'
   // +1 at the beginning is for the indent1 of the offset column
+ '<div style="margin-left: {{add3 1 nestingLevel offsetColumnWidth}}em">'
+ '  <div class="indent1 unexpanded-vertical-padding"'
+ '      style="width: {{offsetColumnWidth}}em; float: left; text-align: right">'
+ '    {{nanosToMillis duration}}{{#if active}}..{{/if}}'
+ '  </div>'
+ '  <div style="margin-left: {{add 2 offsetColumnWidth}}em">'
+ '    {{#if message}}'
+ '      {{#ifLongMessage message.text}}'
+ '        <div>'
+ '          <span class="unexpanded-content">'
+ '            {{firstPart message.text}}'
+ '            <span class="red"><strong>...</strong></span>'
+ '            {{lastPart message.text}}'
+ '          </span>'
+ '          <div class="expanded-content breakword hide">'
+ '            {{message.text}}'
+ '          </div>'
+ '        </div>'
+ '      {{^}}'
+ '        <div class="unexpanded-padding">'
+ '          {{message.text}}'
+ '        </div>'
+ '      {{/ifLongMessage}}'
+ '      {{#if message.detail}}'
+ '        <div class="indent2">'
+ '          <span class="unexpanded-content red">detail</span>'
+ '          <div class="expanded-content hide">'
+ '            {{{messageDetailHtml message.detail}}}'
+ '          </div>'
+ '        </div>'
+ '      {{/if}}'
+ '    {{/if}}'
+ '    {{#if error}}'
+ '      <div{{{errorIndent message}}}>'
+ '        <strong><span class="indent1">{{error.text}}</span></strong>'
+ '        <br>'
+ '        {{#if error.detail}}'
+ '          <div class="indent1">'
+ '            {{{messageDetailHtml error.detail}}}'
+ '          </div>'
+ '        {{/if}}'
+ '        {{#if error.exception}}'
+ '          <div{{{exceptionIndent message}}}>'
+ '            <span class="unexpanded-content red">exception</span>'
               // using span so background will stretch beyond page border if needed
+ '            <span class="expanded-content nowrap hide">'
+ '              {{{exceptionHtml error.exception}}}'
+ '            </span>'
+ '          </div>'
+ '        {{/if}}'
+ '      </div>'
+ '    {{/if}}'
+ '    {{#if stackTrace}}'
+ '      <div class="indent2">'
+ '        <span class="unexpanded-content red">span stack trace</span>'
           // using span so background will stretch beyond page border if needed
+ '        <span class="expanded-content nowrap hide">'
+ '          {{{stackTraceHtml stackTrace}}}'
+ '        </span>'
+ '      </div>'
+ '    {{/if}}'
+ '  </div>'
+ '</div>'
Handlebars.registerHelper('eachKeyValuePair', function(map, options) {
  var buffer = ''
  if (map) {
    $.each(map, function(key, value) {
      buffer += options.fn({ key: key, value: value })
    })
  }
  return buffer
})
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
        ret += '<div class="indent1">'
        ret += messageDetailHtml(propVal)
        ret += '</div>'
      } else {
        ret += '<div class="breakword textindent1">'
        ret += propName + ': ' + propVal
        ret += '</div>'
      }
    })
    return ret
  }
  return messageDetailHtml(detail)
})
Handlebars.registerHelper('ifLongMessage', function(message, options) {
  if (message.length > spanLineLength) {
    return options.fn(this)
  } else {
    return options.inverse(this)
  }
})
Handlebars.registerHelper('errorIndent', function(message) {
  if (message) {
    return ' class="indent2"'
  } else {
    return ''
  }
})
Handlebars.registerHelper('exceptionIndent', function(message) {
  if (message) {
    return ' class="indent1"'
  } else {
    return ' class="indent2"'
  }
})
Handlebars.registerHelper('ifRolledOver', function(value, options) {
  if (value == 'rolled over') {
    return options.fn(this)
  } else {
    return options.inverse(this)
  }
})
Handlebars.registerHelper('add', function(x, y) {
  return x + y
})
Handlebars.registerHelper('add3', function(x, y, z) {
  return x + y + z
})
Handlebars.registerHelper('firstPart', function(message) {
  // -3 to leave room in the middle for ' ... '
  return message.slice(0, spanLineLength/2 - 3)
})
Handlebars.registerHelper('lastPart', function(message) {
  // -3 to leave room in the middle for ' ... '
  return message.slice(-(spanLineLength/2 - 3))
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
var spanLineLength
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
  $(document).on('click', '.unexpanded-content, .expanded-content', function(e, keyboard) {
    smartToggle($(this).parent(), e, keyboard)
  })
})
function initSpanLineLength() {
  // using an average character (width-wise) 'o'
  $('body').prepend('<span class="offscreen" id="bodyFontCharWidth">o</span>')
  var charWidth = $('#bodyFontCharWidth').width()
  // -100 for the left margin of the span lines
  spanLineLength = ($('#sps').width() - 100) / charWidth
  // min value of 80, otherwise not enough context provided by the elipsed line
  spanLineLength = Math.max(spanLineLength, 80)
}
function toggleSpans() {
  if (! $('#sps').html()) {
    // first time opening
    initSpanLineLength()
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
    var maxDurationMillis = (spans[0].duration / 1000000).toFixed(1)
    spans[i].offsetColumnWidth = maxDurationMillis.length / 2 + 1
    html += spansTemplate(spans[i])
  }
  html += '</div>'
  $('#sps').append(html)
  if (start + 100 < spans.length) {
    setTimeout(function() { renderNext(spans, start + 100) }, 10)
  }
}
function basicToggle(parent) {
  var expanded = parent.find('.expanded-content')
  var unexpanded = parent.find('.unexpanded-content')
  unexpanded.toggleClass('hide')
  expanded.toggleClass('hide')
  if (unexpanded.hasClass('hide')) {
    expanded.focus()
  } else {
    unexpanded.focus()
  }
}
function smartToggle(parent, e, keyboard) {
  if (keyboard) {
    basicToggle(parent)
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
  var expanded = parent.find('.expanded-content')
  var unexpanded = parent.find('.unexpanded-content')
  if (unexpanded.hasClass('hide')) {
    // slight delay on hiding in order to not contract on double click text highlighting
    smartToggleTimer = setTimeout(function() {
      unexpanded.removeClass('hide')
      expanded.addClass('hide')
      smartToggleTimer = undefined
    }, 250)
  } else {
    // no delay on expanding because it makes it feel sluggish
    // (at the expense of double click text highlighting also expanding the span)
    unexpanded.addClass('hide')
    expanded.removeClass('hide')
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
    // the space after the % is actually important when highlighting a block of stack trace elements
    // in the ui and copy pasting into the eclipse java stack trace console, because the space gives
    // separation between the percentage and the stack trace element and so eclipse is still able to
    // understand the stack trace
    ret += '% </span>'
    ret += node.stackTraceElement + '<br>'
    if (node.leafThreadState) {
      // each indent is 1/3em, so adding extra .333em to indent thread state
      ret += '<span class="inlineblock" style="width: 4.333em; margin-left: ' + ((level / 3))
                 + 'em">'
      ret += '</span>'
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
        // the space after the % is actually important when highlighting a block of stack trace
        // elements in the ui and copy pasting into the eclipse java stack trace console, because
        // the space gives separation between the percentage and the stack trace element and so
        // eclipse is still able to understand the stack trace
        uninterestingHtml += '<span class="inlineblock" style="width: 4em">100.0% </span>'
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
