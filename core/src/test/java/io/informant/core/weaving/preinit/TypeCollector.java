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
package io.informant.core.weaving.preinit;

import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class TypeCollector {

    @Nullable
    private String superType;
    @Nullable
    private ImmutableSet<String> interfaceTypes;
    @Nullable
    private ImmutableSet<String> allSuperTypes;

    // map key is the method "name:desc"
    private final Map<String, MethodCollector> methodCollectors = Maps.newHashMap();

    @Nullable
    String getSuperType() {
        return superType;
    }

    ImmutableSet<String> getInterfaceTypes() {
        return interfaceTypes;
    }

    ImmutableSet<String> getAllSuperTypes() {
        return allSuperTypes;
    }

    @Nullable
    MethodCollector getMethodCollector(String methodId) {
        return methodCollectors.get(methodId);
    }

    Set<String> getMethodIds() {
        return methodCollectors.keySet();
    }

    void setSuperType(@Nullable String superType) {
        this.superType = superType;
    }

    void setInterfaceTypes(String[] interfaceTypes) {
        this.interfaceTypes = ImmutableSet.copyOf(interfaceTypes);
    }

    void setAllSuperTypes(ImmutableSet<String> allSuperTypes) {
        this.allSuperTypes = allSuperTypes;
    }

    void addMethod(ReferencedMethod referencedMethod, MethodCollector methodCollector) {
        methodCollectors.put(referencedMethod.getName() + ":" + referencedMethod.getDesc(),
                methodCollector);
    }
}
