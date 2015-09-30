/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.agent.core.util;

import java.io.Serializable;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import com.google.common.collect.Lists;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.common.util.Styles;

import static com.google.common.base.Preconditions.checkNotNull;

// this is needed in glowroot-core so that the references to logback classes will be shaded whenever
// glowroot-core is shaded
@OnlyUsedByTests
public class SpyingLogbackFilter extends Filter<ILoggingEvent> {

    private final List<ExpectedMessage> expectedMessages = Lists.newCopyOnWriteArrayList();

    private final AtomicInteger unexpectedMessageCount = new AtomicInteger();

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
            if (expectedMessage.loggerName().equals(event.getLoggerName())
                    && event.getFormattedMessage().contains(expectedMessage.partialMessage())) {
                expectedMessages.remove(0);
                return true;
            }
            return false;
        }
    }

    public static void init() {
        Appender<ILoggingEvent> consoleAppender = getConsoleAppender();
        SpyingLogbackFilter spyingLogbackFilter = getSpyingLogbackFilter(consoleAppender);
        if (spyingLogbackFilter == null) {
            consoleAppender.addFilter(new SpyingLogbackFilter());
        }
    }

    public static boolean active() {
        return getSpyingLogbackFilter() != null;
    }

    public static void addExpectedMessage(String loggerName, String partialMessage) {
        SpyingLogbackFilter spyingLogbackFilter = getSpyingLogbackFilter();
        checkNotNull(spyingLogbackFilter, "SpyingLogbackFilter.init() was never called");
        spyingLogbackFilter.expectedMessages
                .add(ImmutableExpectedMessage.of(loggerName, partialMessage));
    }

    public static MessageCount clearMessages() {
        SpyingLogbackFilter spyingLogbackFilter = getSpyingLogbackFilter();
        checkNotNull(spyingLogbackFilter, "SpyingLogbackFilter.init() was never called");
        MessageCount counts = ImmutableMessageCount.of(spyingLogbackFilter.expectedMessages.size(),
                spyingLogbackFilter.unexpectedMessageCount.get());
        spyingLogbackFilter.expectedMessages.clear();
        spyingLogbackFilter.unexpectedMessageCount.set(0);
        return counts;
    }

    private static @Nullable SpyingLogbackFilter getSpyingLogbackFilter() {
        Appender<ILoggingEvent> consoleAppender = getConsoleAppender();
        return getSpyingLogbackFilter(consoleAppender);
    }

    private static Appender<ILoggingEvent> getConsoleAppender() {
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        for (Iterator<Appender<ILoggingEvent>> i = root.iteratorForAppenders(); i.hasNext();) {
            Appender<ILoggingEvent> appender = i.next();
            if (appender instanceof ConsoleAppender) {
                return appender;
            }
        }
        throw new IllegalStateException("No console appender found");
    }

    private static @Nullable SpyingLogbackFilter getSpyingLogbackFilter(
            Appender<ILoggingEvent> appender) {
        for (Filter<ILoggingEvent> filter : appender.getCopyOfAttachedFiltersList()) {
            if (filter instanceof SpyingLogbackFilter) {
                return (SpyingLogbackFilter) filter;
            }
        }
        return null;
    }

    @Value.Immutable
    @Styles.AllParameters
    public interface MessageCount extends Serializable {
        int expectedCount();
        int unexpectedCount();
    }

    @Value.Immutable
    @Styles.AllParameters
    interface ExpectedMessage {
        String loggerName();
        String partialMessage();
    }
}
