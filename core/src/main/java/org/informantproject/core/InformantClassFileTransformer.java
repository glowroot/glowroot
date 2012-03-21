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
package org.informantproject.core;

import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.concurrent.Callable;

import org.informantproject.api.PluginServices;
import org.informantproject.shaded.aspectj.weaver.loadtime.ClassPreProcessorAgentAdapter;

import com.google.inject.Inject;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class InformantClassFileTransformer extends ClassPreProcessorAgentAdapter {

    private static final String WEAVING_SUMMARY_KEY = "informant weaving";

    private final PluginServices pluginServices;

    @Inject
    public InformantClassFileTransformer(PluginServices pluginServices) {
        this.pluginServices = pluginServices;
    }

    @Override
    public byte[] transform(final ClassLoader loader, final String className,
            final Class<?> classBeingRedefined, final ProtectionDomain protectionDomain,
            final byte[] bytes) throws IllegalClassFormatException {

        try {
            return pluginServices.proceedAndRecordMetricData(WEAVING_SUMMARY_KEY,
                    new Callable<byte[]>() {
                        public byte[] call() throws IllegalClassFormatException {
                            return InformantClassFileTransformer.super.transform(loader, className,
                                    classBeingRedefined, protectionDomain, bytes);
                        }
                    });
        } catch (IllegalClassFormatException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
