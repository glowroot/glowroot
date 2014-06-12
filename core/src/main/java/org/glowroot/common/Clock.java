/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.common;

import com.google.common.base.Ticker;

import org.glowroot.markers.ThreadSafe;

/**
 * Modeled after Guava's {@link Ticker} class, but for currentTimeMillis.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public abstract class Clock {

    private static final Clock SYSTEM_CLOCK = new Clock() {
        @Override
        public long currentTimeMillis() {
            return System.currentTimeMillis();
        }
    };

    public abstract long currentTimeMillis();

    public static Clock systemClock() {
        return SYSTEM_CLOCK;
    }
}
