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
import java.util.concurrent.Executors;

import javax.annotation.Nullable;

import com.google.common.base.Stopwatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.SECONDS;

public class NettyWorkaround {

    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    private NettyWorkaround() {}

    public static void run(final @Nullable Instrumentation instrumentation,
            final NettyInit doNettyInit) throws Exception {
        if (instrumentation == null) {
            doNettyInit.execute(false);
        } else {
            // cannot start netty in premain otherwise can crash JVM, at least using Java 1.8.0_25
            // on Windows (though fixed now in Java 1.8.0_91)
            //
            // see https://github.com/netty/netty/issues/3233
            // and https://bugs.openjdk.java.net/browse/JDK-8041920
            // also see repro for the issue in MethodHandleRelatedCrashIT.java
            Executors.newSingleThreadExecutor().execute(new Runnable() {
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
        void execute(boolean newThread) throws Exception;
    }
}
