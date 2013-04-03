/**
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
package io.informant.container;

import java.io.Serializable;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import com.google.common.collect.Lists;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class SpyingConsoleAppender<E> extends ConsoleAppender<E> {

    public static final String NAME = "SPYING";

    private static final List<ExpectedMessage> expectedMessages = Lists.newCopyOnWriteArrayList();

    private static final AtomicInteger unexpectedMessageCount = new AtomicInteger();

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    protected void append(E eventObject) {
        if (!isExpected(eventObject)) {
            super.append(eventObject);
            unexpectedMessageCount.getAndIncrement();
        }
    }

    private boolean isExpected(E eventObject) {
        if (!(eventObject instanceof ILoggingEvent)) {
            return false;
        }
        ILoggingEvent event = (ILoggingEvent) eventObject;
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

    public static List<ExpectedMessage> getExpectedMessages() {
        return expectedMessages;
    }

    public static int getUnexpectedMessageCount() {
        return unexpectedMessageCount.get();
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
        public MessageCount(int expectedCount, int unexpectedCount) {
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
