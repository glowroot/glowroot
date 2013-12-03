/*
 * Copyright 2013 the original author or authors.
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

import java.io.Serializable;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import com.google.common.collect.Lists;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class SpyingLogFilter extends Filter<ILoggingEvent> {

    private static final List<ExpectedMessage> expectedMessages = Lists.newCopyOnWriteArrayList();

    private static final AtomicInteger unexpectedMessageCount = new AtomicInteger();

    @Override
    public String getName() {
        return "SPYING";
    }

    @Override
    public FilterReply decide(ILoggingEvent event) {
        if (!isExpected(event)) {
            if (event.getLevel().isGreaterOrEqual(Level.WARN)) {
                unexpectedMessageCount.getAndIncrement();
            }
            return FilterReply.NEUTRAL;
        } else {
            // this is expected, so don't log it
            return FilterReply.DENY;
        }
    }

    private boolean isExpected(ILoggingEvent event) {
        synchronized (expectedMessages) {
            if (expectedMessages.isEmpty()) {
                return false;
            }
            ExpectedMessage expectedMessage = expectedMessages.get(0);
            if (event.getMessage() == null) {
                return false;
            }
            if (expectedMessage.loggerName.equals(event.getLoggerName())
                    && event.getFormattedMessage().contains(expectedMessage.partialMessage)) {
                expectedMessages.remove(0);
                return true;
            }
            return false;
        }
    }

    public static void addExpectedMessage(String loggerName, String partialMessage) {
        expectedMessages.add(new ExpectedMessage(loggerName, partialMessage));
    }

    public static MessageCount clearMessages() {
        MessageCount counts = new MessageCount(expectedMessages.size(),
                unexpectedMessageCount.get());
        expectedMessages.clear();
        unexpectedMessageCount.set(0);
        return counts;
    }

    @SuppressWarnings("serial")
    public static class MessageCount implements Serializable {
        private final int expectedCount;
        private final int unexpectedCount;
        private MessageCount(int expectedCount, int unexpectedCount) {
            this.expectedCount = expectedCount;
            this.unexpectedCount = unexpectedCount;
        }
        public int getExpectedCount() {
            return expectedCount;
        }
        public int getUnexpectedCount() {
            return unexpectedCount;
        }
    }

    private static class ExpectedMessage {
        private final String loggerName;
        private final String partialMessage;
        private ExpectedMessage(String loggerName, String partialMessage) {
            this.loggerName = loggerName;
            this.partialMessage = partialMessage;
        }
    }
}
