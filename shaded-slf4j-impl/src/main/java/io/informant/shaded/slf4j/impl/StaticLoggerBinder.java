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
package io.informant.shaded.slf4j.impl;

import io.informant.shaded.slf4j.ILoggerFactory;
import io.informant.shaded.slf4j.spi.LoggerFactoryBinder;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class StaticLoggerBinder implements LoggerFactoryBinder {

    // to avoid constant folding by the compiler, this field must *not* be final
    public static String REQUESTED_API_VERSION = "1.6";

    private static StaticLoggerBinder SINGLETON = new StaticLoggerBinder();

    private StaticLoggerBinder() {}

    public ILoggerFactory getLoggerFactory() {
        if (Configuration.useUnshadedSlf4j()) {
            return new UnshadedLoggerFactoryAdapter(org.slf4j.impl.StaticLoggerBinder
                    .getSingleton().getLoggerFactory());
        } else {
            return new ExtraShadedLoggerFactoryAdapter(
                    io.informant.shaded.slf4jx.impl.StaticLoggerBinder.getSingleton()
                            .getLoggerFactory());
        }
    }

    public String getLoggerFactoryClassStr() {
        if (Configuration.useUnshadedSlf4j()) {
            return org.slf4j.impl.StaticLoggerBinder.getSingleton().getLoggerFactoryClassStr();
        } else {
            return io.informant.shaded.slf4jx.impl.StaticLoggerBinder.getSingleton()
                    .getLoggerFactoryClassStr();
        }
    }

    public static StaticLoggerBinder getSingleton() {
        return SINGLETON;
    }
}
