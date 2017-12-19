/*
 * Copyright 2012-2017 the original author or authors.
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

import java.util.concurrent.ConcurrentMap;

import com.google.common.collect.Maps;

class InternalNames {

    private static final ConcurrentMap<String, Boolean> bootstrapInternalNames =
            Maps.newConcurrentMap();
    private static final ConcurrentMap<String, Boolean> allInternalNames = Maps.newConcurrentMap();

    private InternalNames() {}

    static boolean inBootstrapClassLoader(String internalName) {
        if (internalName.startsWith("java/sql/")) {
            // this is to mimic Java 9, where java.sql is not in the bootstrap class loader
            return false;
        }
        Boolean cached = bootstrapInternalNames.get(internalName);
        if (cached == null) {
            cached = calculateIsBootstrapClass(internalName);
            bootstrapInternalNames.put(internalName, cached);
        }
        return cached;
    }

    static boolean exists(String internalName) {
        Boolean cached = allInternalNames.get(internalName);
        if (cached == null) {
            cached = calculateExists(internalName);
            allInternalNames.put(internalName, cached);
        }
        return cached;
    }

    private static boolean calculateIsBootstrapClass(String internalName) {
        try {
            return classForInternalName(internalName).getClassLoader() == null;
        } catch (Throwable e) {
            // need Throwable to catch NoClassDefFoundError which extends Error
            return false;
        }
    }

    private static boolean calculateExists(String internalName) {
        try {
            classForInternalName(internalName);
            return true;
        } catch (Throwable e) {
            // need Throwable to catch NoClassDefFoundError which extends Error
            return false;
        }
    }

    private static Class<?> classForInternalName(String internalName)
            throws ClassNotFoundException {
        return Class.forName(internalName.replace('/', '.'), false,
                ClassLoader.getSystemClassLoader());
    }
}
