/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.plugin.api.internal;

import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.glowroot.plugin.api.transaction.MessageSupplier;
import org.glowroot.plugin.api.transaction.QueryEntry;
import org.glowroot.plugin.api.transaction.Timer;
import org.glowroot.plugin.api.transaction.TimerName;
import org.glowroot.plugin.api.transaction.TraceEntry;
import org.glowroot.plugin.api.transaction.TransactionService;

public class NopTransactionService implements TransactionService {

    public static final TransactionService INSTANCE = new NopTransactionService();

    @Override
    public TimerName getTimerName(Class<?> adviceClass) {
        return NopTimerName.INSTANCE;
    }

    @Override
    public TraceEntry startTransaction(String transactionType, String transactionName,
            MessageSupplier messageSupplier, TimerName timerName) {
        return NopTraceEntry.INSTANCE;
    }

    @Override
    public TraceEntry startTraceEntry(MessageSupplier messageSupplier, TimerName timerName) {
        return NopTraceEntry.INSTANCE;
    }

    @Override
    public QueryEntry startQueryEntry(String queryType, String queryText,
            MessageSupplier messageSupplier, TimerName timerName) {
        return NopQueryEntry.INSTANCE;
    }

    @Override
    public QueryEntry startQueryEntry(String queryType, String queryText, long queryExecutionCount,
            MessageSupplier messageSupplier, TimerName timerName) {
        return NopQueryEntry.INSTANCE;
    }

    @Override
    public Timer startTimer(TimerName timerName) {
        return NopTimer.INSTANCE;
    }

    @Override
    public void addErrorEntry(Throwable t) {}

    @Override
    public void addErrorEntry(@Nullable String message) {}

    @Override
    public void addErrorEntry(@Nullable String message, Throwable t) {}

    @Override
    public void setTransactionType(@Nullable String transactionType) {}

    @Override
    public void setTransactionName(@Nullable String transactionName) {}

    @Override
    public void setTransactionError(@Nullable Throwable t) {}

    @Override
    public void setTransactionError(@Nullable String message) {}

    @Override
    public void setTransactionError(@Nullable String message, @Nullable Throwable t) {}

    @Override
    public void setTransactionUser(@Nullable String user) {}

    @Override
    public void addTransactionCustomAttribute(String name, @Nullable String value) {}

    @Override
    public void setTransactionSlowThreshold(long threshold, TimeUnit unit) {}

    @Override
    public boolean isInTransaction() {
        return false;
    }

    public static class NopTraceEntry implements TraceEntry {

        public static final NopTraceEntry INSTANCE = new NopTraceEntry();

        private NopTraceEntry() {}

        @Override
        public void end() {}

        @Override
        public void endWithStackTrace(long threshold, TimeUnit unit) {}

        @Override
        public void endWithError(Throwable t) {}

        @Override
        public void endWithError(@Nullable String message) {}

        @Override
        public void endWithError(@Nullable String message, Throwable t) {}

        @Override
        public Timer extend() {
            return NopTimer.INSTANCE;
        }

        @Override
        public @Nullable MessageSupplier getMessageSupplier() {
            return null;
        }
    }

    public static class NopQueryEntry extends NopTraceEntry implements QueryEntry {

        public static final NopQueryEntry INSTANCE = new NopQueryEntry();

        private NopQueryEntry() {}

        @Override
        public void incrementCurrRow() {}

        @Override
        public void setCurrRow(long row) {}
    }

    public static class NopTimer implements Timer {

        public static final NopTimer INSTANCE = new NopTimer();

        @Override
        public void stop() {}
    }

    private static class NopTimerName implements TimerName {

        private static final NopTimerName INSTANCE = new NopTimerName();
    }
}
