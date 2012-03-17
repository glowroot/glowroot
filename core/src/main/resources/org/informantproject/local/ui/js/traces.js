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
var tracesTemplateText = ''
+ '{{#each .}}'
+ '<div>'
+ '  {{#if active}}'
+ '    <b>ACTIVE {{#if stuck}}/ STUCK{{/if}}</b><br>'
+ '  {{^}}'
+ '    {{#if stuck}}<b>{{#if completed}}UN{{/if}}STUCK</b><br>{{/if}}'
+ '  {{/if}}'
+ '  {{description}}'
+ '  {{#if showExport}}'
+ '    <span style="width: 1em; display: inline-block"></span>'
+ '    <a href="trace/export?id={{id}}">export</a>'
+ '  {{/if}}<br>'
+ '  start: {{date start}}<br>'
+ '  duration: {{nanosToMillis duration}}{{#if active}}..{{/if}} milliseconds<br>'
+ '  {{#if username}}username: {{username}}<br>{{/if}}'
+ '  breakdown:<br>'
+ '  <table class="metrics-table" style="margin-left: 1em; border-spacing:0">'
+ '    <thead>'
+ '      <tr>'
+ '        <td></td>'
+ '        <td>total</td>'
+ '        <td>min</td>'
+ '        <td>max</td>'
+ '        <td>count</td>'
+ '      </tr>'
+ '    </thead>'
+ '    <tbody>'
+ '      {{#each metrics}}'
+ '        <tr>'
+ '          <td style="text-align: left">{{name}}</td>'
+ '          <td>{{nanosToMillis total}}</td>'
+ '          <td>{{nanosToMillis min}}</td>'
+ '          <td>{{nanosToMillis max}}</td>'
+ '          <td>{{count}}</td>'
+ '        </tr>'
+ '      {{/each}}'
+ '    </tbody>'
+ '  </table>'
+ '  {{#if contextMap}}'
+ '    <a href="" onclick="$(\'#cm_{{id}}\').toggle(); return false">context map</a>'
+ '    ({{contextMapLines contextMap}})<br>'
+ '    <div id="cm_{{id}}" style="display: none">'
+ '      <div style="margin-left: 1em">{{#contextMapHtml contextMap}}{{/contextMapHtml}}</div>'
+ '    </div>'
+ '  {{/if}}'
+ '  {{#if spans}}'
+ '    <a href="" onclick="toggleSpan(\'{{id}}\'); return false">spans</a> ({{spans.length}})<br>'
+ '    <div id="sp_{{id}}" style="display: none"></div>'
+ '  {{/if}}'
+ '  {{#if mergedStackTree.sampleCount}}'
+ '    <a href="" onclick="toggleMergedStackTree(\'{{id}}\'); return false">merged stack tree</a>'
+ '    ({{mergedStackTree.sampleCount}})<br>'
+ '    <div id="mst_{{id}}" style="display: none; white-space: nowrap"></div>'
+ '  {{/if}}'
+ '  <br>'
+ '</div>'
+ '{{/each}}'
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
+ '          <a href="" onclick="$(\'.sp_{{../../id}}_{{index}}\').toggle(); return false"'
+ '              style="color: #333">'
+ '            {{#short description}}{{/short}}'
+ '          </a>'
+ '        </span>'
+ '        <span class="sp_{{../../id}}_{{index}}" style="display: none">'
+ '          <a href="" onclick="$(\'.sp_{{../../id}}_{{index}}\').toggle(); return false">'
+ '            {{description}}'
+ '          </a>'
+ '        </span>'
+ '      {{^}}'
+ '        {{description}}'
+ '      {{/ifLongDescription}}'
+ '      {{#if index}}'
+ '        {{! context map for index=0 is displayed in root context section above }}'
+ '        <div style="margin-left: 1em">{{#contextMapHtml contextMap}}{{/contextMapHtml}}</div>'
+ '      {{/if}}'
+ '      {{#if stackTraceHash}}'
+ '        <a href="" onclick="viewStackTrace(\'{{stackTraceHash}}\'); return false">'
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
Handlebars.registerHelper('first', function(array, options) {
  return options.fn(array[0])
})
Handlebars.registerHelper('contextMapLines', function(contextMap) {
  function contextMapLines(contextMap) {
    var ret = 0
    for (var propName in contextMap) {
      var propVal = contextMap[propName]
      if (typeof propVal == 'object') {
        ret += contextMapLines(propVal) + 1 // +1 because the map key is on its own line
      } else {
        ret += 1
      }
    }
    return ret
  }
  return contextMapLines(contextMap)
})
Handlebars.registerHelper('contextMapHtml', function(contextMap) {
  function contextMapHtml(contextMap) {
    var ret = ''
    for (var propName in contextMap) {
      var propVal = contextMap[propName]
      ret += propName + ':'
      if (typeof propVal == 'object') {
        ret += '<br>'
        ret += '<div style="margin-left: 1em">'
        ret += contextMapHtml(propVal)
        ret += '</div>'
      } else {
        ret += ' ' + propVal + '<br>'
      }
    }
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
var traces
var tracesTemplate
var spansTemplate
$(document).ready(function() {
  tracesTemplate = Handlebars.compile(tracesTemplateText)
  spansTemplate = Handlebars.compile(spansTemplateText)
})
function traceForId(id) {
  for (var i = 0; i < traces.length; i++) {
    if (traces[i].id == id) {
      return traces[i]
    }
  }
}
function toggleSpan(id) {
  if ($('#sp_' + id).is(':visible')) {
    $('#sp_' + id).hide()
  } else {
    if (! $('#sp_' + id).html()) {
      var html = spansTemplate(traceForId(id))
      $(html).appendTo('#sp_' + id)
    }
    $('#sp_' + id).show()
  }
}
function toggleMergedStackTree(id) {
  var rootNode = traceForId(id).mergedStackTree
  function curr(node, level) {
    var ret = ''
    var stackTraceElement = node.stackTraceElement
    if (node.sampleCount < rootNode.sampleCount)
      level++
    for (var j = 0; j < level; j++) {
      ret += '&nbsp;'
    }
    ret += '&nbsp;&nbsp;<span style="display: inline-block; width: 4em">'
    var samplePercentage = (node.sampleCount / rootNode.sampleCount) * 100
    ret += samplePercentage.toFixed(1)
    ret += '%</span>'
    ret += stackTraceElement + '<br>'
    if (node.leafThreadState) {
      for (var j = 0; j < level; j++) {
        ret += '&nbsp;'
      }
      ret += '&nbsp;&nbsp;<span style="display: inline-block; width: 4em">'
      ret += samplePercentage.toFixed(1)
      ret += '%</span>'
      ret += '&nbsp;' + node.leafThreadState + '<br>'
    }
    if (node.childNodes) {
      var childNodes = node.childNodes
      // order child nodes by sampleCount (descending)
      childNodes.sort(function(a, b) { return b.sampleCount - a.sampleCount })
      for (var i = 0; i < childNodes.length; i++) {
        ret += curr(childNodes[i], level)
      }
    }
    return ret
  }
  if ($('#mst_' + id).is(':visible')) {
    $('#mst_' + id).hide()
  } else {
    if (! $('#mst_' + id).html()) {
      var html = curr(rootNode, 0)
      $(html).appendTo('#mst_' + id)
    }
    $('#mst_' + id).show()
  }
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
