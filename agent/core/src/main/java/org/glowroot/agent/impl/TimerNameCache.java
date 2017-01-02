/*
 * Copyright 2012-2017 the original author or authors.
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
package org.glowroot.agent.impl;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.model.ImmutableTimerNameImpl;
import org.glowroot.agent.model.TimerNameImpl;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.weaving.Pointcut;

// used to ensure one instance per name so that pointer equality can be used instead of String
// equality
//
// also used to ensure @Pointcut timer name matches the timer name passed to TransactionService
public class TimerNameCache {

    private static final Logger logger = LoggerFactory.getLogger(TimerNameCache.class);

    private final LoadingCache<String, TimerNameImpl> names = CacheBuilder.newBuilder()
            .build(new CacheLoader<String, TimerNameImpl>() {
                @Override
                public TimerNameImpl load(String name) {
                    return ImmutableTimerNameImpl.of(name, false);
                }
            });

    private final TimerName unknownTimerName = names.getUnchecked("unknown");
    private final TimerName auxThreadTimerName = names.getUnchecked("auxiliary thread");

    public TimerName getTimerName(Class<?> adviceClass) {
        if (adviceClass == null) {
            logger.error("getTimerName(): argument 'adviceClass' must be non-null");
            return unknownTimerName;
        }
        Pointcut pointcut = adviceClass.getAnnotation(Pointcut.class);
        if (pointcut == null) {
            logger.warn("advice has no @Pointcut: {}", adviceClass.getName());
            return unknownTimerName;
        } else if (pointcut.timerName().isEmpty()) {
            logger.warn("advice @Pointcut has no timerName() attribute: {}", adviceClass.getName());
            return unknownTimerName;
        } else {
            return getName(pointcut.timerName());
        }
    }

    TimerName getTimerName(String name) {
        if (name == null) {
            logger.error("getTimerName(): argument 'name' must be non-null");
            return unknownTimerName;
        }
        return getName(name);
    }

    TimerName getAuxThreadTimerName() {
        return auxThreadTimerName;
    }

    private TimerName getName(String name) {
        return names.getUnchecked(name);
    }
}
