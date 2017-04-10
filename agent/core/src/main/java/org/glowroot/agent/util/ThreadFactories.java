/*
 * Copyright 2017 the original author or authors.
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
package org.glowroot.agent.util;

import java.util.concurrent.ThreadFactory;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

public class ThreadFactories {

    private ThreadFactories() {}

    public static ThreadFactory create(String name) {
        final ThreadFactory backingThreadFactory = new ThreadFactoryBuilder()
                .setDaemon(true)
                .setNameFormat(name)
                .build();
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = backingThreadFactory.newThread(r);
                thread.setContextClassLoader(ThreadFactories.class.getClassLoader());
                return thread;
            }
        };
    }
}
