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
package io.informant.core.util;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.List;

import com.google.common.io.Resources;

/**
 * Additional method similar to those in Guava's {@link Resources} class.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public final class Resources2 {

    public static List<URL> getResources(String resourceName) throws IOException {
        ClassLoader loader = Resources2.class.getClassLoader();
        if (loader == null) {
            // highly unlikely that this class is loaded by the bootstrap class loader,
            // but handling anyways
            return Collections.list(ClassLoader.getSystemResources(resourceName));
        }
        return Collections.list(loader.getResources(resourceName));
    }

    private Resources2() {}
}
