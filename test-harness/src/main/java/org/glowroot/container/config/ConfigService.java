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

import javax.annotation.Nullable;

public interface ConfigService {

    void setPluginProperty(String pluginId, String propertyName, @Nullable Object propertyValue)
            throws Exception;

    TraceConfig getTraceConfig() throws Exception;

    void updateTraceConfig(TraceConfig config) throws Exception;

    ProfilingConfig getProfilingConfig() throws Exception;

    void updateProfilingConfig(ProfilingConfig config) throws Exception;

    UserRecordingConfig getUserRecordingConfig() throws Exception;

    void updateUserRecordingConfig(UserRecordingConfig config) throws Exception;

    StorageConfig getStorageConfig() throws Exception;

    void updateStorageConfig(StorageConfig config) throws Exception;

    UserInterfaceConfig getUserInterfaceConfig() throws Exception;

    // throws CurrentPasswordIncorrectException
    void updateUserInterfaceConfig(UserInterfaceConfig config) throws Exception;

    AdvancedConfig getAdvancedConfig() throws Exception;

    void updateAdvancedConfig(AdvancedConfig config) throws Exception;

    @Nullable
    PluginConfig getPluginConfig(String pluginId) throws Exception;

    void updatePluginConfig(String pluginId, PluginConfig config) throws Exception;

    List<Gauge> getGauges() throws Exception;

    Gauge addGauge(Gauge gauge) throws Exception;

    Gauge updateGauge(Gauge gauge) throws Exception;

    void removeGauge(String version) throws Exception;

    List<CapturePoint> getCapturePoints() throws Exception;

    CapturePoint addCapturePoint(CapturePoint capturePoint) throws Exception;

    CapturePoint updateCapturePoint(CapturePoint capturePoint) throws Exception;

    void removeCapturePoint(String version) throws Exception;

    int reweavePointcuts() throws Exception;

    // TODO move to TraceService
    void compactData() throws Exception;

    @SuppressWarnings("serial")
    class CurrentPasswordIncorrectException extends Exception {}

    @SuppressWarnings("serial")
    class PortChangeFailedException extends Exception {}
}
