/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.testkit;

import io.informant.core.MainEntryPoint;
import io.informant.core.config.ConfigService;
import io.informant.core.config.ConfigService.OptimisticLockException;
import io.informant.core.trace.TraceRegistry;
import io.informant.core.util.ByteStream;
import io.informant.core.util.DataSource;
import io.informant.core.util.ThreadSafe;
import io.informant.local.log.LogMessageDao;
import io.informant.local.trace.TraceSinkLocal;
import io.informant.local.trace.TraceSnapshot;
import io.informant.local.trace.TraceSnapshotDao;
import io.informant.local.trace.TraceSnapshotWriter;
import io.informant.local.trace.TraceWriter;
import io.informant.local.ui.TraceExportHttpService;
import io.informant.testkit.LogMessage.Level;
import io.informant.testkit.PointcutConfig.CaptureItem;
import io.informant.testkit.PointcutConfig.MethodModifier;
import io.informant.testkit.Trace.ExceptionInfo;
import io.informant.testkit.internal.GsonFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.List;
import java.util.Map.Entry;

import checkers.nullness.quals.Nullable;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Stopwatch;
import com.google.common.base.Ticker;
import com.google.common.collect.Lists;
import com.google.gson.Gson;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// even though this is thread safe, it is not useful for running tests in parallel since
// getLastTrace() and others are not scoped to a particular test
@ThreadSafe
class SameJvmInformant implements Informant {

    private static final Gson gson = GsonFactory.create();

    private final ConfigService configService;
    private final LogMessageDao logMessageDao;
    private final DataSource dataSource;
    private final TraceSinkLocal traceSinkLocal;
    private final TraceSnapshotDao traceSnapshotDao;
    private final TraceExportHttpService traceExportHttpService;
    private final TraceRegistry traceRegistry;
    private final Ticker ticker;

    SameJvmInformant() {
        configService = MainEntryPoint.getInstance(ConfigService.class);
        logMessageDao = MainEntryPoint.getInstance(LogMessageDao.class);
        dataSource = MainEntryPoint.getInstance(DataSource.class);
        traceSinkLocal = MainEntryPoint.getInstance(TraceSinkLocal.class);
        traceSnapshotDao = MainEntryPoint.getInstance(TraceSnapshotDao.class);
        traceExportHttpService = MainEntryPoint.getInstance(TraceExportHttpService.class);
        traceRegistry = MainEntryPoint.getInstance(TraceRegistry.class);
        // can't use ticker from Informant since it is shaded when run in mvn and unshaded in ide
        ticker = Ticker.systemTicker();
    }

    public void setStoreThresholdMillis(int storeThresholdMillis) throws OptimisticLockException {
        io.informant.core.config.GeneralConfig config = configService.getGeneralConfig();
        io.informant.core.config.GeneralConfig updatedConfig =
                io.informant.core.config.GeneralConfig.builder(config)
                        .storeThresholdMillis(storeThresholdMillis)
                        .build();
        configService.updateGeneralConfig(updatedConfig, config.getVersionHash());
    }

    public GeneralConfig getGeneralConfig() {
        io.informant.core.config.GeneralConfig coreConfig = configService.getGeneralConfig();
        GeneralConfig config = new GeneralConfig();
        config.setEnabled(coreConfig.isEnabled());
        config.setStoreThresholdMillis(coreConfig.getStoreThresholdMillis());
        config.setStuckThresholdSeconds(coreConfig.getStuckThresholdSeconds());
        config.setMaxSpans(coreConfig.getMaxSpans());
        config.setSnapshotExpirationHours(coreConfig.getSnapshotExpirationHours());
        config.setRollingSizeMb(coreConfig.getRollingSizeMb());
        config.setWarnOnSpanOutsideTrace(coreConfig.isWarnOnSpanOutsideTrace());
        config.setVersionHash(coreConfig.getVersionHash());
        return config;
    }

    public String updateGeneralConfig(GeneralConfig config) throws OptimisticLockException {
        io.informant.core.config.GeneralConfig updatedConfig =
                io.informant.core.config.GeneralConfig.builder(configService.getGeneralConfig())
                        .enabled(config.isEnabled())
                        .storeThresholdMillis(config.getStoreThresholdMillis())
                        .stuckThresholdSeconds(config.getStuckThresholdSeconds())
                        .maxSpans(config.getMaxSpans())
                        .snapshotExpirationHours(config.getSnapshotExpirationHours())
                        .rollingSizeMb(config.getRollingSizeMb())
                        .warnOnSpanOutsideTrace(config.isWarnOnSpanOutsideTrace())
                        .build();
        return configService.updateGeneralConfig(updatedConfig, config.getVersionHash());
    }

    public CoarseProfilingConfig getCoarseProfilingConfig() {
        io.informant.core.config.CoarseProfilingConfig coreConfig =
                configService.getCoarseProfilingConfig();
        CoarseProfilingConfig config = new CoarseProfilingConfig();
        config.setEnabled(coreConfig.isEnabled());
        config.setInitialDelayMillis(coreConfig.getInitialDelayMillis());
        config.setIntervalMillis(coreConfig.getIntervalMillis());
        config.setTotalSeconds(coreConfig.getTotalSeconds());
        config.setVersionHash(coreConfig.getVersionHash());
        return config;
    }

    public String updateCoarseProfilingConfig(CoarseProfilingConfig config)
            throws OptimisticLockException {
        io.informant.core.config.CoarseProfilingConfig updatedConfig =
                io.informant.core.config.CoarseProfilingConfig
                        .builder(configService.getCoarseProfilingConfig())
                        .enabled(config.isEnabled())
                        .initialDelayMillis(config.getInitialDelayMillis())
                        .intervalMillis(config.getIntervalMillis())
                        .totalSeconds(config.getTotalSeconds())
                        .build();
        return configService.updateCoarseProfilingConfig(updatedConfig, config.getVersionHash());
    }

    public FineProfilingConfig getFineProfilingConfig() {
        io.informant.core.config.FineProfilingConfig coreConfig =
                configService.getFineProfilingConfig();
        FineProfilingConfig config = new FineProfilingConfig();
        config.setEnabled(coreConfig.isEnabled());
        config.setTracePercentage(coreConfig.getTracePercentage());
        config.setIntervalMillis(coreConfig.getIntervalMillis());
        config.setTotalSeconds(coreConfig.getTotalSeconds());
        config.setStoreThresholdMillis(coreConfig.getStoreThresholdMillis());
        config.setVersionHash(coreConfig.getVersionHash());
        return config;
    }

    public String updateFineProfilingConfig(FineProfilingConfig config)
            throws OptimisticLockException {
        io.informant.core.config.FineProfilingConfig updatedConfig =
                io.informant.core.config.FineProfilingConfig
                        .builder(configService.getFineProfilingConfig())
                        .enabled(config.isEnabled())
                        .tracePercentage(config.getTracePercentage())
                        .intervalMillis(config.getIntervalMillis())
                        .totalSeconds(config.getTotalSeconds())
                        .storeThresholdMillis(config.getStoreThresholdMillis())
                        .build();
        return configService.updateFineProfilingConfig(updatedConfig, config.getVersionHash());
    }

    public UserConfig getUserConfig() {
        io.informant.core.config.UserConfig coreConfig = configService.getUserConfig();
        UserConfig config = new UserConfig();
        config.setEnabled(coreConfig.isEnabled());
        config.setUserId(coreConfig.getUserId());
        config.setStoreThresholdMillis(coreConfig.getStoreThresholdMillis());
        config.setFineProfiling(coreConfig.isFineProfiling());
        config.setVersionHash(coreConfig.getVersionHash());
        return config;
    }

    public String updateUserConfig(UserConfig config) throws OptimisticLockException {
        io.informant.core.config.UserConfig updatedConfig = io.informant.core.config.UserConfig
                .builder(configService.getUserConfig())
                .enabled(config.isEnabled())
                .userId(config.getUserId())
                .storeThresholdMillis(config.getStoreThresholdMillis())
                .fineProfiling(config.isFineProfiling())
                .build();
        return configService.updateUserConfig(updatedConfig, config.getVersionHash());
    }

    @Nullable
    public PluginConfig getPluginConfig(String pluginId) {
        io.informant.core.config.PluginConfig coreConfig = configService.getPluginConfig(pluginId);
        if (coreConfig == null) {
            return null;
        }
        PluginConfig config = new PluginConfig();
        config.setEnabled(coreConfig.isEnabled());
        for (Entry<String, /*@Nullable*/Object> entry : coreConfig.getProperties().entrySet()) {
            config.setProperty(entry.getKey(), entry.getValue());
        }
        config.setVersionHash(coreConfig.getVersionHash());
        return config;
    }

    public String updatePluginConfig(String pluginId, PluginConfig config)
            throws OptimisticLockException {
        io.informant.core.config.PluginConfig.Builder updatedConfig =
                io.informant.core.config.PluginConfig
                        .builder(configService.getPluginConfig(pluginId));
        updatedConfig.enabled(config.isEnabled());
        for (Entry<String, /*@Nullable*/Object> entry : config.getProperties().entrySet()) {
            updatedConfig.setProperty(entry.getKey(), entry.getValue());
        }
        return configService.updatePluginConfig(updatedConfig.build(), config.getVersionHash());
    }

    public List<PointcutConfig> getPointcutConfigs() {
        List<PointcutConfig> configs = Lists.newArrayList();
        for (io.informant.core.config.PointcutConfig coreConfig : configService
                .getPointcutConfigs()) {
            configs.add(convertToCore(coreConfig));
        }
        return configs;
    }

    public String addPointcutConfig(PointcutConfig config) {
        return configService.insertPointcutConfig(convertToCore(config));
    }

    public String updatePointcutConfig(String versionHash, PointcutConfig config) {
        return configService.updatePointcutConfig(versionHash, convertToCore(config));
    }

    public void removePointcutConfig(String versionHash) {
        configService.deletePointcutConfig(versionHash);
    }

    public Trace getLastTrace() throws Exception {
        return getLastTrace(false);
    }

    public Trace getLastTraceSummary() throws Exception {
        return getLastTrace(true);
    }

    public List<LogMessage> getLogMessages() {
        List<LogMessage> logMessages = Lists.newArrayList();
        for (io.informant.local.log.LogMessage coreLogMessage : logMessageDao.readLogMessages()) {
            LogMessage logMessage = new LogMessage();
            logMessage.setTimestamp(coreLogMessage.getTimestamp());
            logMessage.setLevel(Level.valueOf(coreLogMessage.getLevel().name()));
            logMessage.setLoggerName(coreLogMessage.getLoggerName());
            logMessage.setText(coreLogMessage.getText());
            logMessage.setException(gson.fromJson(coreLogMessage.getException(),
                    ExceptionInfo.class));
            logMessages.add(logMessage);
        }
        return logMessages;
    }

    public void deleteAllLogMessages() {
        logMessageDao.deleteAllLogMessages();
    }

    public void compactData() throws SQLException {
        dataSource.compact();
    }

    // this method blocks for an active trace to be available because
    // sometimes need to give container enough time to start up and for the trace to get stuck
    @Nullable
    public Trace getActiveTraceSummary(int timeoutMillis) throws Exception {
        return getActiveTrace(timeoutMillis, true);
    }

    // this method blocks for an active trace to be available because
    // sometimes need to give container enough time to start up and for the trace to get stuck
    @Nullable
    public Trace getActiveTrace(int timeoutMillis) throws Exception {
        return getActiveTrace(timeoutMillis, false);
    }

    public void cleanUpAfterEachTest() throws Exception {
        traceSnapshotDao.deleteAllSnapshots();
        List<LogMessage> warningMessages = Lists.newArrayList();
        for (LogMessage message : getLogMessages()) {
            if (message.getLevel() == Level.WARN || message.getLevel() == Level.ERROR) {
                warningMessages.add(message);
            }
        }
        if (!warningMessages.isEmpty()) {
            // clear warnings for next test before throwing assertion error
            deleteAllLogMessages();
            throw new AssertionError("There were warnings and/or errors: "
                    + Joiner.on(", ").join(warningMessages));
        }
        configService.deleteConfig();

    }

    public int getNumPendingCompleteTraces() {
        return traceSinkLocal.getPendingCompleteTraces().size();
    }

    public long getNumStoredTraceSnapshots() {
        return traceSnapshotDao.count();
    }

    public InputStream getTraceExport(String id) throws Exception {
        ByteStream byteStream = traceExportHttpService.getExportByteStream(id);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byteStream.writeTo(baos);
        return new ByteArrayInputStream(baos.toByteArray());
    }

    private Trace getLastTrace(boolean summary) throws Exception {
        TraceSnapshot snapshot = traceSnapshotDao.getLastSnapshot(summary);
        ByteStream byteStream = TraceSnapshotWriter.toByteStream(snapshot, false);
        Trace trace = getTrace(byteStream);
        trace.setSummary(summary);
        return trace;
    }

    private Trace getActiveTrace(int timeoutMillis, boolean summary) throws Exception {
        Stopwatch stopwatch = new Stopwatch().start();
        Trace trace = null;
        // try at least once (e.g. in case timeoutMillis == 0)
        while (true) {
            trace = getActiveTrace(summary);
            if (trace != null || stopwatch.elapsedMillis() > timeoutMillis) {
                break;
            }
            Thread.sleep(20);
        }
        return trace;
    }

    @Nullable
    private Trace getActiveTrace(boolean summary) throws IOException {
        List<io.informant.core.trace.Trace> traces = Lists.newArrayList(traceRegistry.getTraces());
        if (traces.isEmpty()) {
            return null;
        } else if (traces.size() > 1) {
            throw new IllegalStateException("Unexpected number of active traces");
        } else {
            TraceSnapshot snapshot =
                    TraceWriter.toTraceSnapshot(traces.get(0), ticker.read(), summary);
            ByteStream byteStream = TraceSnapshotWriter.toByteStream(snapshot, true);
            Trace trace = getTrace(byteStream);
            trace.setSummary(summary);
            return trace;
        }
    }

    private static Trace getTrace(ByteStream byteStream) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byteStream.writeTo(baos);
        Trace trace =
                gson.fromJson(new String(baos.toByteArray(), Charsets.UTF_8.name()), Trace.class);
        return trace;
    }

    private static PointcutConfig convertToCore(
            io.informant.core.config.PointcutConfig coreConfig) {
        List<CaptureItem> captureItems = Lists.newArrayList();
        for (io.informant.core.config.PointcutConfig.CaptureItem captureItem : coreConfig
                .getCaptureItems()) {
            captureItems.add(CaptureItem.valueOf(captureItem.name()));
        }
        List<MethodModifier> methodModifiers = Lists.newArrayList();
        for (io.informant.api.weaving.MethodModifier methodModifier : coreConfig
                .getMethodModifiers()) {
            methodModifiers.add(MethodModifier.valueOf(methodModifier.name()));
        }

        PointcutConfig config = new PointcutConfig();
        config.setCaptureItems(captureItems);
        config.setTypeName(coreConfig.getTypeName());
        config.setMethodName(coreConfig.getMethodName());
        config.setMethodArgTypeNames(coreConfig.getMethodArgTypeNames());
        config.setMethodReturnTypeName(coreConfig.getMethodReturnTypeName());
        config.setMethodModifiers(methodModifiers);
        config.setMetricName(coreConfig.getMetricName());
        config.setSpanTemplate(coreConfig.getSpanTemplate());
        config.setVersionHash(coreConfig.getVersionHash());
        return config;
    }

    private static io.informant.core.config.PointcutConfig convertToCore(PointcutConfig config) {
        List<io.informant.core.config.PointcutConfig.CaptureItem> captureItems =
                Lists.newArrayList();
        for (CaptureItem captureItem : config.getCaptureItems()) {
            captureItems.add(io.informant.core.config.PointcutConfig.CaptureItem
                    .valueOf(captureItem.name()));
        }
        List<io.informant.api.weaving.MethodModifier> methodModifiers = Lists.newArrayList();
        for (MethodModifier methodModifier : config.getMethodModifiers()) {
            methodModifiers.add(io.informant.api.weaving.MethodModifier.valueOf(methodModifier
                    .name()));
        }
        return io.informant.core.config.PointcutConfig.builder()
                .captureItems(captureItems)
                .typeName(config.getTypeName())
                .methodName(config.getMethodName())
                .methodArgTypeNames(config.getMethodArgTypeNames())
                .methodReturnTypeName(config.getMethodReturnTypeName())
                .methodModifiers(methodModifiers)
                .metricName(config.getMetricName())
                .spanTemplate(config.getSpanTemplate())
                .build();
    }
}
