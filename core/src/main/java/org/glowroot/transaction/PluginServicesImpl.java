/*
 * Copyright 2011-2014 the original author or authors.
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
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.primitives.Ints;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.CompletedTraceEntry;
import org.glowroot.api.ErrorMessage;
import org.glowroot.api.MessageSupplier;
import org.glowroot.api.MetricName;
import org.glowroot.api.PluginServices;
import org.glowroot.api.PluginServices.ConfigListener;
import org.glowroot.api.TraceEntry;
import org.glowroot.api.TransactionMetric;
import org.glowroot.common.Clock;
import org.glowroot.common.ScheduledRunnable;
import org.glowroot.common.Ticker;
import org.glowroot.config.AdvancedConfig;
import org.glowroot.config.ConfigService;
import org.glowroot.config.PluginConfig;
import org.glowroot.config.PluginDescriptor;
import org.glowroot.config.TraceConfig;
import org.glowroot.jvm.ThreadAllocatedBytes;
import org.glowroot.transaction.model.MetricNameImpl;
import org.glowroot.transaction.model.Transaction;
import org.glowroot.transaction.model.TransactionMetricExt;
import org.glowroot.transaction.model.TransactionMetricImpl;

class PluginServicesImpl extends PluginServices implements ConfigListener {

    private static final Logger logger = LoggerFactory.getLogger(PluginServicesImpl.class);

    private final TransactionRegistry transactionRegistry;
    private final TransactionCollector transactionCollector;
    private final ConfigService configService;
    private final MetricNameCache metricNameCache;
    private final @Nullable ThreadAllocatedBytes threadAllocatedBytes;
    private final UserProfileScheduler userProfileScheduler;
    private final Clock clock;
    private final Ticker ticker;

    // pluginId is either the id of a registered plugin or it is null
    // (see validation in constructor)
    private final @Nullable String pluginId;

    // cache for fast read access
    private volatile boolean enabled;
    private volatile boolean captureThreadInfo;
    private volatile boolean captureGcInfo;
    private volatile int maxTraceEntriesPerTransaction;
    private volatile @MonotonicNonNull PluginConfig pluginConfig;

    static PluginServicesImpl create(TransactionRegistry transactionRegistry,
            TransactionCollector transactionCollector, ConfigService configService,
            MetricNameCache metricNameCache, @Nullable ThreadAllocatedBytes threadAllocatedBytes,
            UserProfileScheduler userProfileScheduler, Ticker ticker, Clock clock,
            List<PluginDescriptor> pluginDescriptors, @Nullable String pluginId) {
        PluginServicesImpl pluginServices = new PluginServicesImpl(transactionRegistry,
                transactionCollector, configService, metricNameCache, threadAllocatedBytes,
                userProfileScheduler, ticker, clock, pluginDescriptors, pluginId);
        // add config listeners first before caching configuration property values to avoid a
        // (remotely) possible race condition
        configService.addConfigListener(pluginServices);
        if (pluginId != null) {
            configService.addPluginConfigListener(pluginId, pluginServices);
        }
        // call onChange() to initialize the cached configuration property values
        pluginServices.onChange();
        return pluginServices;
    }

    private PluginServicesImpl(TransactionRegistry transactionRegistry,
            TransactionCollector transactionCollector, ConfigService configService,
            MetricNameCache metricNameCache, @Nullable ThreadAllocatedBytes threadAllocatedBytes,
            UserProfileScheduler userProfileScheduler, Ticker ticker, Clock clock,
            List<PluginDescriptor> pluginDescriptors, @Nullable String pluginId) {
        this.transactionRegistry = transactionRegistry;
        this.transactionCollector = transactionCollector;
        this.configService = configService;
        this.metricNameCache = metricNameCache;
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
    public String getStringProperty(String name) {
        if (name == null) {
            logger.error("getStringProperty(): argument 'name' must be non-null");
            return "";
        }
        if (pluginConfig == null) {
            return "";
        }
        return pluginConfig.getStringProperty(name);
    }

    @Override
    public boolean getBooleanProperty(String name) {
        if (name == null) {
            logger.error("getBooleanProperty(): argument 'name' must be non-null");
            return false;
        }
        return pluginConfig != null && pluginConfig.getBooleanProperty(name);
    }

    @Override
    public @Nullable Double getDoubleProperty(String name) {
        if (name == null) {
            logger.error("getDoubleProperty(): argument 'name' must be non-null");
            return null;
        }
        if (pluginConfig == null) {
            return null;
        }
        return pluginConfig.getDoubleProperty(name);
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
    }

    @Override
    public MetricName getMetricName(Class<?> adviceClass) {
        return metricNameCache.getName(adviceClass);
    }

    @Override
    public TraceEntry startTransaction(String transactionType, String transactionName,
            MessageSupplier messageSupplier, MetricName metricName) {
        if (messageSupplier == null) {
            logger.error("startTrace(): argument 'messageSupplier' must be non-null");
            return NopTraceEntry.INSTANCE;
        }
        if (metricName == null) {
            logger.error("startTrace(): argument 'metricName' must be non-null");
            return NopTraceEntry.INSTANCE;
        }
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction == null) {
            TransactionMetricImpl rootMetric =
                    TransactionMetricImpl.createRootMetric((MetricNameImpl) metricName, ticker);
            long startTick = ticker.read();
            rootMetric.start(startTick);
            transaction = new Transaction(clock.currentTimeMillis(), transactionType,
                    transactionName, messageSupplier, rootMetric, startTick, captureThreadInfo,
                    captureGcInfo, threadAllocatedBytes, ticker);
            transactionRegistry.addTransaction(transaction);
            return new TraceEntryImpl(transaction.getTraceEntryComponent(), transaction);
        } else {
            return startTraceEntry(transaction, metricName, messageSupplier);
        }
    }

    @Override
    public TraceEntry startTraceEntry(MessageSupplier messageSupplier, MetricName metricName) {
        if (messageSupplier == null) {
            logger.error("startTraceEntry(): argument 'messageSupplier' must be non-null");
            return NopTraceEntry.INSTANCE;
        }
        if (metricName == null) {
            logger.error("startTraceEntry(): argument 'metricName' must be non-null");
            return NopTraceEntry.INSTANCE;
        }
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction == null) {
            return NopTraceEntry.INSTANCE;
        } else {
            return startTraceEntry(transaction, metricName, messageSupplier);
        }
    }

    @Override
    public TransactionMetric startTransactionMetric(MetricName metricName) {
        if (metricName == null) {
            logger.error("startTransactionMetric(): argument 'metricName' must be non-null");
            return NopTransactionMetric.INSTANCE;
        }
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction == null) {
            return NopTransactionMetric.INSTANCE;
        }
        TransactionMetricImpl currentMetric = transaction.getCurrentTransactionMetric();
        if (currentMetric == null) {
            return NopTransactionMetric.INSTANCE;
        }
        return currentMetric.startNestedMetric(metricName);
    }

    private TransactionMetricExt startTransactionMetric(MetricName metricName, long startTick,
            Transaction transaction) {
        if (metricName == null) {
            logger.error("startTransactionMetric(): argument 'metricName' must be non-null");
            return NopTransactionMetricExt.INSTANCE;
        }
        TransactionMetricImpl currentTransactionMetric = transaction.getCurrentTransactionMetric();
        if (currentTransactionMetric == null) {
            return NopTransactionMetricExt.INSTANCE;
        }
        return currentTransactionMetric.startNestedMetric(metricName, startTick);
    }

    @Override
    public CompletedTraceEntry addTraceEntry(MessageSupplier messageSupplier) {
        if (messageSupplier == null) {
            logger.error("addEntry(): argument 'messageSupplier' must be non-null");
            return NopCompletedEntry.INSTANCE;
        }
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction != null && transaction.getEntryCount() < maxTraceEntriesPerTransaction) {
            // the trace limit has not been exceeded
            long currTick = ticker.read();
            return new CompletedEntryImpl(transaction.addEntry(currTick, currTick, messageSupplier,
                    null, false));
        }
        return NopCompletedEntry.INSTANCE;
    }

    @Override
    public CompletedTraceEntry addTraceEntry(ErrorMessage errorMessage) {
        if (errorMessage == null) {
            logger.error("addErrorEntry(): argument 'errorMessage' must be non-null");
            return NopCompletedEntry.INSTANCE;
        }
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        // use higher entry limit when adding errors, but still need some kind of cap
        if (transaction != null
                && transaction.getEntryCount() < 2 * maxTraceEntriesPerTransaction) {
            long currTick = ticker.read();
            return new CompletedEntryImpl(
                    transaction.addEntry(currTick, currTick, null, errorMessage, true));
        }
        return NopCompletedEntry.INSTANCE;
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
    public void setTransactionError(@Nullable String error) {
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction != null) {
            transaction.setError(error);
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
    public void setTransactionCustomAttribute(String name, @Nullable String value) {
        if (name == null) {
            logger.error("setTransactionCustomAttribute(): argument 'name' must be non-null");
            return;
        }
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction != null) {
            transaction.putCustomAttribute(name, value);
        }
    }

    @Override
    public void setTraceStoreThreshold(long threshold, TimeUnit unit) {
        if (threshold < 0) {
            logger.error("setTraceStoreThreshold(): argument 'threshold' must be non-negative");
            return;
        }
        Transaction transaction = transactionRegistry.getCurrentTransaction();
        if (transaction != null) {
            int thresholdMillis = Ints.saturatedCast(unit.toMillis(threshold));
            transaction.setStoreThresholdMillisOverride(thresholdMillis);
        }
    }

    @Override
    public boolean isInTransaction() {
        return transactionRegistry.getCurrentTransaction() != null;
    }

    @Override
    public void onChange() {
        TraceConfig traceConfig = configService.getTraceConfig();
        if (pluginId == null) {
            enabled = traceConfig.enabled();
        } else {
            PluginConfig pluginConfig = configService.getPluginConfig(pluginId);
            if (pluginConfig == null) {
                // pluginId was already validated at construction time so this should not happen
                logger.error("plugin config not found for plugin id: {}", pluginId);
                enabled = traceConfig.enabled();
            } else {
                enabled = traceConfig.enabled() && pluginConfig.enabled();
                this.pluginConfig = pluginConfig;
            }
        }
        AdvancedConfig advancedConfig = configService.getAdvancedConfig();
        maxTraceEntriesPerTransaction = advancedConfig.maxTraceEntriesPerTransaction();
        captureThreadInfo = advancedConfig.captureThreadInfo();
        captureGcInfo = advancedConfig.captureGcInfo();
    }

    private TraceEntry startTraceEntry(Transaction transaction, MetricName metricName,
            MessageSupplier messageSupplier) {
        long startTick = ticker.read();
        if (transaction.getEntryCount() >= maxTraceEntriesPerTransaction) {
            // the entry limit has been exceeded for this trace
            transaction.addEntryLimitExceededMarkerIfNeeded();
            TransactionMetricExt transactionMetric =
                    startTransactionMetric(metricName, startTick, transaction);
            return new DummyTraceEntry(transactionMetric, startTick, transaction, messageSupplier);
        } else {
            TransactionMetricExt transactionMetric =
                    startTransactionMetric(metricName, startTick, transaction);
            org.glowroot.transaction.model.TraceEntry traceEntry =
                    transaction.pushEntry(startTick, messageSupplier, transactionMetric);
            return new TraceEntryImpl(traceEntry, transaction);
        }
    }

    private static ImmutableList<StackTraceElement> captureStackTrace() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        // need to strip back a few stack calls:
        // skip i=0 which is "java.lang.Thread.getStackTrace()"
        for (int i = 1; i < stackTrace.length; i++) {
            // startsWith to include nested classes
            if (!stackTrace[i].getClassName().startsWith(PluginServicesImpl.class.getName())) {
                // found the caller of PluginServicesImpl, this should be the @Pointcut
                // @OnReturn/@OnThrow/@OnAfter method, next one should be the woven method
                return ImmutableList.copyOf(stackTrace).subList(i + 1, stackTrace.length);
            }
        }
        logger.warn("stack trace didn't include endWithStackTrace()");
        return ImmutableList.of();
    }

    private class TraceEntryImpl implements TraceEntry {
        private final org.glowroot.transaction.model.TraceEntry traceEntry;
        private final Transaction transaction;
        private TraceEntryImpl(org.glowroot.transaction.model.TraceEntry traceEntry,
                Transaction transaction) {
            this.traceEntry = traceEntry;
            this.transaction = transaction;
        }
        @Override
        public CompletedTraceEntry end() {
            return endInternal(ticker.read(), null);
        }
        @Override
        public CompletedTraceEntry endWithStackTrace(long threshold, TimeUnit unit) {
            if (threshold < 0) {
                logger.error("endWithStackTrace(): argument 'threshold' must be non-negative");
                return end();
            }
            long endTick = ticker.read();
            if (endTick - traceEntry.getStartTick() >= unit.toNanos(threshold)) {
                traceEntry.setStackTrace(captureStackTrace());
            }
            return endInternal(endTick, null);
        }
        @Override
        public CompletedTraceEntry endWithError(ErrorMessage errorMessage) {
            if (errorMessage == null) {
                logger.error("endWithError(): argument 'errorMessage' must be non-null");
                // fallback to end() without error
                return end();
            } else {
                return endInternal(ticker.read(), errorMessage);
            }
        }
        @Override
        public MessageSupplier getMessageSupplier() {
            MessageSupplier messageSupplier = traceEntry.getMessageSupplier();
            if (messageSupplier == null) {
                // this should be impossible since entry.getMessageSupplier() is only null when the
                // entry was created using addErrorEntry(), and that method doesn't return the entry
                // afterwards, so it should be impossible to call getMessageSupplier() on it
                throw new AssertionError("Somehow got hold of an error entry??");
            }
            return messageSupplier;
        }
        private CompletedTraceEntry endInternal(long endTick, @Nullable ErrorMessage errorMessage) {
            transaction.popEntry(traceEntry, endTick, errorMessage);
            if (transaction.isCompleted()) {
                // the root entry has been popped off
                safeCancel(transaction.getImmedateTraceStoreRunnable());
                safeCancel(transaction.getUserProfileRunnable());
                // send to trace collector before removing from trace registry so that trace
                // collector can cover the gap (via
                // TransactionCollectorImpl.getPendingCompleteTraces())
                // between removing the trace from the registry and storing it
                transactionCollector.onCompletedTransaction(transaction);
                transactionRegistry.removeTransaction(transaction);
            }
            return new CompletedEntryImpl(traceEntry);
        }
        private void safeCancel(@Nullable ScheduledRunnable scheduledRunnable) {
            if (scheduledRunnable == null) {
                return;
            }
            scheduledRunnable.cancel();
        }
    }

    private class DummyTraceEntry implements TraceEntry {
        private final TransactionMetricExt transactionMetric;
        private final long startTick;
        private final Transaction transaction;
        private final MessageSupplier messageSupplier;
        public DummyTraceEntry(TransactionMetricExt transactionMetric, long startTick,
                Transaction transaction, MessageSupplier messageSupplier) {
            this.transactionMetric = transactionMetric;
            this.startTick = startTick;
            this.transaction = transaction;
            this.messageSupplier = messageSupplier;
        }
        @Override
        public CompletedTraceEntry end() {
            transactionMetric.stop();
            return NopCompletedEntry.INSTANCE;
        }
        @Override
        public CompletedTraceEntry endWithStackTrace(long threshold, TimeUnit unit) {
            long endTick = ticker.read();
            transactionMetric.end(endTick);
            // use higher entry limit when adding slow entries, but still need some kind of cap
            if (endTick - startTick >= unit.toNanos(threshold)
                    && transaction.getEntryCount() < 2 * maxTraceEntriesPerTransaction) {
                // entry won't necessarily be nested properly, and won't have any timing data, but
                // at least the long entry and stack trace will get captured
                org.glowroot.transaction.model.TraceEntry entry =
                        transaction.addEntry(startTick, endTick, messageSupplier, null, true);
                entry.setStackTrace(captureStackTrace());
                return new CompletedEntryImpl(entry);
            }
            return NopCompletedEntry.INSTANCE;
        }
        @Override
        public CompletedTraceEntry endWithError(ErrorMessage errorMessage) {
            if (errorMessage == null) {
                logger.error("endWithError(): argument 'errorMessage' must be non-null");
                // fallback to end() without error
                return end();
            }
            long endTick = ticker.read();
            transactionMetric.end(endTick);
            // use higher entry limit when adding errors, but still need some kind of cap
            if (transaction.getEntryCount() < 2 * maxTraceEntriesPerTransaction) {
                // entry won't be nested properly, but at least the error will get captured
                return new CompletedEntryImpl(transaction.addEntry(startTick, endTick,
                        messageSupplier, errorMessage, true));
            }
            return NopCompletedEntry.INSTANCE;
        }
        @Override
        public MessageSupplier getMessageSupplier() {
            return messageSupplier;
        }
    }

    private static class CompletedEntryImpl implements CompletedTraceEntry {
        private final org.glowroot.transaction.model.TraceEntry entry;
        private CompletedEntryImpl(org.glowroot.transaction.model.TraceEntry entry) {
            this.entry = entry;
        }
        @Override
        public void captureStackTrace() {
            entry.setStackTrace(PluginServicesImpl.captureStackTrace());
        }
    }

    private static class NopTraceEntry implements TraceEntry {
        private static final NopTraceEntry INSTANCE = new NopTraceEntry();
        private NopTraceEntry() {}
        @Override
        public CompletedTraceEntry end() {
            return NopCompletedEntry.INSTANCE;
        }
        @Override
        public CompletedTraceEntry endWithStackTrace(long threshold, TimeUnit unit) {
            return NopCompletedEntry.INSTANCE;
        }
        @Override
        public CompletedTraceEntry endWithError(ErrorMessage errorMessage) {
            return NopCompletedEntry.INSTANCE;
        }
        @Override
        public @Nullable MessageSupplier getMessageSupplier() {
            return null;
        }
    }

    private static class NopTransactionMetric implements TransactionMetric {
        private static final NopTransactionMetric INSTANCE = new NopTransactionMetric();
        @Override
        public void stop() {}
    }

    private static class NopTransactionMetricExt implements TransactionMetricExt {
        private static final NopTransactionMetricExt INSTANCE = new NopTransactionMetricExt();
        @Override
        public void stop() {}
        @Override
        public void end(long endTick) {}
    }

    private static class NopCompletedEntry implements CompletedTraceEntry {
        private static final NopCompletedEntry INSTANCE = new NopCompletedEntry();
        @Override
        public void captureStackTrace() {}
    }
}
