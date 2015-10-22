/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.agent.it.harness.impl;

import java.io.IOException;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.it.harness.model.ConfigUpdate.AdvancedConfigUpdate;
import org.glowroot.agent.it.harness.model.ConfigUpdate.InstrumentationConfig;
import org.glowroot.agent.it.harness.model.ConfigUpdate.MethodModifier;
import org.glowroot.agent.it.harness.model.ConfigUpdate.OptionalDouble;
import org.glowroot.agent.it.harness.model.ConfigUpdate.PluginConfigUpdate;
import org.glowroot.agent.it.harness.model.ConfigUpdate.PluginProperty;
import org.glowroot.agent.it.harness.model.ConfigUpdate.TransactionConfigUpdate;
import org.glowroot.agent.it.harness.model.ConfigUpdate.UserRecordingConfigUpdate;
import org.glowroot.common.config.ImmutableAdvancedConfig;
import org.glowroot.common.config.ImmutableInstrumentationConfig;
import org.glowroot.common.config.ImmutablePluginConfig;
import org.glowroot.common.config.ImmutableTransactionConfig;
import org.glowroot.common.config.ImmutableUserRecordingConfig;
import org.glowroot.common.config.InstrumentationConfig.CaptureKind;
import org.glowroot.common.config.PluginConfig;
import org.glowroot.common.config.PropertyValue;
import org.glowroot.common.live.LiveWeavingService;

import static com.google.common.base.Preconditions.checkState;

public class ConfigUpdateServiceHelper {

    private final ConfigService configService;
    private final @Nullable LiveWeavingService liveWeavingService;

    public ConfigUpdateServiceHelper(ConfigService configService,
            @Nullable LiveWeavingService liveWeavingService) {
        this.configService = configService;
        this.liveWeavingService = liveWeavingService;
    }

    public void updateTransactionConfig(TransactionConfigUpdate request) throws IOException {
        ImmutableTransactionConfig.Builder builder =
                ImmutableTransactionConfig.builder().copyFrom(configService.getTransactionConfig());
        if (request.hasSlowThresholdMillis()) {
            builder.slowThresholdMillis(request.getSlowThresholdMillis().getValue());
        }
        if (request.hasProfilingIntervalMillis()) {
            builder.profilingIntervalMillis(request.getProfilingIntervalMillis().getValue());
        }
        configService.updateTransactionConfig(builder.build());
    }

    public void updateUserRecordingConfig(UserRecordingConfigUpdate request) throws IOException {
        ImmutableUserRecordingConfig.Builder builder = ImmutableUserRecordingConfig.builder()
                .copyFrom(configService.getUserRecordingConfig());
        if (request.hasUsers()) {
            builder.users(request.getUsers().getValueList());
        }
        if (request.hasProfilingIntervalMillis()) {
            builder.profilingIntervalMillis(request.getProfilingIntervalMillis().getValue());
        }
        configService.updateUserRecordingConfig(builder.build());
    }

    public void updateAdvancedConfig(AdvancedConfigUpdate request) throws IOException {
        ImmutableAdvancedConfig.Builder builder = ImmutableAdvancedConfig.builder()
                .copyFrom(configService.getAdvancedConfig());
        if (request.hasTimerWrapperMethods()) {
            builder.timerWrapperMethods(request.getTimerWrapperMethods().getValue());
        }
        if (request.hasImmediatePartialStoreThresholdSeconds()) {
            builder.immediatePartialStoreThresholdSeconds(
                    request.getImmediatePartialStoreThresholdSeconds().getValue());
        }
        if (request.hasMaxTraceEntriesPerTransaction()) {
            builder.maxTraceEntriesPerTransaction(
                    request.getMaxTraceEntriesPerTransaction().getValue());
        }
        if (request.hasCaptureThreadInfo()) {
            builder.captureThreadInfo(request.getCaptureThreadInfo().getValue());
        }
        if (request.hasCaptureGcActivity()) {
            builder.captureGcActivity(request.getCaptureGcActivity().getValue());
        }
        configService.updateAdvancedConfig(builder.build());
    }

    public void updatePluginConfig(PluginConfigUpdate request) throws IOException {
        String pluginId = request.getId();
        PluginConfig existing = configService.getPluginConfig(pluginId);
        ImmutablePluginConfig.Builder builder = ImmutablePluginConfig.builder().copyFrom(existing);
        if (request.hasEnabled()) {
            builder.enabled(request.getEnabled().getValue());
        }
        Map<String, PropertyValue> properties = Maps.newHashMap(existing.properties());
        for (PluginProperty prop : request.getPropertyList()) {
            switch (prop.getValCase()) {
                case BVAL:
                    properties.put(prop.getName(), new PropertyValue(prop.getBval()));
                    break;
                case DVAL:
                    OptionalDouble dval = prop.getDval();
                    if (dval.getAbsent()) {
                        properties.put(prop.getName(), new PropertyValue(null));
                    } else {
                        properties.put(prop.getName(), new PropertyValue(dval.getValue()));
                    }
                    break;
                case SVAL:
                    properties.put(prop.getName(), new PropertyValue(prop.getSval()));
                    break;
                default:
                    throw new IllegalStateException(
                            "Unexpected plugin property type: " + prop.getValCase());
            }
        }
        builder.properties(properties);
        List<PluginConfig> configs = Lists.newArrayList(configService.getPluginConfigs());
        boolean found = false;
        for (ListIterator<PluginConfig> i = configs.listIterator(); i.hasNext();) {
            PluginConfig loopPluginConfig = i.next();
            if (loopPluginConfig.id().equals(pluginId)) {
                i.set(builder.build());
                found = true;
                break;
            }
        }
        checkState(found, "Plugin config not found: %s", pluginId);
        configService.updatePluginConfigs(configs);
    }

    public int updateInstrumentationConfigs(List<InstrumentationConfig> protos)
            throws Exception {
        List<org.glowroot.common.config.InstrumentationConfig> instrumentationConfigs =
                Lists.newArrayList();
        for (InstrumentationConfig proto : protos) {
            instrumentationConfigs.add(toCommon(proto));
        }
        configService.updateInstrumentationConfigs(instrumentationConfigs);
        if (liveWeavingService == null) {
            return 0;
        }
        return liveWeavingService.reweave("");
    }

    private static org.glowroot.common.config.InstrumentationConfig toCommon(
            InstrumentationConfig proto) {
        ImmutableInstrumentationConfig.Builder builder = ImmutableInstrumentationConfig.builder();
        builder.className(proto.getClassName());
        builder.methodName(proto.getMethodName());
        builder.addAllMethodParameterTypes(proto.getMethodParameterTypeList());
        builder.methodReturnType(proto.getMethodReturnType());
        for (MethodModifier methodModifier : proto.getMethodModifierList()) {
            builder.addMethodModifiers(
                    org.glowroot.common.config.InstrumentationConfig.MethodModifier
                            .valueOf(methodModifier.name()));
        }
        builder.captureKind(CaptureKind.valueOf(proto.getCaptureKind().name()));
        builder.transactionType(proto.getTransactionType());
        builder.transactionNameTemplate(proto.getTransactionNameTemplate());
        builder.transactionUserTemplate(proto.getTransactionUserTemplate());
        builder.putAllTransactionAttributeTemplates(proto.getTransactionAttributeTemplates());
        if (proto.hasTransactionSlowThresholdMillis()) {
            builder.transactionSlowThresholdMillis(
                    proto.getTransactionSlowThresholdMillis().getValue());
        }
        if (proto.hasTraceEntryStackThresholdMillis()) {
            builder.traceEntryStackThresholdMillis(
                    proto.getTraceEntryStackThresholdMillis().getValue());
        }
        builder.traceEntryMessageTemplate(proto.getTraceEntryMessageTemplate());
        builder.traceEntryCaptureSelfNested(proto.getTraceEntryCaptureSelfNested());
        builder.timerName(proto.getTimerName());
        builder.enabledProperty(proto.getEnabledProperty());
        builder.traceEntryEnabledProperty(proto.getTraceEntryEnabledProperty());
        return builder.build();
    }
}
