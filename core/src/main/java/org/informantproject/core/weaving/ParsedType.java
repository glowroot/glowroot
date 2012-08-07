/**
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
package org.informantproject.core.weaving;

import java.util.Arrays;
import java.util.List;

import javax.annotation.Nullable;

import org.objectweb.asm.Type;

import com.google.common.collect.ImmutableList;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// a ParsedType is never created for Object.class
// Immutable
class ParsedType {

    private final boolean missing;

    private final String name;
    @Nullable
    private final String superName;
    private final ImmutableList<String> interfaceNames;
    private final ImmutableList<ParsedMethod> methods;

    static ParsedType from(String name, @Nullable String superName,
            ImmutableList<String> interfaceNames, ImmutableList<ParsedMethod> methods) {
        return new ParsedType(false, name, superName, interfaceNames, methods);
    }

    static ParsedType fromMissing(String name) {
        return new ParsedType(true, name, null, ImmutableList.<String> of(),
                ImmutableList.<ParsedMethod> of());
    }

    private ParsedType(boolean missing, String name, @Nullable String superName,
            ImmutableList<String> interfaceNames, ImmutableList<ParsedMethod> methods) {

        this.missing = missing;
        this.name = name;
        this.superName = superName;
        this.interfaceNames = interfaceNames;
        this.methods = methods;
    }

    boolean isMissing() {
        return missing;
    }

    String getClassName() {
        // TODO cache result
        return name.replace('/', '.');
    }

    @Nullable
    String getSuperName() {
        return superName;
    }

    List<String> getInterfaceNames() {
        return interfaceNames;
    }

    @Nullable
    ParsedMethod getMethod(String name, Type[] types) {
        for (ParsedMethod method : methods) {
            if (!method.name.equals(name)) {
                continue;
            }
            if (Arrays.equals(method.args, types)) {
                return method;
            }
        }
        return null;
    }

    static class ParsedMethod {

        private final String name;
        private final Type[] args;

        ParsedMethod(String name, Type[] args) {
            this.name = name;
            this.args = args;
        }
    }
}
