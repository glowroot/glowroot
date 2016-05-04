/*
 * Copyright 2015-2016 the original author or authors.
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

import org.glowroot.agent.plugin.api.AsyncQueryEntry;
import org.glowroot.agent.plugin.api.AsyncTraceEntry;
import org.glowroot.agent.plugin.api.AuxThreadContext;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.QueryEntry;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.Timer;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;

public class NopTransactionService {

    private NopTransactionService() {}

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
        public void endWithInfo(Throwable t) {}

        @Override
        public @Nullable MessageSupplier getMessageSupplier() {
            return null;
        }
    }

    public static class NopQueryEntry extends NopTraceEntry implements QueryEntry {

        public static final NopQueryEntry INSTANCE = new NopQueryEntry();

        private NopQueryEntry() {}

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
        public void endWithInfo(Throwable t) {}

        @Override
        public @Nullable MessageSupplier getMessageSupplier() {
            return null;
        }

        @Override
        public void stopSyncTimer() {}

        @Override
        public Timer extendSyncTimer(ThreadContext currThreadContext) {
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

    public static class NopAuxThreadContext implements AuxThreadContext {

        public static final NopAuxThreadContext INSTANCE = new NopAuxThreadContext();

        private NopAuxThreadContext() {}

        @Override
        public TraceEntry start() {
            return NopTraceEntry.INSTANCE;
        }

        @Override
        public TraceEntry startAndMarkAsyncTransactionComplete() {
            return NopTraceEntry.INSTANCE;
        }
    }

    public static class NopTimer implements Timer {

        public static final NopTimer INSTANCE = new NopTimer();

        private NopTimer() {}

        @Override
        public void stop() {}

        @Override
        public Timer extend() {
            return INSTANCE;
        }
    }

    public static class NopTimerName implements TimerName {

        public static final NopTimerName INSTANCE = new NopTimerName();

        private NopTimerName() {}
    }
}
