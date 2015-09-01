/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.server.ui;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.primitives.Ints;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.immutables.value.Value;

import org.glowroot.common.config.ImmutableInstrumentationConfig;
import org.glowroot.common.config.InstrumentationConfig;
import org.glowroot.common.config.InstrumentationConfig.CaptureKind;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.live.LiveWeavingService;
import org.glowroot.live.LiveWeavingService.GlobalMeta;
import org.glowroot.live.LiveWeavingService.MethodSignature;
import org.glowroot.plugin.api.weaving.MethodModifier;
import org.glowroot.server.repo.ConfigRepository;

import static com.google.common.base.Preconditions.checkNotNull;

@JsonService
class InstrumentationJsonService {

    private static final ObjectMapper mapper = ObjectMappers.create();

    private static final Ordering<InstrumentationConfig> ordering =
            new InstrumentationConfigOrdering();

    private final ConfigRepository configRepository;
    private final LiveWeavingService liveWeavingService;

    InstrumentationJsonService(ConfigRepository configRepository,
            LiveWeavingService liveWeavingService) {
        this.configRepository = configRepository;
        this.liveWeavingService = liveWeavingService;
    }

    @GET("/backend/config/instrumentation")
    String getInstrumentationConfigs() throws Exception {
        List<InstrumentationConfig> configs = configRepository.getInstrumentationConfigs();
        configs = ordering.immutableSortedCopy(configs);
        List<InstrumentationConfigDto> dtos = Lists.newArrayList();
        for (InstrumentationConfig config : configs) {
            dtos.add(InstrumentationConfigDto.fromConfig(config));
        }
        GlobalMeta globalMeta = liveWeavingService.getGlobalMeta();
        return mapper.writeValueAsString(ImmutableInstrumentationListResponse.builder()
                .addAllConfigs(dtos)
                .jvmOutOfSync(globalMeta.jvmOutOfSync())
                .jvmRetransformClassesSupported(globalMeta.jvmRetransformClassesSupported())
                .build());
    }

    @GET("/backend/config/instrumentation/([0-9a-f]{40})")
    String getInstrumentationConfig(String version) throws JsonProcessingException {
        InstrumentationConfig config = configRepository.getInstrumentationConfig(version);
        if (config == null) {
            throw new JsonServiceException(HttpResponseStatus.NOT_FOUND);
        }
        List<MethodSignature> methodSignatures =
                liveWeavingService.getMethodSignatures(config.className(), config.methodName());
        return mapper.writeValueAsString(ImmutableInstrumentationConfigResponse.builder()
                .config(InstrumentationConfigDto.fromConfig(config))
                .addAllMethodSignatures(methodSignatures)
                .build());
    }

    // this is marked as @GET so it can be used without update rights (e.g. demo instance)
    @GET("/backend/config/preload-classpath-cache")
    void preloadClasspathCache() throws IOException {
        liveWeavingService.preloadClasspathCache();
        // HttpServer is configured with a very small thread pool to keep number of threads down
        // (currently only a single thread), so spawn a background thread to perform the preloading
        // so it doesn't block other http requests
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                liveWeavingService.preloadClasspathCache();
            }
        });
        thread.setDaemon(true);
        thread.setName("Glowroot-Temporary-Thread");
        thread.start();
    }

    @GET("/backend/config/matching-class-names")
    String getMatchingClassNames(String queryString) throws Exception {
        ClassNamesRequest request = QueryStrings.decode(queryString, ClassNamesRequest.class);
        return mapper.writeValueAsString(liveWeavingService
                .getMatchingClassNames(request.partialClassName(), request.limit()));
    }

    @GET("/backend/config/matching-method-names")
    String getMatchingMethodNames(String queryString) throws Exception {
        MethodNamesRequest request = QueryStrings.decode(queryString, MethodNamesRequest.class);
        List<String> matchingMethodNames = liveWeavingService.getMatchingMethodNames(
                request.className(), request.partialMethodName(), request.limit());
        return mapper.writeValueAsString(matchingMethodNames);
    }

    @GET("/backend/config/method-signatures")
    String getMethodSignatures(String queryString) throws Exception {
        MethodSignaturesRequest request =
                QueryStrings.decode(queryString, MethodSignaturesRequest.class);
        List<MethodSignature> methodSignatures =
                liveWeavingService.getMethodSignatures(request.className(), request.methodName());
        return mapper.writeValueAsString(methodSignatures);
    }

    @POST("/backend/config/instrumentation/add")
    String addInstrumentationConfig(String content) throws Exception {
        InstrumentationConfigDto configDto =
                mapper.readValue(content, ImmutableInstrumentationConfigDto.class);
        InstrumentationConfig config = configDto.toConfig();
        ImmutableList<String> errors = config.validationErrors();
        if (!errors.isEmpty()) {
            return mapper.writeValueAsString(ImmutableInstrumentationErrorResponse.builder()
                    .addAllErrors(errors)
                    .build());
        }
        String version = configRepository.insertInstrumentationConfig(config);
        return getInstrumentationConfig(version);
    }

    @POST("/backend/config/instrumentation/update")
    String updateInstrumentationConfig(String content) throws IOException {
        InstrumentationConfigDto configDto =
                mapper.readValue(content, ImmutableInstrumentationConfigDto.class);
        InstrumentationConfig config = configDto.toConfig();
        String version = configDto.version();
        checkNotNull(version, "Missing required request property: version");
        version = configRepository.updateInstrumentationConfig(config, version);
        return getInstrumentationConfig(version);
    }

    @POST("/backend/config/instrumentation/remove")
    void removeInstrumentationConfig(String content) throws IOException {
        String version = mapper.readValue(content, String.class);
        checkNotNull(version);
        configRepository.deleteInstrumentationConfig(version);
    }

    @Value.Immutable
    interface ClassNamesRequest {
        String partialClassName();
        int limit();
    }

    @Value.Immutable
    interface MethodNamesRequest {
        String className();
        String partialMethodName();
        int limit();
    }

    @Value.Immutable
    interface MethodSignaturesRequest {
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
        InstrumentationConfigDto config();
        ImmutableList<MethodSignature> methodSignatures();
    }

    @Value.Immutable
    interface InstrumentationErrorResponse {
        abstract ImmutableList<String> errors();
    }

    @Value.Immutable
    abstract static class InstrumentationConfigDto {

        abstract String className();
        abstract Optional<String> declaringClassName();
        abstract String methodName();
        abstract ImmutableList<String> methodParameterTypes();
        abstract Optional<String> methodReturnType();
        abstract ImmutableList<MethodModifier> methodModifiers();
        abstract CaptureKind captureKind();
        abstract Optional<String> timerName();
        abstract Optional<String> traceEntryTemplate();
        abstract @Nullable Long traceEntryStackThresholdMillis();
        abstract Optional<Boolean> traceEntryCaptureSelfNested();
        abstract Optional<String> transactionType();
        abstract Optional<String> transactionNameTemplate();
        abstract Optional<String> transactionUserTemplate();
        abstract Map<String, String> transactionCustomAttributeTemplates();
        abstract @Nullable Long transactionSlowThresholdMillis();
        abstract Optional<String> enabledProperty();
        abstract Optional<String> traceEntryEnabledProperty();
        abstract @Nullable String version(); // null for insert operations

        private static InstrumentationConfigDto fromConfig(InstrumentationConfig config) {
            return ImmutableInstrumentationConfigDto.builder()
                    .className(config.className())
                    .declaringClassName(config.declaringClassName())
                    .methodName(config.methodName())
                    .addAllMethodParameterTypes(config.methodParameterTypes())
                    .methodReturnType(config.methodReturnType())
                    .addAllMethodModifiers(config.methodModifiers())
                    .captureKind(config.captureKind())
                    .timerName(config.timerName())
                    .traceEntryTemplate(config.traceEntryTemplate())
                    .traceEntryStackThresholdMillis(config.traceEntryStackThresholdMillis())
                    .traceEntryCaptureSelfNested(config.traceEntryCaptureSelfNested())
                    .transactionType(config.transactionType())
                    .transactionNameTemplate(config.transactionNameTemplate())
                    .transactionUserTemplate(config.transactionUserTemplate())
                    .putAllTransactionCustomAttributeTemplates(
                            config.transactionCustomAttributeTemplates())
                    .transactionSlowThresholdMillis(config.transactionSlowThresholdMillis())
                    .enabledProperty(config.enabledProperty())
                    .traceEntryEnabledProperty(config.traceEntryEnabledProperty())
                    .version(config.version())
                    .build();
        }

        private InstrumentationConfig toConfig() {
            return ImmutableInstrumentationConfig.builder()
                    .className(className())
                    .declaringClassName(declaringClassName().or(""))
                    .methodName(methodName())
                    .addAllMethodParameterTypes(methodParameterTypes())
                    .methodReturnType(methodReturnType().or(""))
                    .addAllMethodModifiers(methodModifiers())
                    .captureKind(captureKind())
                    .timerName(timerName().or(""))
                    .traceEntryTemplate(traceEntryTemplate().or(""))
                    .traceEntryStackThresholdMillis(traceEntryStackThresholdMillis())
                    .traceEntryCaptureSelfNested(traceEntryCaptureSelfNested().or(false))
                    .transactionType(transactionType().or(""))
                    .transactionNameTemplate(transactionNameTemplate().or(""))
                    .transactionUserTemplate(transactionUserTemplate().or(""))
                    .putAllTransactionCustomAttributeTemplates(
                            transactionCustomAttributeTemplates())
                    .transactionSlowThresholdMillis(transactionSlowThresholdMillis())
                    .enabledProperty(enabledProperty().or(""))
                    .traceEntryEnabledProperty(traceEntryEnabledProperty().or(""))
                    .build();
        }
    }

    @VisibleForTesting
    static class InstrumentationConfigOrdering extends Ordering<InstrumentationConfig> {
        @Override
        public int compare(@Nullable InstrumentationConfig left,
                @Nullable InstrumentationConfig right) {
            checkNotNull(left);
            checkNotNull(right);
            int compare = left.className().compareToIgnoreCase(right.className());
            if (compare != 0) {
                return compare;
            }
            compare = left.methodName().compareToIgnoreCase(right.methodName());
            if (compare != 0) {
                return compare;
            }
            compare = Ints.compare(left.methodParameterTypes().size(),
                    right.methodParameterTypes().size());
            if (compare != 0) {
                return compare;
            }
            List<String> leftParameterTypes = left.methodParameterTypes();
            List<String> rightParameterTypes = right.methodParameterTypes();
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
