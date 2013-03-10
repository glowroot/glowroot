/**
 * Copyright 2013 the original author or authors.
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
package io.informant.util;

import io.informant.marker.NotThreadSafe;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@NotThreadSafe
public class CharArrayWriter extends java.io.CharArrayWriter {

    // provides access to protected char buffer
    public void arraycopy(int srcPos, char[] dest, int destPos, int length) {
        System.arraycopy(buf, srcPos, dest, destPos, length);
    }
}
