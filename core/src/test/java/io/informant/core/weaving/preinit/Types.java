/**
 * Copyright 2012-2013 the original author or authors.
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

import io.informant.core.util.ThreadSafe;

import java.util.concurrent.ConcurrentMap;

import com.google.common.collect.Maps;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
class Types {

    private static final ConcurrentMap<String, Boolean> bootstrapTypes = Maps.newConcurrentMap();
    private static final ConcurrentMap<String, Boolean> allTypes = Maps.newConcurrentMap();

    static boolean inBootstrapClassLoader(String type) {
        Boolean cached = bootstrapTypes.get(type);
        if (cached == null) {
            cached = calculateIsBootstrapType(type);
            bootstrapTypes.put(type, cached);
        }
        return cached;
    }

    static boolean exists(String type) {
        Boolean cached = allTypes.get(type);
        if (cached == null) {
            cached = calculateExists(type);
            allTypes.put(type, cached);
        }
        return cached;
    }

    private static boolean calculateIsBootstrapType(String type) {
        try {
            return getType(type).getClassLoader() == null;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (NoClassDefFoundError e) {
            return false;
        }
    }

    private static boolean calculateExists(String type) {
        try {
            getType(type);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (NoClassDefFoundError e) {
            return false;
        }
    }

    private static Class<?> getType(String typeName) throws ClassNotFoundException {
        return Class.forName(typeName.replace('/', '.'), false, ClassLoader.getSystemClassLoader());
    }

    private Types() {}
}
