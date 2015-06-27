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
package org.glowroot.transaction;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.MapMaker;
import com.google.common.primitives.Ints;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.ErrorMessage;
import org.glowroot.api.MessageSupplier;
import org.glowroot.api.PluginServices;
import org.glowroot.api.PluginServices.ConfigListener;
import org.glowroot.api.QueryEntry;
import org.glowroot.api.Timer;
import org.glowroot.api.TimerName;
import org.glowroot.api.TraceEntry;
import org.glowroot.api.internal.ReadableErrorMessage;
import org.glowroot.common.Clock;
import org.glowroot.config.AdvancedConfig;
import org.glowroot.config.ConfigService;
import org.glowroot.config.GeneralConfig;
import org.glowroot.config.PluginConfig;
import org.glowroot.config.PluginDescriptor;
import org.glowroot.jvm.ThreadAllocatedBytes;
import org.glowroot.transaction.model.QueryData;
import org.glowroot.transaction.model.TimerImpl;
import org.glowroot.transaction.model.TimerNameImpl;
import org.glowroot.transaction.model.Transaction;
import org.glowroot.transaction.model.Transaction.CompletionCallback;

import static com.google.common.base.Preconditions.checkNotNull;

class PluginServicesImpl extends PluginServices implements ConfigListener {

    private static final Logger logger = LoggerFactory.getLogger(PluginServicesImpl.class);

    private final TransactionRegistry transactionRegistry;
    private final TransactionCollector transactionCollector;
    private final ConfigService configService;
    private final TimerNameCache timerNameCache;
    private final @Nullable ThreadAllocatedBytes threadAllocatedBytes;
    private final UserProfileScheduler userProfileScheduler;
    private final Clock clock;
    private final Ticker ticker;

    private final TransactionCompletionCallback transactionCompletionCallback =
            new TransactionCompletionCallback();

    // pluginId is either the id of a registered plugin or it is null
    // (see validation in constructor)
    private final @Nullable String pluginId;

    // cache for fast read access
    // visibility is provided by memoryBarrier below
    private boolean enabled;
    private boolean captureThreadInfo;
    private boolean captureGcInfo;
    private int maxAggregateQueriesPerQueryType;
    private int maxTraceEntriesPerTransaction;
    private @MonotonicNonNull PluginConfig pluginConfig;

    private final Map<ConfigListener, Boolean> weakConfigListeners =
            new MapMaker().weakKeys().makeMap();

    // memory barrier is used to ensure memory visibility of config values
    private volatile boolean memoryBarrier;

    static PluginServicesImpl create(TransactionRegistry transactionRegistry,
            TransactionCollector transactionCollector, ConfigService configService,
            TimerNameCache timerNameCache, @Nullable ThreadAllocatedBytes threadAllocatedBytes,
            UserProfileScheduler userProfileScheduler, Ticker ticker, Clock clock,
            List<PluginDescriptor> pluginDescriptors, @Nullable String pluginId) {
        PluginServicesImpl pluginServices = new PluginServicesImpl(transactionRegistry,
                transactionCollector, configService, timerNameCache, threadAllocatedBytes,
                userProfileScheduler, ticker, clock, pluginDescriptors, pluginId);
        if (pluginId != null) {
            configService.addPluginConfigListener(pluginId, pluginServices);
        }
        configService.addConfigListener(pluginServices);
        return pluginServices;
    }

    private PluginServicesImpl(TransactionRegistry transactionRegistry,
            TransactionCollector transactionCollector, ConfigService configService,
            TimerNameCache timerNameCache, @Nullable ThreadAllocatedBytes threadAllocatedBytes,
            UserProfileScheduler userProfileScheduler, Ticker ticker, Clock clock,
            List<PluginDescriptor> pluginDescriptors, @Nullable String pluginId) {
        this.transactionRegistry = transactionRegistry;
        this.transactionCollector = transactionCollector;
        this.configService = configService;
        this.timerNameCache = timerNameCache;
        this.threadAllocatedBytes = threadAllocatedBytes;
        this.userProfileScheduler = userProfileScheduler;
        this.clock = clock;
        this.ticker = ticker;
        if (pluginId == null) {
            this.pluginId = null;
        } else {
            PluginConfig pluginConfig = configService.getPluginConfig(pluginId);
            if (pluginConfig == null) {
                List<String> ids = Lists.newArrayList();
                for (PluginDescriptor pluginDescriptor : pluginDescriptors) {
                    ids.add(pluginDescriptor.id());
                }
                logger.warn("unexpected plugin id: {} (available plugin ids are {})", pluginId,
                        Joiner.on(", ").join(ids));
                this.pluginId = null;
            } else {
                this.pluginId = pluginId;
            }
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public StringProperty getStringProperty(String name) {
        if (name == null) {
            logger.error("getStringProperty(): argument 'name' must be non-null");
            return new StringPropertyImpl("");
        }
        StringPropertyImpl stringProperty = new StringPropertyImpl(name);
        weakConfigListeners.put(stringProperty, true);
        return stringProperty;
    }

    @Override
    public BooleanProperty getBooleanProperty(String name) {
        if (name == null) {
            logger.error("getBooleanProperty(): argument 'name' must be non-null");
            return new BooleanPropertyImpl("");
        }
        BooleanPropertyImpl booleanProperty = new BooleanPropertyImpl(name);
        weakConfigListeners.put(booleanProperty, true);
        return booleanProperty;
    }

    @Override
    public DoubleProperty getDoubleProperty(String name) {
        if (name == null) {
            logger.error("getDoubleProperty(): argument 'name' must be non-null");
            return new DoublePropertyImpl("");
        }
        DoublePropertyImpl doubleProperty = new DoublePropertyImpl(name);
        weakConfigListeners.put(doubleProperty, true);
        return doubleProperty;
    }

    @Override
    public BooleanProperty getEnabledProperty(String name) {
        if (name == null) {
            logger.error("getEnabledProperty(): argument 'name' must be non-null");
            return new BooleanPropertyImpl("");
        }
        EnabledPropertyImpl enabledProperty = new EnabledPropertyImpl(name);
        weakConfigListeners.put(enabledProperty, true);
        return enabledProperty;
    }

    @Override
    public void registerConfigListener(ConfigListener listener) {
        if (pluginId == null) {
            return;
        }
        if (listener == null) {
            logger.error("registerConfigListener(): argument 'listener' must be non-null");
            return;
        }
        configService.addPluginConfigListener(pluginId, listener);
        listener.onChange();
    }

    @Override
    public TimerName getTimerName(Class<?> adviceClass) {
        return timerNameCache.getName(adviceClass);
    }

    @Override
    public TraceEntry startTransaction(String transactionType, String transactionName,
            MessageSupplier messageSupplier, TimerName timerName) {
        if (transactionType == null) {
            logger.error("startTransaction(): argument 'transactionType' must be non-null");
            return NopTraceEntry.INSTANCE;
        }
        if (transactionName == null) {
            logger.error("startTransaction(): argument 'transactionName' must be non-null");
            return NopTraceEntry.INSTANCE;
        }
        if (messageSupplier == null) {
            logger.error("startTransaction(): argument 'messageSupplier' must be non-null");
            return NopTraceEntry.INSTANCE;
        }
        if (timerName == null) {
            logger.error("startTransaction(): argument 'timerName' must be non-null");
            return NopTraceEntry.INSTANCE;
        }
        // ensure visibility of recent configuration updates
        readMemoryBarrier();
        return startTransactionInternal(transactionType, transactionName, messageSupplier,
                timerName);
    }

    @Override
    public TraceEntry startTraceEntry(MessageSupplier messageSupplier, TimerName timerName) {
        if (messageSupplier == null) {
            logger.error("startTraceEntry(): argument 'messageSupplier' must be non-null");
            return NopTraceEntry.INSTANCE;
        }
        if (timerName == null) {
            logger.error("startTraceEntry(): argument 'timerName' must be non-null");
            return NopTraceEntry.INSTANCE;
        }
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction == null) {
            return NopTraceEntry.INSTANCE;
        }
        return startTraceEntryInternal(transaction, messageSupplier, null, null, 0, timerName);
    }

    @Override
    public QueryEntry startQueryEntry(String queryType, String queryText,
            MessageSupplier messageSupplier, TimerName timerName) {
        return startQueryEntry(queryType, queryText, 1, messageSupplier, timerName);
    }

    @Override
    public QueryEntry startQueryEntry(String queryType, String queryText, long queryExecutionCount,
            MessageSupplier messageSupplier, TimerName timerName) {
        if (queryType == null) {
            logger.error("startQuery(): argument 'queryType' must be non-null");
            return NopQuery.INSTANCE;
        }
        if (queryText == null) {
            logger.error("startQuery(): argument 'queryText' must be non-null");
            return NopQuery.INSTANCE;
        }
        if (messageSupplier == null) {
            logger.error("startQuery(): argument 'messageSupplier' must be non-null");
            return NopQuery.INSTANCE;
        }
        if (timerName == null) {
            logger.error("startQuery(): argument 'timerName' must be non-null");
            return NopQuery.INSTANCE;
        }
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction == null) {
            return NopQuery.INSTANCE;
        }
        return startTraceEntryInternal(transaction, messageSupplier, queryType, queryText,
                queryExecutionCount, timerName);
    }

    @Override
    public Timer startTimer(TimerName timerName) {
        if (timerName == null) {
            logger.error("startTimer(): argument 'timerName' must be non-null");
            return NopTimer.INSTANCE;
        }
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction == null) {
            return NopTimer.INSTANCE;
        }
        TimerImpl currentTimer = transaction.getCurrentTimer();
        if (currentTimer == null) {
            return NopTimer.INSTANCE;
        }
        return currentTimer.startNestedTimer(timerName);
    }

    @Override
    public void addTraceEntry(ErrorMessage errorMessage) {
        if (errorMessage == null) {
            logger.error("addTraceEntry(): argument 'errorMessage' must be non-null");
            return;
        }
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        // use higher entry limit when adding errors, but still need some kind of cap
        if (transaction != null
                && transaction.getEntryCount() < 2 * maxTraceEntriesPerTransaction) {
            long currTick = ticker.read();
            org.glowroot.transaction.model.TraceEntryImpl entry =
                    transaction.addEntry(currTick, currTick, null, errorMessage, true);
            if (((ReadableErrorMessage) errorMessage).getThrowable() == null) {
                StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
                // need to strip back a few stack calls:
                // skip i=0 which is "java.lang.Thread.getStackTrace()"
                // skip i=1 which is "...PluginServicesImpl.addTraceEntry()"
                // skip i=2 which is the plugin advice
                entry.setStackTrace(ImmutableList.copyOf(stackTrace).subList(3, stackTrace.length));
            }
        }
    }

    @Override
    public void setTransactionType(@Nullable String transactionType) {
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction != null) {
            transaction.setTransactionType(transactionType);
        }
    }

    @Override
    public void setTransactionName(@Nullable String transactionName) {
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction != null) {
            transaction.setTransactionName(transactionName);
        }
    }

    @Override
    public void setTransactionError(ErrorMessage errorMessage) {
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction != null) {
            transaction.setError(errorMessage);
        }
    }

    @Override
    public void setTransactionUser(@Nullable String user) {
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction != null && !Strings.isNullOrEmpty(user)) {
            transaction.setUser(user);
            if (transaction.getUserProfileRunnable() == null) {
                userProfileScheduler.maybeScheduleUserProfiling(transaction, user);
            }
        }
    }

    @Override
    public void addTransactionCustomAttribute(String name, @Nullable String value) {
        if (name == null) {
            logger.error("addTransactionCustomAttribute(): argument 'name' must be non-null");
            return;
        }
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction != null) {
            transaction.addCustomAttribute(name, value);
        }
    }

    @Override
    public void setTraceStoreThreshold(long threshold, TimeUnit unit) {
        if (threshold < 0) {
            logger.error("setTraceStoreThreshold(): argument 'threshold' must be non-negative");
            return;
        }
        if (unit == null) {
            logger.error("setTraceStoreThreshold(): argument 'unit' must be non-null");
            return;
        }
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction != null) {
            int thresholdMillis = Ints.saturatedCast(unit.toMillis(threshold));
            transaction.setTraceStoreThresholdMillisOverride(thresholdMillis);
        }
    }

    @Override
    public boolean isInTransaction() {
        return transactionRegistry.getCurrentTransaction() != null;
    }

    @Override
    public void onChange() {
        GeneralConfig generalConfig = configService.getGeneralConfig();
        if (pluginId == null) {
            enabled = generalConfig.enabled();
        } else {
            PluginConfig pluginConfig = configService.getPluginConfig(pluginId);
            // pluginConfig should not be null since pluginId was already validated
            // at construction time and plugins cannot be removed (or their ids changed) at runtime
            checkNotNull(pluginConfig);
            enabled = generalConfig.enabled() && pluginConfig.enabled();
            this.pluginConfig = pluginConfig;
        }
        AdvancedConfig advancedConfig = configService.getAdvancedConfig();
        maxAggregateQueriesPerQueryType = advancedConfig.maxAggregateQueriesPerQueryType();
        maxTraceEntriesPerTransaction = advancedConfig.maxTraceEntriesPerTransaction();
        captureThreadInfo = advancedConfig.captureThreadInfo();
        captureGcInfo = advancedConfig.captureGcInfo();

        for (ConfigListener weakConfigListener : weakConfigListeners.keySet()) {
            weakConfigListener.onChange();
        }
        memoryBarrier = true;
    }

    private TraceEntry startTransactionInternal(String transactionType, String transactionName,
            MessageSupplier messageSupplier, TimerName timerName) {
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction == null) {
            long startTick = ticker.read();
            transaction = new Transaction(clock.currentTimeMillis(), transactionType,
                    transactionName, messageSupplier, timerName, startTick, captureThreadInfo,
                    captureGcInfo, maxAggregateQueriesPerQueryType, threadAllocatedBytes,
                    transactionCompletionCallback, ticker);
            transactionRegistry.addTransaction(transaction);
            return transaction.getRootEntry();
        } else {
            return startTraceEntryInternal(transaction, messageSupplier, null, null, 0, timerName);
        }
    }

    private QueryEntry startTraceEntryInternal(Transaction transaction,
            MessageSupplier messageSupplier, @Nullable String queryType,
            @Nullable String queryText, long queryExecutionCount, TimerName timerName) {
        long startTick = ticker.read();
        if (transaction.getEntryCount() < maxTraceEntriesPerTransaction) {
            TimerImpl timer = startTimer(timerName, startTick, transaction);
            return transaction.pushEntry(startTick, messageSupplier, queryType, queryText,
                    queryExecutionCount, timer);
        }
        // split out to separate method so as not to affect inlining budget of common path
        return startDummyTraceEntry(transaction, timerName, messageSupplier, queryType, queryText,
                queryExecutionCount, startTick);
    }

    private QueryEntry startDummyTraceEntry(Transaction transaction, TimerName timerName,
            MessageSupplier messageSupplier, @Nullable String queryType,
            @Nullable String queryText, long queryExecutionCount, long startTick) {
        // the entry limit has been exceeded for this trace
        QueryData queryData = null;
        if (queryType != null && queryText != null) {
            queryData = transaction.getOrCreateQueryDataIfPossible(queryType, queryText);
        }
        transaction.addEntryLimitExceededMarkerIfNeeded();
        TimerImpl timer = startTimer(timerName, startTick, transaction);
        return new DummyTraceEntryOrQuery(timer, startTick, transaction, messageSupplier,
                queryData, queryExecutionCount);
    }

    private TimerImpl startTimer(TimerName timerName, long startTick, Transaction transaction) {
        TimerImpl currentTimer = transaction.getCurrentTimer();
        if (currentTimer == null) {
            // this really shouldn't happen as current timer should be non-null unless transaction
            // has completed
            return TimerImpl.createRootTimer(transaction, (TimerNameImpl) timerName);
        }
        return currentTimer.startNestedTimer(timerName, startTick);
    }

    private boolean readMemoryBarrier() {
        return memoryBarrier;
    }

    private class TransactionCompletionCallback implements CompletionCallback {

        @Override
        public void completed(Transaction transaction) {
            // send to trace collector before removing from trace registry so that trace
            // collector can cover the gap
            // (via TransactionCollectorImpl.getPendingCompleteTraces())
            // between removing the trace from the registry and storing it
            transactionCollector.onCompletedTransaction(transaction);
            transactionRegistry.removeTransaction(transaction);
        }
    }

    private class DummyTraceEntryOrQuery implements QueryEntry, Timer {

        private final TimerImpl timer;
        private final long startTick;
        private final Transaction transaction;
        private final MessageSupplier messageSupplier;

        // not volatile, so depends on memory barrier in Transaction for visibility
        private int selfNestingLevel;
        // only used by transaction thread
        private @MonotonicNonNull TimerImpl extendedTimer;

        // queryData, currRow and maxRow are only used by query entries
        private final @Nullable QueryData queryData;
        // row numbers start at 1
        private long currRow = -1;
        private long maxRow;

        public DummyTraceEntryOrQuery(TimerImpl timer, long startTick, Transaction transaction,
                MessageSupplier messageSupplier, @Nullable QueryData queryData,
                long queryExecutionCount) {
            this.timer = timer;
            this.startTick = startTick;
            this.transaction = transaction;
            this.messageSupplier = messageSupplier;
            this.queryData = queryData;
            if (queryData != null) {
                queryData.start(startTick, queryExecutionCount);
            }
        }

        @Override
        public void end() {
            endInternal(ticker.read());
        }

        @Override
        public void endWithStackTrace(long threshold, TimeUnit unit) {
            if (threshold < 0) {
                logger.error("endWithStackTrace(): argument 'threshold' must be non-negative");
                end();
                return;
            }
            endInternal(ticker.read());
        }

        @Override
        public void endWithError(ErrorMessage errorMessage) {
            if (errorMessage == null) {
                logger.error("endWithError(): argument 'errorMessage' must be non-null");
                // fallback to end() without error
                end();
                return;
            }
            long endTick = ticker.read();
            endInternal(endTick);
            // use higher entry limit when adding errors, but still need some kind of cap
            if (transaction.getEntryCount() < 2 * maxTraceEntriesPerTransaction) {
                // entry won't be nested properly, but at least the error will get captured
                transaction.addEntry(startTick, endTick, messageSupplier, errorMessage, true);
            }
        }

        private void endInternal(long endTick) {
            timer.end(endTick);
            if (queryData != null) {
                queryData.end(endTick);
            }
        }

        @Override
        public Timer extend() {
            if (selfNestingLevel++ == 0) {
                long currTick = ticker.read();
                extendedTimer = timer.extend(currTick);
                if (queryData != null) {
                    queryData.extend(currTick);
                }
            }
            return this;
        }

        // this is called for stopping an extension
        @Override
        public void stop() {
            // the timer interface for this class is only expose through return value of extend()
            if (--selfNestingLevel == 0) {
                long stopTick = ticker.read();
                checkNotNull(extendedTimer);
                extendedTimer.end(stopTick);
                if (queryData != null) {
                    queryData.end(stopTick);
                }
            }
        }

        @Override
        public MessageSupplier getMessageSupplier() {
            return messageSupplier;
        }

        @Override
        public void incrementCurrRow() {
            if (currRow == -1) {
                currRow = 1;
                maxRow = 1;
                if (queryData != null) {
                    // queryData can be null here if the aggregated query limit is exceeded
                    // (though typically query limit is larger than trace entry limit)
                    queryData.incrementRowCount(1);
                }
            } else if (currRow == maxRow) {
                currRow++;
                maxRow = currRow;
                if (queryData != null) {
                    // queryData can be null here if the aggregated query limit is exceeded
                    // (though typically query limit is larger than trace entry limit)
                    queryData.incrementRowCount(1);
                }
            } else {
                currRow++;
            }
        }

        @Override
        public void setCurrRow(long row) {
            if (row > maxRow) {
                if (queryData != null) {
                    // queryData can be null here if the query limit is exceeded
                    // (though typically query limit is larger than trace entry limit)
                    queryData.incrementRowCount(row - maxRow);
                }
                maxRow = row;
            }
            currRow = row;
        }
    }

    private class StringPropertyImpl implements StringProperty, ConfigListener {
        private final String name;
        // visibility is provided by memoryBarrier in outer class
        private String value = "";
        private StringPropertyImpl(String name) {
            this.name = name;
            if (pluginConfig != null) {
                value = pluginConfig.getStringProperty(name);
            }
        }
        @Override
        public String value() {
            return value;
        }
        @Override
        public void onChange() {
            if (pluginConfig != null) {
                value = pluginConfig.getStringProperty(name);
            }
        }
    }

    private class BooleanPropertyImpl implements BooleanProperty, ConfigListener {
        private final String name;
        // visibility is provided by memoryBarrier in outer class
        private boolean value;
        private BooleanPropertyImpl(String name) {
            this.name = name;
            if (pluginConfig != null) {
                value = pluginConfig.getBooleanProperty(name);
            }
        }
        @Override
        public boolean value() {
            return value;
        }
        @Override
        public void onChange() {
            if (pluginConfig != null) {
                value = pluginConfig.getBooleanProperty(name);
            }
        }
    }

    private class DoublePropertyImpl implements DoubleProperty, ConfigListener {
        private final String name;
        // visibility is provided by memoryBarrier in outer class
        private @Nullable Double value;
        private DoublePropertyImpl(String name) {
            this.name = name;
            if (pluginConfig != null) {
                value = pluginConfig.getDoubleProperty(name);
            }
        }
        @Override
        public @Nullable Double value() {
            return value;
        }
        @Override
        public void onChange() {
            if (pluginConfig != null) {
                value = pluginConfig.getDoubleProperty(name);
            }
        }
    }

    private class EnabledPropertyImpl implements BooleanProperty, ConfigListener {
        private final String name;
        // visibility is provided by memoryBarrier in outer class
        private boolean value;
        private EnabledPropertyImpl(String name) {
            this.name = name;
            if (pluginConfig != null) {
                value = enabled && pluginConfig.getBooleanProperty(name);
            }
        }
        @Override
        public boolean value() {
            return value;
        }
        @Override
        public void onChange() {
            if (pluginConfig != null) {
                value = enabled && pluginConfig.getBooleanProperty(name);
            }
        }
    }

    // TODO remove this class so TraceEntry can't go megamorphic
    private static class NopTraceEntry implements TraceEntry {
        private static final NopTraceEntry INSTANCE = new NopTraceEntry();
        private NopTraceEntry() {}
        @Override
        public void end() {}
        @Override
        public void endWithStackTrace(long threshold, TimeUnit unit) {}
        @Override
        public void endWithError(ErrorMessage errorMessage) {}
        @Override
        public Timer extend() {
            return NopTimer.INSTANCE;
        }
        @Override
        public @Nullable MessageSupplier getMessageSupplier() {
            return null;
        }
    }

    private static class NopQuery extends NopTraceEntry implements QueryEntry {
        private static final NopQuery INSTANCE = new NopQuery();
        private NopQuery() {}
        @Override
        public void incrementCurrRow() {}
        @Override
        public void setCurrRow(long row) {}
    }

    private static class NopTimer implements Timer {
        private static final NopTimer INSTANCE = new NopTimer();
        @Override
        public void stop() {}
    }
}
