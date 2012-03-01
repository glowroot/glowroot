/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.core.util;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

/**
 * Convenience methods for creating {@link ExecutorService}s.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public final class DaemonExecutors {

    private static final String NAME_COUNTER_SUFFIX = "-%d";

    private DaemonExecutors() {}

    public static ExecutorService newCachedThreadPool(String name) {
        return Executors.newCachedThreadPool(new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat(name + NAME_COUNTER_SUFFIX)
                .setUncaughtExceptionHandler(new ExceptionHandler())
                .build());
    }

    public static ExecutorService newSingleThreadExecutor(String name) {
        return Executors.newSingleThreadExecutor(new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat(name + NAME_COUNTER_SUFFIX)
                .setUncaughtExceptionHandler(new ExceptionHandler())
                .build());
    }

    public static ScheduledExecutorService newSingleThreadScheduledExecutor(String name) {
        return Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat(name + NAME_COUNTER_SUFFIX)
                .setUncaughtExceptionHandler(new ExceptionHandler())
                .build());
    }

    public static ScheduledExecutorService newScheduledThreadPool(int corePoolSize, String name) {
        return Executors.newScheduledThreadPool(corePoolSize, new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat(name + NAME_COUNTER_SUFFIX)
                .setUncaughtExceptionHandler(new ExceptionHandler())
                .build());
    }

    private static class ExceptionHandler implements UncaughtExceptionHandler {
        private static final Logger logger = LoggerFactory.getLogger(ExceptionHandler.class);
        public void uncaughtException(Thread t, Throwable e) {
            logger.error(e.getMessage(), e);
        }
    }
}
