/*
 * Copyright 2013-2016 the original author or authors.
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
package org.glowroot.ui;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.primitives.Ints;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.live.LiveJvmService.AgentNotConnectedException;
import org.glowroot.common.live.LiveWeavingService;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.Versions;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig.CaptureKind;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.InstrumentationConfig.MethodModifier;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.GlobalMeta;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MethodSignature;
import org.glowroot.wire.api.model.Proto.OptionalInt32;

@JsonService
class InstrumentationConfigJsonService {

    private static final Logger logger =
            LoggerFactory.getLogger(InstrumentationConfigJsonService.class);

    private static final ObjectMapper mapper = ObjectMappers.create();

    private static final Ordering<InstrumentationConfig> ordering =
            new InstrumentationConfigOrdering();

    private final ConfigRepository configRepository;
    private final LiveWeavingService liveWeavingService;

    InstrumentationConfigJsonService(ConfigRepository configRepository,
            LiveWeavingService liveWeavingService) {
        this.configRepository = configRepository;
        this.liveWeavingService = liveWeavingService;
    }

    @GET("/backend/config/instrumentation")
    String getInstrumentationConfig(String queryString) throws Exception {
        InstrumentationConfigRequest request =
                QueryStrings.decode(queryString, InstrumentationConfigRequest.class);
        String agentId = request.agentId();
        Optional<String> version = request.version();
        if (version.isPresent()) {
            return getInstrumentationConfigInternal(agentId, version.get());
        } else {
            List<InstrumentationConfig> configs =
                    configRepository.getInstrumentationConfigs(agentId);
            configs = ordering.immutableSortedCopy(configs);
            List<InstrumentationConfigDto> dtos = Lists.newArrayList();
            for (InstrumentationConfig config : configs) {
                dtos.add(InstrumentationConfigDto.create(config));
            }
            GlobalMeta globalMeta;
            try {
                globalMeta = liveWeavingService.getGlobalMeta(agentId);
            } catch (AgentNotConnectedException e) {
                logger.debug(e.getMessage(), e);
                globalMeta = null;
            }
            return mapper.writeValueAsString(ImmutableInstrumentationListResponse.builder()
                    .addAllConfigs(dtos)
                    .jvmOutOfSync(globalMeta != null && globalMeta.getJvmOutOfSync())
                    .jvmRetransformClassesSupported(
                            globalMeta != null && globalMeta.getJvmRetransformClassesSupported())
                    .build());
        }
    }

    // this is marked as @GET so it can be used without update rights (e.g. demo instance)
    @GET("/backend/config/preload-classpath-cache")
    void preloadClasspathCache(String queryString) throws Exception {
        final String agentId = getAgentId(queryString);
        // HttpServer is configured with a very small thread pool to keep number of threads down
        // (currently only a single thread), so spawn a background thread to perform the preloading
        // so it doesn't block other http requests
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    liveWeavingService.preloadClasspathCache(agentId);
                } catch (AgentNotConnectedException e) {
                    logger.debug(e.getMessage(), e);
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
            }
        });
        thread.setDaemon(true);
        thread.setName("Glowroot-Temporary-Thread");
        thread.start();
    }

    @GET("/backend/config/matching-class-names")
    String getMatchingClassNames(String queryString) throws Exception {
        ClassNamesRequest request = QueryStrings.decode(queryString, ClassNamesRequest.class);
        return mapper.writeValueAsString(liveWeavingService.getMatchingClassNames(
                request.agentId(), request.partialClassName(), request.limit()));
    }

    @GET("/backend/config/matching-method-names")
    String getMatchingMethodNames(String queryString) throws Exception {
        MethodNamesRequest request = QueryStrings.decode(queryString, MethodNamesRequest.class);
        List<String> matchingMethodNames =
                liveWeavingService.getMatchingMethodNames(request.agentId(), request.className(),
                        request.partialMethodName(), request.limit());
        return mapper.writeValueAsString(matchingMethodNames);
    }

    @GET("/backend/config/method-signatures")
    String getMethodSignatures(String queryString) throws Exception {
        MethodSignaturesRequest request =
                QueryStrings.decode(queryString, MethodSignaturesRequest.class);
        List<MethodSignature> signatures = liveWeavingService
                .getMethodSignatures(request.agentId(), request.className(), request.methodName());
        List<MethodSignatureDto> methodSignatures = Lists.newArrayList();
        for (MethodSignature signature : signatures) {
            methodSignatures.add(MethodSignatureDto.create(signature));
        }
        return mapper.writeValueAsString(methodSignatures);
    }

    @POST("/backend/config/instrumentation/add")
    String addInstrumentationConfig(String content) throws Exception {
        InstrumentationConfigDto configDto =
                mapper.readValue(content, ImmutableInstrumentationConfigDto.class);
        String agentId = configDto.agentId().get();
        InstrumentationConfig config = configDto.convert();
        configRepository.insertInstrumentationConfig(agentId, config);
        return getInstrumentationConfigInternal(agentId, Versions.getVersion(config));
    }

    @POST("/backend/config/instrumentation/update")
    String updateInstrumentationConfig(String content) throws Exception {
        InstrumentationConfigDto configDto =
                mapper.readValue(content, ImmutableInstrumentationConfigDto.class);
        String agentId = configDto.agentId().get();
        InstrumentationConfig config = configDto.convert();
        String version = configDto.version().get();
        configRepository.updateInstrumentationConfig(agentId, config, version);
        return getInstrumentationConfigInternal(agentId, Versions.getVersion(config));
    }

    @POST("/backend/config/instrumentation/remove")
    void removeInstrumentationConfig(String content) throws Exception {
        InstrumentationDeleteRequest request =
                mapper.readValue(content, ImmutableInstrumentationDeleteRequest.class);
        configRepository.deleteInstrumentationConfigs(request.agentId(), request.versions());
    }

    @POST("/backend/config/instrumentation/import")
    void importInstrumentationConfig(String content) throws Exception {
        InstrumentationImportRequest request =
                mapper.readValue(content, ImmutableInstrumentationImportRequest.class);
        String agentId = request.agentId();
        List<InstrumentationConfig> configs = Lists.newArrayList();
        for (InstrumentationConfigDto configDto : request.configs()) {
            configs.add(configDto.convert());
        }
        configRepository.insertInstrumentationConfigs(agentId, configs);
    }

    private String getInstrumentationConfigInternal(String agentId, String version)
            throws Exception {
        InstrumentationConfig config = configRepository.getInstrumentationConfig(agentId, version);
        if (config == null) {
            throw new JsonServiceException(HttpResponseStatus.NOT_FOUND);
        }
        List<MethodSignature> methodSignatures;
        try {
            methodSignatures = liveWeavingService.getMethodSignatures(agentId,
                    config.getClassName(), config.getMethodName());
        } catch (AgentNotConnectedException e) {
            logger.debug(e.getMessage(), e);
            methodSignatures = null;
        }

        ImmutableInstrumentationConfigResponse.Builder builder =
                ImmutableInstrumentationConfigResponse.builder()
                        .agentNotConnected(methodSignatures == null)
                        .config(InstrumentationConfigDto.create(config));
        if (methodSignatures == null) {
            // agent not connected
            List<String> modifiers = Lists.newArrayList();
            if (!isSignatureAll(config)) {
                for (MethodModifier modifier : config.getMethodModifierList()) {
                    modifiers.add(modifier.name());
                }
                builder.addMethodSignatures(ImmutableMethodSignatureDto.builder()
                        .name(config.getMethodName())
                        .parameterTypes(config.getMethodParameterTypeList())
                        .returnType(config.getMethodReturnType())
                        .modifiers(modifiers)
                        .build());
            }
        } else {
            for (MethodSignature methodSignature : methodSignatures) {
                builder.addMethodSignatures(MethodSignatureDto.create(methodSignature));
            }
        }
        return mapper.writeValueAsString(builder.build());
    }

    private static boolean isSignatureAll(InstrumentationConfig config) {
        return config.getMethodModifierCount() == 0 && config.getMethodReturnType().isEmpty()
                && config.getMethodParameterTypeCount() == 1
                && config.getMethodParameterType(0).equals("..");
    }

    private static String getAgentId(String queryString) {
        return QueryStringDecoder.decodeComponent(queryString.substring("agent-id".length() + 1));
    }

    @Value.Immutable
    interface InstrumentationConfigRequest {
        String agentId();
        Optional<String> version();
    }

    @Value.Immutable
    interface ClassNamesRequest {
        String agentId();
        String partialClassName();
        int limit();
    }

    @Value.Immutable
    interface MethodNamesRequest {
        String agentId();
        String className();
        String partialMethodName();
        int limit();
    }

    @Value.Immutable
    interface MethodSignaturesRequest {
        String agentId();
        String className();
        String methodName();
    }

    @Value.Immutable
    interface InstrumentationListResponse {
        ImmutableList<InstrumentationConfigDto> configs();
        boolean jvmOutOfSync();
        boolean jvmRetransformClassesSupported();
    }

    @Value.Immutable
    interface InstrumentationConfigResponse {
        boolean agentNotConnected();
        InstrumentationConfigDto config();
        ImmutableList<MethodSignatureDto> methodSignatures();
    }

    @Value.Immutable
    interface InstrumentationErrorResponse {
        ImmutableList<String> errors();
    }

    @Value.Immutable
    interface InstrumentationImportRequest {
        String agentId();
        ImmutableList<ImmutableInstrumentationConfigDto> configs();
    }

    @Value.Immutable
    interface InstrumentationDeleteRequest {
        String agentId();
        List<String> versions();
    }

    @Value.Immutable
    @JsonInclude(value = Include.ALWAYS)
    abstract static class InstrumentationConfigDto {

        @JsonInclude(value = Include.NON_EMPTY)
        abstract Optional<String> agentId(); // only used in request
        abstract String className();
        abstract String classAnnotation();
        abstract String methodDeclaringClassName();
        abstract String methodName();
        abstract String methodAnnotation();
        abstract ImmutableList<String> methodParameterTypes();
        abstract String methodReturnType();
        abstract ImmutableList<MethodModifier> methodModifiers();
        abstract String nestingGroup();
        abstract int priority();
        abstract CaptureKind captureKind();
        abstract String timerName();
        abstract String traceEntryMessageTemplate();
        abstract @Nullable Integer traceEntryStackThresholdMillis();
        abstract boolean traceEntryCaptureSelfNested();
        abstract String transactionType();
        abstract String transactionNameTemplate();
        abstract String transactionUserTemplate();
        abstract Map<String, String> transactionAttributeTemplates();
        abstract @Nullable Integer transactionSlowThresholdMillis();
        abstract String enabledProperty();
        abstract String traceEntryEnabledProperty();
        abstract Optional<String> version(); // absent for insert operations

        private InstrumentationConfig convert() {
            InstrumentationConfig.Builder builder = InstrumentationConfig.newBuilder()
                    .setClassName(className())
                    .setMethodDeclaringClassName(methodDeclaringClassName())
                    .setMethodName(methodName())
                    .addAllMethodParameterType(methodParameterTypes())
                    .setMethodReturnType(methodReturnType())
                    .addAllMethodModifier(methodModifiers())
                    .setNestingGroup(nestingGroup())
                    .setPriority(priority())
                    .setCaptureKind(captureKind())
                    .setTimerName(timerName())
                    .setTraceEntryMessageTemplate(traceEntryMessageTemplate());
            Integer traceEntryStackThresholdMillis = traceEntryStackThresholdMillis();
            if (traceEntryStackThresholdMillis != null) {
                builder.setTraceEntryStackThresholdMillis(
                        OptionalInt32.newBuilder().setValue(traceEntryStackThresholdMillis));
            }
            builder.setTraceEntryCaptureSelfNested(traceEntryCaptureSelfNested())
                    .setTransactionType(transactionType())
                    .setTransactionNameTemplate(transactionNameTemplate())
                    .setTransactionUserTemplate(transactionUserTemplate())
                    .putAllTransactionAttributeTemplates(transactionAttributeTemplates());
            Integer transactionSlowThresholdMillis = transactionSlowThresholdMillis();
            if (transactionSlowThresholdMillis != null) {
                builder.setTransactionSlowThresholdMillis(
                        OptionalInt32.newBuilder().setValue(transactionSlowThresholdMillis));
            }
            return builder.setEnabledProperty(enabledProperty())
                    .setTraceEntryEnabledProperty(traceEntryEnabledProperty())
                    .build();
        }

        private static InstrumentationConfigDto create(InstrumentationConfig config) {
            ImmutableInstrumentationConfigDto.Builder builder =
                    ImmutableInstrumentationConfigDto.builder()
                            .className(config.getClassName())
                            .classAnnotation(config.getClassAnnotation())
                            .methodDeclaringClassName(config.getMethodDeclaringClassName())
                            .methodName(config.getMethodName())
                            .methodAnnotation(config.getMethodAnnotation())
                            .addAllMethodParameterTypes(config.getMethodParameterTypeList())
                            .methodReturnType(config.getMethodReturnType())
                            .addAllMethodModifiers(config.getMethodModifierList())
                            .nestingGroup(config.getNestingGroup())
                            .priority(config.getPriority())
                            .captureKind(config.getCaptureKind())
                            .timerName(config.getTimerName())
                            .traceEntryMessageTemplate(config.getTraceEntryMessageTemplate());
            if (config.hasTraceEntryStackThresholdMillis()) {
                builder.traceEntryStackThresholdMillis(
                        config.getTraceEntryStackThresholdMillis().getValue());
            }
            builder.traceEntryCaptureSelfNested(config.getTraceEntryCaptureSelfNested())
                    .transactionType(config.getTransactionType())
                    .transactionNameTemplate(config.getTransactionNameTemplate())
                    .transactionUserTemplate(config.getTransactionUserTemplate())
                    .putAllTransactionAttributeTemplates(config.getTransactionAttributeTemplates());
            if (config.hasTransactionSlowThresholdMillis()) {
                builder.transactionSlowThresholdMillis(
                        config.getTransactionSlowThresholdMillis().getValue());
            }
            return builder.enabledProperty(config.getEnabledProperty())
                    .traceEntryEnabledProperty(config.getTraceEntryEnabledProperty())
                    .version(Versions.getVersion(config))
                    .build();
        }
    }

    @Value.Immutable
    @JsonInclude(value = Include.ALWAYS)
    abstract static class MethodSignatureDto {

        abstract String name();
        abstract ImmutableList<String> parameterTypes();
        abstract String returnType();
        abstract ImmutableList<String> modifiers();

        private static MethodSignatureDto create(MethodSignature methodSignature) {
            return ImmutableMethodSignatureDto.builder()
                    .name(methodSignature.getName())
                    .addAllParameterTypes(methodSignature.getParameterTypeList())
                    .returnType(methodSignature.getReturnType())
                    .modifiers(methodSignature.getModifierList())
                    .build();
        }
    }

    @VisibleForTesting
    static class InstrumentationConfigOrdering extends Ordering<InstrumentationConfig> {
        @Override
        public int compare(InstrumentationConfig left, InstrumentationConfig right) {
            int compare = left.getClassName().compareToIgnoreCase(right.getClassName());
            if (compare != 0) {
                return compare;
            }
            compare = left.getMethodName().compareToIgnoreCase(right.getMethodName());
            if (compare != 0) {
                return compare;
            }
            List<String> leftParameterTypes = left.getMethodParameterTypeList();
            List<String> rightParameterTypes = right.getMethodParameterTypeList();
            compare = Ints.compare(leftParameterTypes.size(), rightParameterTypes.size());
            if (compare != 0) {
                return compare;
            }
            for (int i = 0; i < leftParameterTypes.size(); i++) {
                compare = leftParameterTypes.get(i).compareToIgnoreCase(rightParameterTypes.get(i));
                if (compare != 0) {
                    return compare;
                }
            }
            return 0;
        }
    }
}
