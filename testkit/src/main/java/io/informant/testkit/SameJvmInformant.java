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

import static java.util.concurrent.TimeUnit.SECONDS;
import io.informant.InformantModule;
import io.informant.config.ConfigModule;
import io.informant.config.ConfigService;
import io.informant.core.CoreModule;
import io.informant.core.TraceRegistry;
import io.informant.local.store.DataSource;
import io.informant.local.store.DataSourceModule;
import io.informant.local.store.LocalTraceSink;
import io.informant.local.store.StorageModule;
import io.informant.local.store.TraceSnapshot;
import io.informant.local.store.TraceSnapshotDao;
import io.informant.local.store.TraceSnapshotWriter;
import io.informant.local.store.TraceWriter;
import io.informant.local.ui.LocalUiModule;
import io.informant.local.ui.TraceExportHttpService;
import io.informant.testkit.PointcutConfig.CaptureItem;
import io.informant.testkit.PointcutConfig.MethodModifier;
import io.informant.testkit.internal.ObjectMappers;
import io.informant.util.ThreadSafe;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Stopwatch;
import com.google.common.base.Ticker;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
// even though this is thread safe, it is not useful for running tests in parallel since
// getLastTrace() and others are not scoped to a particular test
@ThreadSafe
class SameJvmInformant implements Informant {

    @ReadOnly
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final ConfigService configService;
    private final DataSource dataSource;
    private final LocalTraceSink traceSinkLocal;
    private final TraceSnapshotDao traceSnapshotDao;
    private final TraceExportHttpService traceExportHttpService;
    private final TraceRegistry traceRegistry;
    private final Ticker ticker;

    SameJvmInformant(InformantModule informantModule) {
        ConfigModule configModule = informantModule.getConfigModule();
        DataSourceModule dataSourceModule = informantModule.getDataSourceModule();
        StorageModule storageModule = informantModule.getStorageModule();
        CoreModule coreModule = informantModule.getCoreModule();
        LocalUiModule uiModule = informantModule.getUiModule();
        configService = configModule.getConfigService();
        dataSource = dataSourceModule.getDataSource();
        traceSinkLocal = storageModule.getTraceSink();
        traceSnapshotDao = storageModule.getTraceSnapshotDao();
        traceExportHttpService = uiModule.getTraceExportHttpService();
        traceRegistry = coreModule.getTraceRegistry();
        // can't use ticker from Informant since it is shaded when run in mvn and unshaded in ide
        ticker = Ticker.systemTicker();
    }

    public void setStoreThresholdMillis(int storeThresholdMillis) throws Exception {
        io.informant.config.GeneralConfig config = configService.getGeneralConfig();
        io.informant.config.GeneralConfig.Overlay overlay =
                io.informant.config.GeneralConfig.overlay(config);
        overlay.setStoreThresholdMillis(storeThresholdMillis);
        configService.updateGeneralConfig(overlay.build(), config.getVersion());
    }

    public GeneralConfig getGeneralConfig() {
        io.informant.config.GeneralConfig coreConfig = configService.getGeneralConfig();
        GeneralConfig config = new GeneralConfig(coreConfig.getVersion());
        config.setEnabled(coreConfig.isEnabled());
        config.setStoreThresholdMillis(coreConfig.getStoreThresholdMillis());
        config.setStuckThresholdSeconds(coreConfig.getStuckThresholdSeconds());
        config.setMaxSpans(coreConfig.getMaxSpans());
        config.setSnapshotExpirationHours(coreConfig.getSnapshotExpirationHours());
        config.setRollingSizeMb(coreConfig.getRollingSizeMb());
        config.setWarnOnSpanOutsideTrace(coreConfig.isWarnOnSpanOutsideTrace());
        return config;
    }

    public String updateGeneralConfig(GeneralConfig config) throws Exception {
        io.informant.config.GeneralConfig updatedConfig =
                new io.informant.config.GeneralConfig(config.isEnabled(),
                        config.getStoreThresholdMillis(),
                        config.getStuckThresholdSeconds(),
                        config.getMaxSpans(),
                        config.getSnapshotExpirationHours(),
                        config.getRollingSizeMb(),
                        config.isWarnOnSpanOutsideTrace());
        return configService.updateGeneralConfig(updatedConfig, config.getVersion());
    }

    public CoarseProfilingConfig getCoarseProfilingConfig() {
        io.informant.config.CoarseProfilingConfig coreConfig =
                configService.getCoarseProfilingConfig();
        CoarseProfilingConfig config = new CoarseProfilingConfig(coreConfig.getVersion());
        config.setEnabled(coreConfig.isEnabled());
        config.setInitialDelayMillis(coreConfig.getInitialDelayMillis());
        config.setIntervalMillis(coreConfig.getIntervalMillis());
        config.setTotalSeconds(coreConfig.getTotalSeconds());
        return config;
    }

    public String updateCoarseProfilingConfig(CoarseProfilingConfig config) throws Exception {
        io.informant.config.CoarseProfilingConfig updatedConfig =
                new io.informant.config.CoarseProfilingConfig(config.isEnabled(),
                        config.getInitialDelayMillis(), config.getIntervalMillis(),
                        config.getTotalSeconds());
        return configService.updateCoarseProfilingConfig(updatedConfig, config.getVersion());
    }

    public FineProfilingConfig getFineProfilingConfig() {
        io.informant.config.FineProfilingConfig coreConfig =
                configService.getFineProfilingConfig();
        FineProfilingConfig config = new FineProfilingConfig(coreConfig.getVersion());
        config.setEnabled(coreConfig.isEnabled());
        config.setTracePercentage(coreConfig.getTracePercentage());
        config.setIntervalMillis(coreConfig.getIntervalMillis());
        config.setTotalSeconds(coreConfig.getTotalSeconds());
        config.setStoreThresholdMillis(coreConfig.getStoreThresholdMillis());
        return config;
    }

    public String updateFineProfilingConfig(FineProfilingConfig config) throws Exception {
        io.informant.config.FineProfilingConfig updatedConfig =
                new io.informant.config.FineProfilingConfig(config.isEnabled(),
                        config.getTracePercentage(), config.getIntervalMillis(),
                        config.getTotalSeconds(), config.getStoreThresholdMillis());
        return configService.updateFineProfilingConfig(updatedConfig, config.getVersion());
    }

    public UserConfig getUserConfig() {
        io.informant.config.UserConfig coreConfig = configService.getUserConfig();
        UserConfig config = new UserConfig(coreConfig.getVersion());
        config.setEnabled(coreConfig.isEnabled());
        config.setUserId(coreConfig.getUserId());
        config.setStoreThresholdMillis(coreConfig.getStoreThresholdMillis());
        config.setFineProfiling(coreConfig.isFineProfiling());
        return config;
    }

    public String updateUserConfig(UserConfig config) throws Exception {
        io.informant.config.UserConfig updatedConfig = new io.informant.config.UserConfig(
                config.isEnabled(), config.getUserId(), config.getStoreThresholdMillis(),
                config.isFineProfiling());
        return configService.updateUserConfig(updatedConfig, config.getVersion());
    }

    @Nullable
    public PluginConfig getPluginConfig(String pluginId) {
        io.informant.config.PluginConfig coreConfig = configService.getPluginConfig(pluginId);
        if (coreConfig == null) {
            return null;
        }
        PluginConfig config = new PluginConfig(coreConfig.getGroupId(), coreConfig.getArtifactId(),
                coreConfig.getVersion());
        config.setEnabled(coreConfig.isEnabled());
        for (Entry<String, /*@Nullable*/Object> entry : coreConfig.getProperties().entrySet()) {
            config.setProperty(entry.getKey(), entry.getValue());
        }
        return config;
    }

    public String updatePluginConfig(String pluginId, PluginConfig config) throws Exception {
        io.informant.config.PluginConfig pluginConfig = configService.getPluginConfig(pluginId);
        if (pluginConfig == null) {
            throw new IllegalArgumentException("Plugin for id not found: " + pluginId);
        }
        io.informant.config.PluginConfig.Builder updatedConfig =
                io.informant.config.PluginConfig.builder(pluginConfig);
        updatedConfig.enabled(config.isEnabled());
        for (Entry<String, /*@Nullable*/Object> entry : config.getProperties().entrySet()) {
            updatedConfig.setProperty(entry.getKey(), entry.getValue());
        }
        return configService.updatePluginConfig(updatedConfig.build(), config.getVersion());
    }

    public List<PointcutConfig> getPointcutConfigs() {
        List<PointcutConfig> configs = Lists.newArrayList();
        for (io.informant.config.PointcutConfig coreConfig : configService
                .getPointcutConfigs()) {
            configs.add(convertToCore(coreConfig));
        }
        return configs;
    }

    public String addPointcutConfig(PointcutConfig config) throws Exception {
        return configService.insertPointcutConfig(convertToCore(config));
    }

    public String updatePointcutConfig(String version, PointcutConfig config) throws Exception {
        return configService.updatePointcutConfig(version, convertToCore(config));
    }

    public void removePointcutConfig(String version) throws Exception {
        configService.deletePointcutConfig(version);
    }

    @Nullable
    public Trace getLastTrace() throws Exception {
        return getLastTrace(false);
    }

    @Nullable
    public Trace getLastTraceSummary() throws Exception {
        return getLastTrace(true);
    }

    public void compactData() throws SQLException {
        dataSource.compact();
    }

    // this method blocks for an active trace to be available because
    // sometimes need to give container enough time to start up and for the trace to get stuck
    @Nullable
    public Trace getActiveTraceSummary(int timeout, TimeUnit unit) throws Exception {
        return getActiveTrace(timeout, unit, true);
    }

    // this method blocks for an active trace to be available because
    // sometimes need to give container enough time to start up and for the trace to get stuck
    @Nullable
    public Trace getActiveTrace(int timeout, TimeUnit unit) throws Exception {
        return getActiveTrace(timeout, unit, false);
    }

    public void cleanUpAfterEachTest() throws Exception {
        traceSnapshotDao.deleteAllSnapshots();
        assertNoActiveTraces();
        // TODO assert no warn or error log messages
        configService.resetAllConfig();
    }

    public int getNumPendingCompleteTraces() {
        return traceSinkLocal.getPendingCompleteTraces().size();
    }

    public long getNumStoredTraceSnapshots() {
        return traceSnapshotDao.count();
    }

    public InputStream getTraceExport(String id) throws Exception {
        return new ByteArrayInputStream(traceExportHttpService.getExportBytes(id));
    }

    @Nullable
    private Trace getLastTrace(boolean summary) throws Exception {
        TraceSnapshot snapshot = traceSnapshotDao.getLastSnapshot(summary);
        if (snapshot == null) {
            return null;
        }
        Trace trace = ObjectMappers.readRequiredValue(mapper,
                TraceSnapshotWriter.toString(snapshot, false), Trace.class);
        trace.setSummary(summary);
        return trace;
    }

    @Nullable
    private Trace getActiveTrace(int timeout, TimeUnit unit, boolean summary) throws Exception {
        Stopwatch stopwatch = new Stopwatch().start();
        Trace trace = null;
        // try at least once (e.g. in case timeoutMillis == 0)
        boolean first = true;
        while (first || stopwatch.elapsed(unit) < timeout) {
            trace = getActiveTrace(summary);
            if (trace != null) {
                break;
            }
            Thread.sleep(20);
            first = false;
        }
        return trace;
    }

    @Nullable
    private Trace getActiveTrace(boolean summary) throws IOException {
        List<io.informant.core.Trace> traces = Lists.newArrayList(traceRegistry.getTraces());
        if (traces.isEmpty()) {
            return null;
        } else if (traces.size() > 1) {
            throw new IllegalStateException("Unexpected number of active traces");
        } else {
            TraceSnapshot snapshot =
                    TraceWriter.toTraceSnapshot(traces.get(0), ticker.read(), summary);
            Trace trace = ObjectMappers.readRequiredValue(mapper,
                    TraceSnapshotWriter.toString(snapshot, true), Trace.class);
            trace.setSummary(summary);
            return trace;
        }
    }

    private void assertNoActiveTraces() throws Exception {
        Stopwatch stopwatch = new Stopwatch().start();
        // if interruptAppUnderTest() was used to terminate an active trace, it may take a few
        // milliseconds to interrupt the thread and end the active trace
        while (stopwatch.elapsed(SECONDS) < 2) {
            int numActiveTraces = Iterables.size(traceRegistry.getTraces());
            if (numActiveTraces == 0) {
                return;
            }
        }
        throw new AssertionError("There are still active traces");
    }

    private static PointcutConfig convertToCore(
            io.informant.config.PointcutConfig coreConfig) {
        List<CaptureItem> captureItems = Lists.newArrayList();
        for (io.informant.config.PointcutConfig.CaptureItem captureItem : coreConfig
                .getCaptureItems()) {
            captureItems.add(CaptureItem.valueOf(captureItem.name()));
        }
        List<MethodModifier> methodModifiers = Lists.newArrayList();
        for (io.informant.api.weaving.MethodModifier methodModifier : coreConfig
                .getMethodModifiers()) {
            methodModifiers.add(MethodModifier.valueOf(methodModifier.name()));
        }

        PointcutConfig config = new PointcutConfig(coreConfig.getVersion());
        config.setCaptureItems(captureItems);
        config.setTypeName(coreConfig.getTypeName());
        config.setMethodName(coreConfig.getMethodName());
        config.setMethodArgTypeNames(coreConfig.getMethodArgTypeNames());
        config.setMethodReturnTypeName(coreConfig.getMethodReturnTypeName());
        config.setMethodModifiers(methodModifiers);
        config.setMetricName(coreConfig.getMetricName());
        config.setSpanTemplate(coreConfig.getSpanTemplate());
        return config;
    }

    private static io.informant.config.PointcutConfig convertToCore(PointcutConfig config) {
        List<io.informant.config.PointcutConfig.CaptureItem> captureItems =
                Lists.newArrayList();
        for (CaptureItem captureItem : config.getCaptureItems()) {
            captureItems.add(io.informant.config.PointcutConfig.CaptureItem
                    .valueOf(captureItem.name()));
        }
        List<io.informant.api.weaving.MethodModifier> methodModifiers = Lists.newArrayList();
        for (MethodModifier methodModifier : config.getMethodModifiers()) {
            methodModifiers.add(io.informant.api.weaving.MethodModifier.valueOf(methodModifier
                    .name()));
        }
        return new io.informant.config.PointcutConfig(captureItems, config.getTypeName(),
                config.getMethodName(), config.getMethodArgTypeNames(),
                config.getMethodReturnTypeName(), methodModifiers, config.getMetricName(),
                config.getSpanTemplate());
    }
}
