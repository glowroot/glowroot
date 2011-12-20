/**
 * Copyright 2011 the original author or authors.
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
package org.informantproject;

import java.lang.instrument.Instrumentation;

import org.informantproject.api.PluginServices;
import org.informantproject.shaded.aspectj.weaver.loadtime.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Guice;
import com.google.inject.Injector;

/**
 * This class is registered as the Premain-Class in the MANIFEST.MF of informant.jar:
 * 
 * Premain-Class: org.informantproject.MainEntryPoint
 * 
 * This defines the entry point when the JVM is launched via -javaagent:informant.jar. This class
 * starts various background threads and then starts the AspectJ load-time weaving agent.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public final class MainEntryPoint {

    private static final Logger logger = LoggerFactory.getLogger(MainEntryPoint.class);

    private static final PluginServicesProxy pluginServicesProxy = new PluginServicesProxy();
    private static volatile Injector injector;
    private static final Object lock = new Object();

    private MainEntryPoint() {}

    public static void premain(String options, Instrumentation instrumentation) {
        logger.debug("premain(): options={}", options);
        start(options);
        // start the AspectJ load-time weaving agent
        Agent.premain(null, instrumentation);
    }

    public static PluginServices getPluginServices() {
        return pluginServicesProxy;
    }

    public static void start() {
        start(null);
    }

    public static void start(String options) {
        logger.debug("start(): classLoader={}", MainEntryPoint.class.getClassLoader());
        synchronized (lock) {
            if (injector != null) {
                throw new IllegalStateException("Informant is already started");
            }
            CommandLineOptions commandLineOptions = new CommandLineOptions(options);
            injector = Guice.createInjector(new InformantModule(commandLineOptions));
            InformantModule.start(injector);
            pluginServicesProxy.start(injector.getInstance(PluginServicesImpl.class));
        }
    }

    public static void shutdown() {
        logger.debug("shutdown()");
        synchronized (lock) {
            if (injector == null) {
                throw new IllegalStateException("Informant is not started");
            }
            pluginServicesProxy.shutdown();
            InformantModule.shutdown(injector);
            injector = null;
        }
    }
}
