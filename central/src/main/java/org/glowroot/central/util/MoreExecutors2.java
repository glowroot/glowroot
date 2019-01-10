/*
 * Copyright 2019 the original author or authors.
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
package org.glowroot.central.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

public class MoreExecutors2 {

    private MoreExecutors2() {}

    public static ExecutorService newSingleThreadExecutor(String nameFormat) {
        return Executors.newSingleThreadExecutor(newThreadFactory(nameFormat));
    }

    public static ScheduledExecutorService newSingleThreadScheduledExecutor(String nameFormat) {
        return Executors.newSingleThreadScheduledExecutor(newThreadFactory(nameFormat));
    }

    public static ExecutorService newFixedThreadPool(int nThreads, String nameFormat) {
        return Executors.newFixedThreadPool(nThreads, newThreadFactory(nameFormat));
    }

    public static ExecutorService newCachedThreadPool(String nameFormat) {
        return Executors.newCachedThreadPool(newThreadFactory(nameFormat));
    }

    public static ThreadFactory newThreadFactory(String nameFormat) {
        return new ThreadFactoryBuilder()
                .setNameFormat(nameFormat)
                .build();
    }
}
