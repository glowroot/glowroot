/*
 * Copyright 2018 the original author or authors.
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
package org.glowroot.agent.bytecode.api;

import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

// this is for bytecode helpers that are needed before Bytecode service is set up
public class BytecodeEarly {

    private BytecodeEarly() {}

    // this is used by IbmJava6HackClassFileTransformer2
    public static Set</*@Nullable*/ Object> removeDuplicates(Set</*@Nullable*/ Object> urls) {
        if (urls.size() == 2) {
            // only a very specialized implementation is needed for this hack
            Iterator</*@Nullable*/ Object> i = urls.iterator();
            Object obj1 = i.next();
            Object obj2 = i.next();
            if (obj1 instanceof URL && obj2 instanceof URL) {
                URL url1 = (URL) obj1;
                URL url2 = (URL) obj2;
                if (url1.toExternalForm().equals(url2.toExternalForm())) {
                    return new HashSet</*@Nullable*/ Object>(Arrays.asList(url1));
                }
            }
        }
        return urls;
    }
}
