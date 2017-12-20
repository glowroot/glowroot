/*
 * Copyright 2015-2017 the original author or authors.
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
package org.glowroot.agent.init;

import java.lang.instrument.Instrumentation;

import javax.annotation.Nullable;

import com.google.common.base.Stopwatch;
import io.netty.util.internal.PlatformDependent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.util.AppServerDetection;
import org.glowroot.agent.util.JavaVersion;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.SECONDS;

public class NettyWorkaround {

    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    private NettyWorkaround() {}

    public static void run(final @Nullable Instrumentation instrumentation,
            final NettyInit doNettyInit) throws Exception {
        if (AppServerDetection.isIbmJvm()) {
            // WebSphere crashes on startup without this workaround (at least when pointing to
            // glowroot central and using WebSphere 8.5.5.11)
            String prior = System.getProperty("io.netty.noUnsafe");
            System.setProperty("io.netty.noUnsafe", "true");
            try {
                if (PlatformDependent.hasUnsafe()) {
                    throw new IllegalStateException("Netty property to disable usage of UNSAFE was"
                            + " not set early enough, please report to the Glowroot project");
                }
            } finally {
                if (prior == null) {
                    System.clearProperty("io.netty.noUnsafe");
                } else {
                    System.setProperty("io.netty.noUnsafe", prior);
                }
            }
        }
        if (instrumentation == null || JavaVersion.isGreaterThanOrEqualToJava9()) {
            doNettyInit.execute(false);
        } else {
            // cannot start netty in premain otherwise can crash JVM, at least using Java 1.8.0_25
            // on Windows (though fixed now in Java 1.8.0_91)
            //
            // see https://github.com/netty/netty/issues/3233
            // and https://bugs.openjdk.java.net/browse/JDK-8041920
            // also see repro for the issue in MethodHandleRelatedCrashIT.java
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        checkNotNull(instrumentation);
                        waitForMain(instrumentation);
                        doNettyInit.execute(true);
                    } catch (Throwable t) {
                        startupLogger.error(t.getMessage(), t);
                    }
                }
            });
            thread.setName("Glowroot-Init-UI");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private static void waitForMain(Instrumentation instrumentation) throws InterruptedException {
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (stopwatch.elapsed(SECONDS) < 60) {
            Thread.sleep(100);
            for (Class<?> clazz : instrumentation.getInitiatedClasses(null)) {
                if (clazz.getName().equals("sun.misc.Launcher")) {
                    return;
                }
            }
        }
        // something has gone wrong
        startupLogger.error("sun.misc.Launcher was never loaded");
    }

    public interface NettyInit {
        void execute(boolean alreadyInsideNewThread) throws Exception;
    }
}
