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

/* global TraceRenderer, $ */

$(document).ready(function () {
  var trace = JSON.parse($('#traceJson').html());
  var spans = JSON.parse($('#spansJson').html());
  var profileJson = $('#profileJson').html();
  var profile;
  if (profileJson) {
    profile = JSON.parse(profileJson);
  }
  var outlierProfileJson = $('#outlierProfileJson').html();
  var outlierProfile;
  if (outlierProfileJson) {
    outlierProfile = JSON.parse(outlierProfileJson);
  }
  TraceRenderer.renderTraceFromExport(trace, $('#tracePlaceholder'), spans, profile, outlierProfile);
});
