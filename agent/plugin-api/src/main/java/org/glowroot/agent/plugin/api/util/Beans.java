/*
 * Copyright 2012-2018 the original author or authors.
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
package org.glowroot.agent.plugin.api.util;

import java.util.List;
import java.util.Map;

import org.glowroot.agent.plugin.api.checker.Nullable;
import org.glowroot.agent.plugin.api.internal.PluginService;
import org.glowroot.agent.plugin.api.internal.PluginServiceHolder;

public class Beans {

    private static final PluginService service = PluginServiceHolder.get();

    private Beans() {}

    public static @Nullable Object value(@Nullable Object obj, List<String> path) throws Exception {
        return service.getBeanValue(obj, path);
    }

    public static Map<String, String> propertiesAsText(Object obj) {
        return service.getBeanPropertiesAsText(obj);
    }
}
