/*
 * Copyright 2013-2014 the original author or authors.
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
package org.glowroot.container.config;

import java.util.List;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public interface ConfigService {

    void setPluginProperty(String pluginId, String propertyName, @Nullable Object propertyValue)
            throws Exception;

    GeneralConfig getGeneralConfig() throws Exception;

    void updateGeneralConfig(GeneralConfig config) throws Exception;

    ProfilingConfig getProfilingConfig() throws Exception;

    void updateProfilingConfig(ProfilingConfig config) throws Exception;

    OutlierProfilingConfig getOutlierProfilingConfig() throws Exception;

    void updateOutlierProfilingConfig(OutlierProfilingConfig config) throws Exception;

    UserTracingConfig getUserTracingConfig() throws Exception;

    void updateUserTracingConfig(UserTracingConfig config) throws Exception;

    StorageConfig getStorageConfig() throws Exception;

    void updateStorageConfig(StorageConfig config) throws Exception;

    UserInterfaceConfig getUserInterfaceConfig() throws Exception;

    // throws CurrentPasswordIncorrectException
    void updateUserInterfaceConfig(UserInterfaceConfig config) throws Exception;

    List<PointcutConfig> getPointcutConfigs() throws Exception;

    String addPointcutConfig(PointcutConfig pointcutConfig) throws Exception;

    void updatePointcutConfig(String version, PointcutConfig pointcutConfig) throws Exception;

    void removePointcutConfig(String version) throws Exception;

    AdvancedConfig getAdvancedConfig() throws Exception;

    void updateAdvancedConfig(AdvancedConfig config) throws Exception;

    @Nullable
    PluginConfig getPluginConfig(String pluginId) throws Exception;

    void updatePluginConfig(String pluginId, PluginConfig config) throws Exception;

    int reweavePointcuts() throws Exception;

    // TODO move to TraceService
    void compactData() throws Exception;

    @SuppressWarnings("serial")
    class CurrentPasswordIncorrectException extends Exception {}

    @SuppressWarnings("serial")
    class PortChangeFailedException extends Exception {}
}
