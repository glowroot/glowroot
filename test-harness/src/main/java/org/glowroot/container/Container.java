/*
 * Copyright 2011-2015 the original author or authors.
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
package org.glowroot.container;

import org.glowroot.container.admin.AdminService;
import org.glowroot.container.aggregate.AggregateService;
import org.glowroot.container.config.ConfigService;
import org.glowroot.container.trace.TraceService;

public interface Container {

    ConfigService getConfigService();

    void addExpectedLogMessage(String loggerName, String partialMessage) throws Exception;

    void executeAppUnderTest(Class<? extends AppUnderTest> appUnderTestClass) throws Exception;

    void interruptAppUnderTest() throws Exception;

    TraceService getTraceService();

    AggregateService getAggregateService();

    AdminService getAdminService();

    int getUiPort() throws Exception;

    // checks no unexpected log messages
    // checks no active traces
    // resets Glowroot back to square one
    void checkAndReset() throws Exception;

    void checkAndResetConfigOnly() throws Exception;

    void close() throws Exception;

    void close(boolean evenIfShared) throws Exception;

    @SuppressWarnings("serial")
    class StartupFailedException extends Exception {
        public StartupFailedException() {}
        public StartupFailedException(Throwable cause) {
            super(cause);
        }
    }
}
