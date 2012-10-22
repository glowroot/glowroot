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
package io.informant.test;

import static org.fest.assertions.api.Assertions.assertThat;
import io.informant.api.Logger;
import io.informant.api.LoggerFactory;
import io.informant.testkit.AppUnderTest;
import io.informant.testkit.InformantContainer;
import io.informant.testkit.LogMessage;
import io.informant.testkit.LogMessage.Level;
import io.informant.testkit.Trace.CapturedException;

import java.util.List;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.google.common.collect.ImmutableList;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class LogMessageTest {

    private static InformantContainer container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = InformantContainer.create();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @After
    public void afterEachTest() throws Exception {
        container.getInformant().cleanUpAfterEachTest();
    }

    @Test
    public void shouldReadLogMessage() throws Exception {
        // given
        container.getInformant().setPersistenceThresholdMillis(0);
        // when
        container.executeAppUnderTest(GenerateLogMessage.class);
        // then
        long startAt = System.currentTimeMillis();
        List<LogMessage> messages = ImmutableList.of();
        // log messages are stored asynchronously, so may need to wait a little
        while (messages.isEmpty() && System.currentTimeMillis() - startAt < 5000) {
            messages = container.getInformant().getLogMessages();
            Thread.sleep(10);
        }
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(messages.get(0).getLoggerName()).isEqualTo(GenerateLogMessage.class.getName());
        assertThat(messages.get(0).getText()).isEqualTo("a warning from app under test");
        CapturedException exception = messages.get(0).getException();
        assertThat(exception).isNotNull();
        assertThat(exception.getDisplay()).isEqualTo("java.lang.IllegalStateException: Ex msg");
        CapturedException cause = exception.getCause();
        assertThat(cause).isNotNull();
        assertThat(cause.getDisplay()).isEqualTo("java.lang.IllegalArgumentException: Cause 3");
        assertThat(cause.getFramesInCommonWithCaused()).isEqualTo(
                exception.getStackTrace().size() - 1);
        cause = cause.getCause();
        assertThat(cause).isNotNull();
        assertThat(cause.getDisplay()).isEqualTo("java.lang.RuntimeException: Cause 2");
        assertThat(cause.getFramesInCommonWithCaused()).isEqualTo(
                exception.getStackTrace().size() - 1);
        cause = cause.getCause();
        assertThat(cause).isNotNull();
        assertThat(cause.getDisplay()).isEqualTo("java.lang.IllegalStateException: Cause 1");
        assertThat(cause.getFramesInCommonWithCaused()).isEqualTo(
                exception.getStackTrace().size() - 1);
        assertThat(cause.getCause()).isNull();
        container.getInformant().deleteAllLogMessages();
    }

    public static class GenerateLogMessage implements AppUnderTest {
        private static final Logger logger = LoggerFactory.getLogger(GenerateLogMessage.class);
        public void executeApp() throws Exception {
            Exception causeException1 = new IllegalStateException("Cause 1");
            Exception causeException2 = new RuntimeException("Cause 2", causeException1);
            Exception causeException3 = new IllegalArgumentException("Cause 3", causeException2);
            logger.warn("a warning from app under test", new IllegalStateException("Ex msg",
                    causeException3));
        }
    }
}
