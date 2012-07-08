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
public class ParsedType {

    private final boolean missing;

    private final String name;
    private final String superName;
    private final String[] interfaceNames;
    private final List<ParsedMethod> methods;

    ParsedType(String name, @Nullable String superName, String[] interfaceNames,
            List<ParsedMethod> methods) {

        this.missing = false;
        this.name = name;
        this.superName = superName;
        this.interfaceNames = interfaceNames;
        this.methods = methods;
    }

    ParsedType(String name) {
        this.missing = true;
        this.name = name;
        superName = null;
        interfaceNames = new String[0];
        methods = ImmutableList.of();
    }

    public boolean isMissing() {
        return missing;
    }

    public String getClassName() {
        // TODO cache result
        return name.replace('/', '.');
    }

    @Nullable
    public String getSuperName() {
        return superName;
    }

    public String[] getInterfaceNames() {
        return interfaceNames;
    }

    public List<ParsedMethod> getMethods() {
        return methods;
    }

    @Nullable
    public ParsedMethod getMethod(String name, Type[] types) {
        for (ParsedMethod method : methods) {
            if (!method.getName().equals(name)) {
                continue;
            }
            if (Arrays.equals(method.getArgs(), types)) {
                return method;
            }
        }
        return null;
    }

    public static class ParsedMethod {

        private final String name;
        private final Type[] args;

        ParsedMethod(String name, Type[] args) {
            this.name = name;
            this.args = args;
        }

        public String getName() {
            return name;
        }

        public Type[] getArgs() {
            return args;
        }
    }
}
