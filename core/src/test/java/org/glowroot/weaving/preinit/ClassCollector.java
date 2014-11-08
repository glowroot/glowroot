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
package org.glowroot.weaving.preinit;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import org.checkerframework.checker.nullness.qual.Nullable;

class ClassCollector {

    @Nullable
    private String superInternalNames;
    @Nullable
    private ImmutableList<String> interfaceInternalNames;
    @Nullable
    private ImmutableList<String> allSuperInternalNames;

    // map key is the method "name:desc"
    private final Map<String, MethodCollector> methodCollectors = Maps.newHashMap();

    @Nullable
    String getSuperInternalNames() {
        return superInternalNames;
    }

    ImmutableList<String> getInterfaceInternalNames() {
        return interfaceInternalNames;
    }

    ImmutableList<String> getAllSuperInternalNames() {
        return allSuperInternalNames;
    }

    @Nullable
    MethodCollector getMethodCollector(String methodId) {
        return methodCollectors.get(methodId);
    }

    Set<String> getMethodIds() {
        return methodCollectors.keySet();
    }

    void setSuperInternalNames(@Nullable String superInternalNames) {
        this.superInternalNames = superInternalNames;
    }

    void setInterfaceTypes(List<String> interfaceInternalNames) {
        this.interfaceInternalNames = ImmutableList.copyOf(interfaceInternalNames);
    }

    void setAllSuperInternalNames(List<String> allSuperInternalNames) {
        this.allSuperInternalNames = ImmutableList.copyOf(allSuperInternalNames);
    }

    void addMethod(ReferencedMethod referencedMethod, MethodCollector methodCollector) {
        methodCollectors.put(referencedMethod.getName() + ":" + referencedMethod.getDesc(),
                methodCollector);
    }
}
