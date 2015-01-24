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
package org.glowroot.config;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.PluginServices.ConfigListener;
import org.glowroot.markers.OnlyUsedByTests;

import static com.google.common.base.Preconditions.checkState;

public class ConfigService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigService.class);

    private final ConfigFile configFile;
    private final Object writeLock = new Object();

    private final Set<ConfigListener> configListeners = Sets.newCopyOnWriteArraySet();
    private final Multimap<String, ConfigListener> pluginConfigListeners =
            Multimaps.synchronizedMultimap(ArrayListMultimap.<String, ConfigListener>create());

    private volatile Config config;

    ConfigService(File dataDir, List<PluginDescriptor> pluginDescriptors) {
        configFile = new ConfigFile(new File(dataDir, "config.json"), pluginDescriptors);
        try {
            config = configFile.loadConfig();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            config = configFile.getDefaultConfig();
        }
    }

    public TraceConfig getTraceConfig() {
        return config.traceConfig();
    }

    public ProfilingConfig getProfilingConfig() {
        return config.profilingConfig();
    }

    public UserRecordingConfig getUserRecordingConfig() {
        return config.userRecordingConfig();
    }

    public StorageConfig getStorageConfig() {
        return config.storageConfig();
    }

    public UserInterfaceConfig getUserInterfaceConfig() {
        return config.userInterfaceConfig();
    }

    public AdvancedConfig getAdvancedConfig() {
        return config.advancedConfig();
    }

    public @Nullable PluginConfig getPluginConfig(String pluginId) {
        for (PluginConfig pluginConfig : config.pluginConfigs()) {
            if (pluginId.equals(pluginConfig.id())) {
                return pluginConfig;
            }
        }
        return null;
    }

    public List<Gauge> getGauges() {
        return config.gauges();
    }

    public @Nullable Gauge getGauge(String version) {
        for (Gauge gauge : config.gauges()) {
            if (gauge.version().equals(version)) {
                return gauge;
            }
        }
        return null;
    }

    public List<CapturePoint> getCapturePoints() {
        return config.capturePoints();
    }

    public @Nullable CapturePoint getCapturePoint(String version) {
        for (CapturePoint capturePoint : config.capturePoints()) {
            if (capturePoint.version().equals(version)) {
                return capturePoint;
            }
        }
        return null;
    }

    public void addConfigListener(ConfigListener listener) {
        configListeners.add(listener);
    }

    public void addPluginConfigListener(String pluginId, ConfigListener listener) {
        pluginConfigListeners.put(pluginId, listener);
    }

    public String updateTraceConfig(TraceConfig traceConfig, String priorVersion) throws Exception {
        boolean notifyPluginConfigListeners;
        synchronized (writeLock) {
            checkVersionsEqual(config.traceConfig().version(), priorVersion);
            boolean previousEnabled = config.traceConfig().enabled();
            Config updatedConfig = ((ImmutableConfig) config).withTraceConfig(traceConfig);
            configFile.write(updatedConfig);
            config = updatedConfig;
            notifyPluginConfigListeners = config.traceConfig().enabled() != previousEnabled;
        }
        notifyConfigListeners();
        if (notifyPluginConfigListeners) {
            notifyAllPluginConfigListeners();
        }
        return traceConfig.version();
    }

    public String updateProfilingConfig(ProfilingConfig profilingConfig, String priorVersion)
            throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(config.profilingConfig().version(), priorVersion);
            Config updatedConfig = ((ImmutableConfig) config).withProfilingConfig(profilingConfig);
            configFile.write(updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return profilingConfig.version();
    }

    public String updateUserRecordingConfig(UserRecordingConfig userRecordingConfig,
            String priorVersion) throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(config.userRecordingConfig().version(), priorVersion);
            Config updatedConfig =
                    ((ImmutableConfig) config).withUserRecordingConfig(userRecordingConfig);
            configFile.write(updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return userRecordingConfig.version();
    }

    public String updateStorageConfig(StorageConfig storageConfig, String priorVersion)
            throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(config.storageConfig().version(), priorVersion);
            Config updatedConfig = ((ImmutableConfig) config).withStorageConfig(storageConfig);
            configFile.write(updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return storageConfig.version();
    }

    public String updateUserInterfaceConfig(UserInterfaceConfig userInterfaceConfig,
            String priorVersion) throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(config.userInterfaceConfig().version(), priorVersion);
            Config updatedConfig =
                    ((ImmutableConfig) config).withUserInterfaceConfig(userInterfaceConfig);
            configFile.write(updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return userInterfaceConfig.version();
    }

    public String updateAdvancedConfig(AdvancedConfig advancedConfig, String priorVersion)
            throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(config.advancedConfig().version(), priorVersion);
            Config updatedConfig = ((ImmutableConfig) config).withAdvancedConfig(advancedConfig);
            configFile.write(updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return advancedConfig.version();
    }

    public String updatePluginConfig(PluginConfig pluginConfig, String priorVersion)
            throws Exception {
        synchronized (writeLock) {
            List<PluginConfig> pluginConfigs = Lists.newArrayList(config.pluginConfigs());
            boolean found = false;
            for (ListIterator<PluginConfig> i = pluginConfigs.listIterator(); i.hasNext();) {
                PluginConfig loopPluginConfig = i.next();
                if (pluginConfig.id().equals(loopPluginConfig.id())) {
                    checkVersionsEqual(loopPluginConfig.version(), priorVersion);
                    i.set(pluginConfig);
                    found = true;
                    break;
                }
            }
            checkState(found, "Plugin config not found: %s", pluginConfig.id());
            Config updatedConfig = ((ImmutableConfig) config).withPluginConfigs(pluginConfigs);
            configFile.write(updatedConfig);
            config = updatedConfig;
        }
        notifyPluginConfigListeners(pluginConfig.id());
        return pluginConfig.version();
    }

    public String insertGauge(Gauge gauge) throws Exception {
        synchronized (writeLock) {
            List<Gauge> gauges = Lists.newArrayList(config.gauges());
            // check for duplicate mbeanObjectName
            for (Gauge loopGauge : gauges) {
                if (loopGauge.mbeanObjectName().equals(gauge.mbeanObjectName())) {
                    throw new DuplicateMBeanObjectNameException();
                }
            }
            gauges.add(gauge);
            Config updatedConfig = ((ImmutableConfig) config).withGauges(gauges);
            configFile.write(updatedConfig);
            config = updatedConfig;
        }
        return gauge.version();
    }

    public String updateGauge(Gauge gauge, String priorVersion)
            throws IOException {
        synchronized (writeLock) {
            List<Gauge> gauges = Lists.newArrayList(config.gauges());
            boolean found = false;
            for (ListIterator<Gauge> i = gauges.listIterator(); i.hasNext();) {
                if (priorVersion.equals(i.next().version())) {
                    i.set(gauge);
                    found = true;
                    break;
                }
            }
            checkState(found, "Gauge config not found: %s", priorVersion);
            Config updatedConfig = ((ImmutableConfig) config).withGauges(gauges);
            configFile.write(updatedConfig);
            config = updatedConfig;
        }
        return gauge.version();
    }

    public void deleteGauge(String version) throws IOException {
        synchronized (writeLock) {
            List<Gauge> gauges = Lists.newArrayList(config.gauges());
            boolean found = false;
            for (ListIterator<Gauge> i = gauges.listIterator(); i.hasNext();) {
                if (version.equals(i.next().version())) {
                    i.remove();
                    found = true;
                    break;
                }
            }
            checkState(found, "Gauge config not found: %s", version);
            Config updatedConfig = ((ImmutableConfig) config).withGauges(gauges);
            configFile.write(updatedConfig);
            config = updatedConfig;
        }
    }

    public String insertCapturePoint(CapturePoint capturePoint) throws IOException {
        synchronized (writeLock) {
            List<CapturePoint> capturePoints = Lists.newArrayList(config.capturePoints());
            capturePoints.add(capturePoint);
            Config updatedConfig = ((ImmutableConfig) config).withCapturePoints(capturePoints);
            configFile.write(updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return capturePoint.version();
    }

    public String updateCapturePoint(CapturePoint capturePoint, String priorVersion)
            throws IOException {
        synchronized (writeLock) {
            List<CapturePoint> capturePoints = Lists.newArrayList(config.capturePoints());
            boolean found = false;
            for (ListIterator<CapturePoint> i = capturePoints.listIterator(); i.hasNext();) {
                if (priorVersion.equals(i.next().version())) {
                    i.set(capturePoint);
                    found = true;
                    break;
                }
            }
            if (!found) {
                logger.warn("aspect config unique hash not found: {}", priorVersion);
                return priorVersion;
            }
            Config updatedConfig = ((ImmutableConfig) config).withCapturePoints(capturePoints);
            configFile.write(updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return capturePoint.version();
    }

    public void deleteCapturePoint(String version) throws IOException {
        synchronized (writeLock) {
            List<CapturePoint> capturePoints = Lists.newArrayList(config.capturePoints());
            boolean found = false;
            for (ListIterator<CapturePoint> i = capturePoints.listIterator(); i.hasNext();) {
                if (version.equals(i.next().version())) {
                    i.remove();
                    found = true;
                    break;
                }
            }
            if (!found) {
                logger.warn("aspect config version not found: {}", version);
                return;
            }
            Config updatedConfig = ((ImmutableConfig) config).withCapturePoints(capturePoints);
            configFile.write(updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
    }

    private void checkVersionsEqual(String version, String priorVersion)
            throws OptimisticLockException {
        if (!version.equals(priorVersion)) {
            throw new OptimisticLockException();
        }
    }

    // the updated config is not passed to the listeners to avoid the race condition of multiple
    // config updates being sent out of order, instead listeners must call get*Config() which will
    // never return the updates out of order (at worst it may return the most recent update twice
    // which is ok)
    private void notifyConfigListeners() {
        for (ConfigListener configListener : configListeners) {
            configListener.onChange();
        }
    }

    private void notifyPluginConfigListeners(String pluginId) {
        for (ConfigListener configListener : pluginConfigListeners.get(pluginId)) {
            configListener.onChange();
        }
    }

    private void notifyAllPluginConfigListeners() {
        for (ConfigListener configListener : pluginConfigListeners.values()) {
            configListener.onChange();
        }
    }

    @OnlyUsedByTests
    public void resetAllConfig() throws IOException {
        configFile.delete();
        config = configFile.loadConfig();
        notifyConfigListeners();
        notifyAllPluginConfigListeners();
    }

    @SuppressWarnings("serial")
    public static class OptimisticLockException extends Exception {}

    @SuppressWarnings("serial")
    public static class DuplicateMBeanObjectNameException extends Exception {}
}
