/**
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

import org.slf4j.LoggerFactory;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class SpyingAppenderCheck {

    private static final boolean spyingAppenderEnabled;

    static {
        spyingAppenderEnabled = spyingAppenderEnabled();
    }

    public static boolean isSpyingAppenderEnabled() {
        return spyingAppenderEnabled;
    }

    private static boolean spyingAppenderEnabled() {
        try {
            ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) LoggerFactory
                    .getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
            return root.getAppender(SpyingConsoleAppender.NAME) != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
