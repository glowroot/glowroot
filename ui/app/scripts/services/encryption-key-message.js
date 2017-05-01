/*
 * Copyright 2017 the original author or authors.
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

/* global glowroot */

glowroot.factory('encryptionKeyMessage', [
  function () {
    function extra() {
      var message = '\n\ncassandra.symmetricEncryptionKey can be any 32 character hex string';
      if (window.crypto && window.crypto.getRandomValues) {
        var symmetricEncryptionKey = '';
        var array = new Uint32Array(4);
        window.crypto.getRandomValues(array);
        for (var i = 0; i < array.length; i++) {
          var hex = array[i].toString(16);
          symmetricEncryptionKey += ('0000000' + hex).slice(-8); // zero padding if needed;
        }
        return message + ', e.g. here\'s one generated right now in your browser that you could use: '
            + symmetricEncryptionKey;
      } else {
        return message;
      }
    }

    return {
      extra: extra
    };
  }
]);
