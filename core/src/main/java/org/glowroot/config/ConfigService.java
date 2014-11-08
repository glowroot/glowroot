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
package org.glowroot.config;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Charsets;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.PluginServices.ConfigListener;
import org.glowroot.markers.OnlyUsedByTests;

public class ConfigService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigService.class);

    private final PluginDescriptorCache pluginDescriptorCache;
    private final File configFile;
    private final Object writeLock = new Object();

    private final Set<ConfigListener> configListeners = Sets.newCopyOnWriteArraySet();
    private final Multimap<String, ConfigListener> pluginConfigListeners =
            Multimaps.synchronizedMultimap(ArrayListMultimap.<String, ConfigListener>create());

    private volatile Config config;

    ConfigService(File dataDir, PluginDescriptorCache pluginDescriptorCache) {
        logger.debug("<init>()");
        this.pluginDescriptorCache = pluginDescriptorCache;
        configFile = new File(dataDir, "config.json");
        try {
            config = loadConfig(configFile, pluginDescriptorCache.getPluginDescriptors());
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            config = Config.getDefault(pluginDescriptorCache.getPluginDescriptors());
        }
    }

    public TraceConfig getTraceConfig() {
        return config.getTraceConfig();
    }

    public ProfilingConfig getProfilingConfig() {
        return config.getProfilingConfig();
    }

    public UserRecordingConfig getUserRecordingConfig() {
        return config.getUserRecordingConfig();
    }

    public StorageConfig getStorageConfig() {
        return config.getStorageConfig();
    }

    public UserInterfaceConfig getUserInterfaceConfig() {
        return config.getUserInterfaceConfig();
    }

    public AdvancedConfig getAdvancedConfig() {
        return config.getAdvancedConfig();
    }

    @Nullable
    public PluginConfig getPluginConfig(String pluginId) {
        for (PluginConfig pluginConfig : config.getPluginConfigs()) {
            if (pluginId.equals(pluginConfig.getId())) {
                return pluginConfig;
            }
        }
        return null;
    }

    public ImmutableList<MBeanGauge> getMBeanGauges() {
        return config.getMBeanGauges();
    }

    public ImmutableList<CapturePoint> getCapturePoints() {
        return config.getCapturePoints();
    }

    public void addConfigListener(ConfigListener listener) {
        configListeners.add(listener);
    }

    public void addPluginConfigListener(String pluginId, ConfigListener listener) {
        pluginConfigListeners.put(pluginId, listener);
    }

    public String updateTraceConfig(TraceConfig traceConfig, String priorVersion)
            throws OptimisticLockException, IOException {
        boolean notifyPluginConfigListeners;
        synchronized (writeLock) {
            if (!config.getTraceConfig().getVersion().equals(priorVersion)) {
                throw new OptimisticLockException();
            }
            boolean previousEnabled = config.getTraceConfig().isEnabled();
            Config updatedConfig = Config.builder(config)
                    .traceConfig(traceConfig)
                    .build();
            ConfigMapper.writeValue(configFile, updatedConfig);
            config = updatedConfig;
            notifyPluginConfigListeners = config.getTraceConfig().isEnabled() != previousEnabled;
        }
        notifyConfigListeners();
        if (notifyPluginConfigListeners) {
            notifyAllPluginConfigListeners();
        }
        return traceConfig.getVersion();
    }

    public String updateProfilingConfig(ProfilingConfig profilingConfig, String priorVersion)
            throws OptimisticLockException, IOException {
        synchronized (writeLock) {
            if (!config.getProfilingConfig().getVersion().equals(priorVersion)) {
                throw new OptimisticLockException();
            }
            Config updatedConfig = Config.builder(config)
                    .profilingConfig(profilingConfig)
                    .build();
            ConfigMapper.writeValue(configFile, updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return profilingConfig.getVersion();
    }

    public String updateUserRecordingConfig(UserRecordingConfig userRecordingConfig,
            String priorVersion) throws OptimisticLockException, IOException {
        synchronized (writeLock) {
            if (!config.getUserRecordingConfig().getVersion().equals(priorVersion)) {
                throw new OptimisticLockException();
            }
            Config updatedConfig = Config.builder(config)
                    .userRecordingConfig(userRecordingConfig)
                    .build();
            ConfigMapper.writeValue(configFile, updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return userRecordingConfig.getVersion();
    }

    public String updateStorageConfig(StorageConfig storageConfig, String priorVersion)
            throws OptimisticLockException, IOException {
        synchronized (writeLock) {
            if (!config.getStorageConfig().getVersion().equals(priorVersion)) {
                throw new OptimisticLockException();
            }
            Config updatedConfig = Config.builder(config)
                    .storageConfig(storageConfig)
                    .build();
            ConfigMapper.writeValue(configFile, updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return storageConfig.getVersion();
    }

    public String updateUserInterfaceConfig(UserInterfaceConfig userInterfaceConfig,
            String priorVersion) throws OptimisticLockException, IOException {
        synchronized (writeLock) {
            if (!config.getUserInterfaceConfig().getVersion().equals(priorVersion)) {
                throw new OptimisticLockException();
            }
            Config updatedConfig = Config.builder(config)
                    .userInterfaceConfig(userInterfaceConfig)
                    .build();
            ConfigMapper.writeValue(configFile, updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return userInterfaceConfig.getVersion();
    }

    public String updateAdvancedConfig(AdvancedConfig advancedConfig, String priorVersion)
            throws OptimisticLockException, IOException {
        synchronized (writeLock) {
            if (!config.getAdvancedConfig().getVersion().equals(priorVersion)) {
                throw new OptimisticLockException();
            }
            Config updatedConfig = Config.builder(config)
                    .advancedConfig(advancedConfig)
                    .build();
            ConfigMapper.writeValue(configFile, updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return advancedConfig.getVersion();
    }

    public String updatePluginConfig(PluginConfig pluginConfig, String priorVersion)
            throws OptimisticLockException, IOException {
        synchronized (writeLock) {
            List<PluginConfig> pluginConfigs = Lists.newArrayList(config.getPluginConfigs());
            for (ListIterator<PluginConfig> i = pluginConfigs.listIterator(); i.hasNext();) {
                PluginConfig loopPluginConfig = i.next();
                if (pluginConfig.getId().equals(loopPluginConfig.getId())) {
                    if (!loopPluginConfig.getVersion().equals(priorVersion)) {
                        throw new OptimisticLockException();
                    }
                    i.set(pluginConfig);
                }
            }
            Config updatedConfig = Config.builder(config)
                    .pluginConfigs(pluginConfigs)
                    .build();
            ConfigMapper.writeValue(configFile, updatedConfig);
            config = updatedConfig;
        }
        notifyPluginConfigListeners(pluginConfig.getId());
        return pluginConfig.getVersion();
    }

    public String insertMBeanGauge(MBeanGauge mbeanGauge) throws IOException,
            DuplicateMBeanObjectNameException {
        synchronized (writeLock) {
            List<MBeanGauge> mbeanGauges = Lists.newArrayList(config.getMBeanGauges());
            // check for duplicate mbeanObjectName
            for (MBeanGauge loopMBeanGauge : mbeanGauges) {
                if (loopMBeanGauge.getMBeanObjectName().equals(mbeanGauge.getMBeanObjectName())) {
                    throw new DuplicateMBeanObjectNameException();
                }
            }
            mbeanGauges.add(mbeanGauge);
            Config updatedConfig = Config.builder(config)
                    .mbeanGauges(mbeanGauges)
                    .build();
            ConfigMapper.writeValue(configFile, updatedConfig);
            config = updatedConfig;
        }
        return mbeanGauge.getVersion();
    }

    public String updateMBeanGauge(String priorVersion, MBeanGauge mbeanGauge)
            throws IOException {
        synchronized (writeLock) {
            List<MBeanGauge> mbeanGauges = Lists.newArrayList(config.getMBeanGauges());
            boolean found = false;
            for (ListIterator<MBeanGauge> i = mbeanGauges.listIterator(); i.hasNext();) {
                if (priorVersion.equals(i.next().getVersion())) {
                    i.set(mbeanGauge);
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IOException("Gauge config not found: " + priorVersion);
            }
            Config updatedConfig = Config.builder(config)
                    .mbeanGauges(mbeanGauges)
                    .build();
            ConfigMapper.writeValue(configFile, updatedConfig);
            config = updatedConfig;
        }
        return mbeanGauge.getVersion();
    }

    public void deleteMBeanGauge(String version) throws IOException {
        synchronized (writeLock) {
            List<MBeanGauge> mbeanGauges = Lists.newArrayList(config.getMBeanGauges());
            boolean found = false;
            for (ListIterator<MBeanGauge> i = mbeanGauges.listIterator(); i.hasNext();) {
                if (version.equals(i.next().getVersion())) {
                    i.remove();
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IOException("Gauge config not found: " + version);
            }
            Config updatedConfig = Config.builder(config)
                    .mbeanGauges(mbeanGauges)
                    .build();
            ConfigMapper.writeValue(configFile, updatedConfig);
            config = updatedConfig;
        }
    }

    public String insertCapturePoint(CapturePoint capturePoint) throws IOException {
        synchronized (writeLock) {
            List<CapturePoint> capturePoints = Lists.newArrayList(config.getCapturePoints());
            capturePoints.add(capturePoint);
            Config updatedConfig = Config.builder(config)
                    .capturePoints(capturePoints)
                    .build();
            ConfigMapper.writeValue(configFile, updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return capturePoint.getVersion();
    }

    public String updateCapturePoint(String priorVersion, CapturePoint capturePoint)
            throws IOException {
        synchronized (writeLock) {
            List<CapturePoint> capturePoints = Lists.newArrayList(config.getCapturePoints());
            boolean found = false;
            for (ListIterator<CapturePoint> i = capturePoints.listIterator(); i.hasNext();) {
                if (priorVersion.equals(i.next().getVersion())) {
                    i.set(capturePoint);
                    found = true;
                    break;
                }
            }
            if (!found) {
                logger.warn("aspect config unique hash not found: {}", priorVersion);
                return priorVersion;
            }
            Config updatedConfig = Config.builder(config)
                    .capturePoints(capturePoints)
                    .build();
            ConfigMapper.writeValue(configFile, updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
        return capturePoint.getVersion();
    }

    public void deleteCapturePoint(String version) throws IOException {
        synchronized (writeLock) {
            List<CapturePoint> capturePoints = Lists.newArrayList(config.getCapturePoints());
            boolean found = false;
            for (ListIterator<CapturePoint> i = capturePoints.listIterator(); i.hasNext();) {
                if (version.equals(i.next().getVersion())) {
                    i.remove();
                    found = true;
                    break;
                }
            }
            if (!found) {
                logger.warn("aspect config version not found: {}", version);
                return;
            }
            Config updatedConfig = Config.builder(config)
                    .capturePoints(capturePoints)
                    .build();
            ConfigMapper.writeValue(configFile, updatedConfig);
            config = updatedConfig;
        }
        notifyConfigListeners();
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
        if (!configFile.delete()) {
            throw new IOException("Could not delete file: " + configFile.getCanonicalPath());
        }
        config = loadConfig(configFile, pluginDescriptorCache.getPluginDescriptors());
        notifyConfigListeners();
        notifyAllPluginConfigListeners();
    }

    // the 2 methods below are only used by test harness (LocalContainer), so that tests will still
    // succeed even if core is shaded (e.g. compiled from maven) and test-harness is compiled
    // against unshaded core (e.g. compiled previously in IDE)
    //
    // don't return ImmutableList
    @OnlyUsedByTests
    public List<MBeanGauge> getMBeanGaugesNeverShaded() {
        return getMBeanGauges();
    }

    // don't return ImmutableList, see comment above
    @OnlyUsedByTests
    public List<CapturePoint> getCapturePointsNeverShaded() {
        return config.getCapturePoints();
    }

    private static Config loadConfig(File configFile,
            ImmutableList<PluginDescriptor> pluginDescriptors) throws IOException {
        if (!configFile.exists()) {
            Config config = Config.getDefault(pluginDescriptors);
            ConfigMapper.writeValue(configFile, config);
            return config;
        }
        String content = Files.toString(configFile, Charsets.UTF_8);
        Config config;
        String warningMessage = null;
        try {
            config = new ConfigMapper(pluginDescriptors).readValue(content);
        } catch (JsonProcessingException e) {
            logger.warn("error processing config file: {}", configFile.getAbsolutePath(), e);
            File backupFile = new File(configFile.getParentFile(), configFile.getName()
                    + ".invalid-orig");
            config = Config.getDefault(pluginDescriptors);
            try {
                Files.copy(configFile, backupFile);
                warningMessage = "due to an error in the config file, it has been backed up to"
                        + " extension '.invalid-orig' and overwritten with the default config";
            } catch (IOException f) {
                logger.warn("error making a copy of the invalid config file before overwriting it",
                        f);
                warningMessage = "due to an error in the config file, it has been overwritten with"
                        + " the default config";
            }
        }
        // it's nice to update config.json on startup if it is missing some/all config
        // properties so that the file contents can be reviewed/updated/copied if desired
        writeToFileIfNeeded(config, configFile, content);
        if (warningMessage != null) {
            logger.warn(warningMessage);
        }
        return config;
    }

    private static void writeToFileIfNeeded(Config config, File configFile, String existingContent)
            throws IOException {
        String content = ConfigMapper.writeValueAsString(config);
        if (content.equals(existingContent)) {
            // it's nice to preserve the correct modification stamp on the file to track when it was
            // last really changed
            return;
        }
        Files.write(content, configFile, Charsets.UTF_8);
    }

    @SuppressWarnings("serial")
    public static class OptimisticLockException extends Exception {}

    @SuppressWarnings("serial")
    public static class DuplicateMBeanObjectNameException extends Exception {}
}
