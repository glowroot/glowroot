/*
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.container;

import java.util.Iterator;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.filter.Filter;
import org.slf4j.LoggerFactory;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class SpyingLogFilterCheck {

    private static final boolean spyingLogFilterEnabled;

    static {
        spyingLogFilterEnabled = spyingLogFilterEnabled();
    }

    private SpyingLogFilterCheck() {}

    public static boolean isSpyingLogFilterEnabled() {
        return spyingLogFilterEnabled;
    }

    private static boolean spyingLogFilterEnabled() {
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) LoggerFactory
                .getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        for (Iterator<Appender<ILoggingEvent>> i = root.iteratorForAppenders(); i.hasNext();) {
            for (Filter<ILoggingEvent> filter : i.next().getCopyOfAttachedFiltersList()) {
                if (filter instanceof SpyingLogFilter) {
                    return true;
                }
            }
        }
        return false;
    }
}
