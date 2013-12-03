/*
 * Copyright 2011-2013 the original author or authors.
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
package org.glowroot.testkit;

import checkers.nullness.quals.Nullable;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.glowroot.container.config.PluginConfig;
import org.glowroot.container.local.GenericLocalContainer;
import org.glowroot.container.local.GenericLocalContainer.AppExecutor;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class Container {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final GenericLocalContainer<AppUnderTest> container;

    public static Container create() throws Exception {
        return new Container();
    }

    private Container() throws Exception {
        container = new GenericLocalContainer<AppUnderTest>(null, false, false, AppUnderTest.class,
                AppUnderTestExecutor.INSTANCE);
        container.getConfigService().setStoreThresholdMillis(0);
    }

    public void setPluginProperty(String pluginId, String propertyName,
            @Nullable Object propertyValue) throws Exception {
        PluginConfig config = container.getConfigService().getPluginConfig(pluginId);
        if (config == null) {
            throw new IllegalStateException("Plugin not found for pluginId: " + pluginId);
        }
        config.setProperty(propertyName, propertyValue);
        container.getConfigService().updatePluginConfig(pluginId, config);
    }

    public void executeAppUnderTest(Class<? extends AppUnderTest> appUnderTestClass)
            throws Exception {
        container.executeAppUnderTest(appUnderTestClass);
    }

    @Nullable
    public Trace getLastTrace() throws Exception {
        return mapper.convertValue(container.getTraceService().getLastTrace(), Trace.class);
    }

    public void checkAndReset() throws Exception {
        container.checkAndReset();
        container.getConfigService().setStoreThresholdMillis(0);
    }

    public void close() throws Exception {
        container.close();
    }

    private static class AppUnderTestExecutor implements AppExecutor<AppUnderTest> {
        private static final AppUnderTestExecutor INSTANCE = new AppUnderTestExecutor();
        public void executeApp(AppUnderTest appUnderTest) throws Exception {
            appUnderTest.executeApp();
        }
    }
}
