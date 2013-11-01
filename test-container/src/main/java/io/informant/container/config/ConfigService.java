/*
 * Copyright 2013 the original author or authors.
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
package io.informant.container.config;

import java.util.List;

import checkers.nullness.quals.Nullable;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public interface ConfigService {

    void setStoreThresholdMillis(int storeThresholdMillis) throws Exception;

    GeneralConfig getGeneralConfig() throws Exception;

    String updateGeneralConfig(GeneralConfig config) throws Exception;

    CoarseProfilingConfig getCoarseProfilingConfig() throws Exception;

    String updateCoarseProfilingConfig(CoarseProfilingConfig config) throws Exception;

    FineProfilingConfig getFineProfilingConfig() throws Exception;

    String updateFineProfilingConfig(FineProfilingConfig config) throws Exception;

    UserOverridesConfig getUserOverridesConfig() throws Exception;

    String updateUserOverridesConfig(UserOverridesConfig config) throws Exception;

    StorageConfig getStorageConfig() throws Exception;

    String updateStorageConfig(StorageConfig config) throws Exception;

    UserInterfaceConfig getUserInterfaceConfig() throws Exception;

    // throws CurrentPasswordIncorrectException
    String updateUserInterfaceConfig(UserInterfaceConfig config) throws Exception;

    AdvancedConfig getAdvancedConfig() throws Exception;

    String updateAdvancedConfig(AdvancedConfig config) throws Exception;

    @Nullable
    PluginConfig getPluginConfig(String pluginId) throws Exception;

    String updatePluginConfig(String pluginId, PluginConfig config) throws Exception;

    List<AdhocPointcutConfig> getAdhocPointcutConfigs() throws Exception;

    String addAdhocPointcutConfig(AdhocPointcutConfig adhocPointcutConfig) throws Exception;

    String updateAdhocPointcutConfig(String version, AdhocPointcutConfig adhocPointcutConfig)
            throws Exception;

    void removeAdhocPointcutConfig(String version) throws Exception;

    void reweaveAdhocPointcuts() throws Exception;

    void compactData() throws Exception;

    @SuppressWarnings("serial")
    public class CurrentPasswordIncorrectException extends Exception {}
}
