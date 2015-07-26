/*
 * Copyright 2015 the original author or authors.
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

// this is not angular service because it is included in exported traces as well
window.gtParseIncludesExcludes = function(text) {
  var includes = [];
  var excludes = [];
  var i;
  var c;
  var currTerm;
  var inQuote;
  var inExclude;

  function pushCurrTerm() {
    if (inExclude) {
      excludes.push(currTerm);
    } else {
      includes.push(currTerm);
    }
  }

  if (!text) {
    text = '';
  }

  for (i = 0; i < text.length; i++) {
    c = text.charAt(i);
    if (currTerm !== undefined) {
      // inside quoted or non-quoted term
      if (c === inQuote || !inQuote && c === ' ') {
        // end of term (quoted or non-quoted)
        pushCurrTerm();
        currTerm = undefined;
        inQuote = undefined;
        inExclude = false;
      } else if (!inQuote && (c === '\'' || c === '"')) {
        return {
          error: 'Mismatched quote'
        };
      } else {
        currTerm += c;
      }
    } else if (c === '\'' || c === '"') {
      // start of quoted term
      currTerm = '';
      inQuote = c;
    } else if (c === '-') {
      // validate there is an immediate next term
      if (i === text.length - 1 || text.charAt(i + 1) === ' ') {
        return {
          error: 'Invalid location for minus'
        };
      }
      // next term is an exclude
      inExclude = true;
    } else if (c !== ' ') {
      // start of non-quoted term
      currTerm = c;
    }
  }
  if (inQuote) {
    return {
      error: 'Mismatched quote'
    };
  }
  if (currTerm) {
    // end the last non-quoted term
    pushCurrTerm();
  }
  return {
    includes: includes,
    excludes: excludes
  };
};
