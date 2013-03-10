/**
 * Copyright 2011-2013 the original author or authors.
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


/**
 * Convenience method for nanosecond comparison.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public class Nanoseconds {

    private Nanoseconds() {}

    // nano times roll over every 292 years, so it is important to test differences between
    // nano times (e.g. nano2 - nano1 >= 0, not nano1 <= nano2)
    // (see http://java.sun.com/javase/7/docs/api/java/lang/System.html#nanoTime())
    public static boolean lessThan(long nano1, long nano2) {
        return nano2 - nano1 >= 0;
    }
}
