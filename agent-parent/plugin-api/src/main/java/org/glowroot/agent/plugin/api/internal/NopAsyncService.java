/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.agent.plugin.api.internal;

import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopTimer;
import org.glowroot.agent.plugin.api.internal.NopTransactionService.NopTraceEntry;
import org.glowroot.agent.plugin.api.transaction.AsyncQueryEntry;
import org.glowroot.agent.plugin.api.transaction.AsyncService;
import org.glowroot.agent.plugin.api.transaction.AsyncTraceEntry;
import org.glowroot.agent.plugin.api.transaction.MessageSupplier;
import org.glowroot.agent.plugin.api.transaction.ThreadContext;
import org.glowroot.agent.plugin.api.transaction.Timer;
import org.glowroot.agent.plugin.api.transaction.TimerName;
import org.glowroot.agent.plugin.api.transaction.TraceEntry;

public class NopAsyncService implements AsyncService {

    public static final AsyncService INSTANCE = new NopAsyncService();

    private NopAsyncService() {}

    @Override
    public AsyncQueryEntry startAsyncQueryEntry(String queryType, String queryText,
            MessageSupplier messageSupplier, TimerName syncTimerName, TimerName asyncTimerName) {
        return NopAsyncQueryEntry.INSTANCE;
    }

    @Override
    public AsyncTraceEntry startAsyncTraceEntry(MessageSupplier messageSupplier,
            TimerName syncTimerName, TimerName asyncTimerName) {
        return NopAsyncTraceEntry.INSTANCE;
    }

    public static class NopAsyncTraceEntry implements AsyncTraceEntry {

        public static final NopAsyncTraceEntry INSTANCE = new NopAsyncTraceEntry();

        private NopAsyncTraceEntry() {}

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
        public @Nullable MessageSupplier getMessageSupplier() {
            return null;
        }

        @Override
        public void stopSyncTimer() {}

        @Override
        public Timer extendSyncTimer() {
            return NopTimer.INSTANCE;
        }
    }

    public static class NopAsyncQueryEntry extends NopAsyncTraceEntry implements AsyncQueryEntry {

        public static final NopAsyncQueryEntry INSTANCE = new NopAsyncQueryEntry();

        private NopAsyncQueryEntry() {}

        @Override
        public Timer extend() {
            return NopTimer.INSTANCE;
        }

        @Override
        public void rowNavigationAttempted() {}

        @Override
        public void incrementCurrRow() {}

        @Override
        public void setCurrRow(long row) {}
    }

    public static class NopAsyncContext implements ThreadContext {

        public static final NopAsyncContext INSTANCE = new NopAsyncContext();

        private NopAsyncContext() {}

        @Override
        public TraceEntry start() {
            return NopTraceEntry.INSTANCE;
        }
    }
}
