/*
 * Copyright 2011-2017 the original author or authors.
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
package org.glowroot.agent.it.harness;

import org.glowroot.wire.api.model.TraceOuterClass.Trace;

public interface Container {

    ConfigService getConfigService();

    void addExpectedLogMessage(String loggerName, String partialMessage) throws Exception;

    Trace execute(Class<? extends AppUnderTest> appUnderTestClass) throws Exception;

    Trace execute(Class<? extends AppUnderTest> appUnderTestClass, String transactionType)
            throws Exception;

    void executeNoExpectedTrace(Class<? extends AppUnderTest> appUnderTestClass) throws Exception;

    void interruptAppUnderTest() throws Exception;

    Trace getCollectedPartialTrace() throws Exception;

    // checks no unexpected log messages
    // checks no active traces
    // resets Glowroot back to square one (including re-weaving instrumentation configs if needed)
    void checkAndReset() throws Exception;

    void close() throws Exception;
}
