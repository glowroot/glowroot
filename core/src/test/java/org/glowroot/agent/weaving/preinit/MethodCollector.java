/*
 * Copyright 2012-2015 the original author or authors.
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
package org.glowroot.agent.weaving.preinit;

import java.util.Set;

import com.google.common.collect.Sets;
import org.objectweb.asm.commons.Remapper;

class MethodCollector extends Remapper {

    private final Set<String> referencedInternalNames = Sets.newHashSet();
    // referenced method is stored as "owner:name:desc"
    private final Set<ReferencedMethod> referencedMethods = Sets.newHashSet();

    @Override
    public String map(String internalName) {
        referencedInternalNames.add(internalName);
        return internalName;
    }

    Set<String> getReferencedInternalNames() {
        return referencedInternalNames;
    }

    Set<ReferencedMethod> getReferencedMethods() {
        return referencedMethods;
    }

    void addReferencedMethod(ReferencedMethod referencedMethod) {
        referencedMethods.add(referencedMethod);
    }
}
