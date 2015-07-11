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
package org.glowroot.local.ui;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.immutables.value.Value;

import org.glowroot.api.weaving.MethodModifier;
import org.glowroot.common.ObjectMappers;
import org.glowroot.config.CaptureKind;
import org.glowroot.config.ConfigService;
import org.glowroot.config.InstrumentationConfig;
import org.glowroot.transaction.AdviceCache;
import org.glowroot.transaction.TransactionModule;
import org.glowroot.weaving.AnalyzedWorld;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_SYNCHRONIZED;

@JsonService
class InstrumentationJsonService {

    private static final String DUMMY_KEY = "KEY";

    private static final ObjectMapper mapper = ObjectMappers.create();
    private static final Splitter splitter = Splitter.on(' ').omitEmptyStrings();

    private final ConfigService configService;
    private final AdviceCache adviceCache;
    private final TransactionModule transactionModule;
    private final AnalyzedWorld analyzedWorld;
    private final @Nullable Instrumentation instrumentation;

    // hopefully can simplify someday https://github.com/google/guava/issues/872
    private final LoadingCache<String, ClasspathCache> classpathCache = CacheBuilder.newBuilder()
            .softValues()
            .maximumSize(1)
            .build(new CacheLoader<String, ClasspathCache>() {
                @Override
                public ClasspathCache load(String key) throws Exception {
                    return new ClasspathCache(analyzedWorld, instrumentation);
                }
            });

    InstrumentationJsonService(ConfigService configService, AdviceCache adviceCache,
            TransactionModule transactionModule, AnalyzedWorld analyzedWorld,
            @Nullable Instrumentation instrumentation) {
        this.configService = configService;
        this.adviceCache = adviceCache;
        this.transactionModule = transactionModule;
        this.analyzedWorld = analyzedWorld;
        this.instrumentation = instrumentation;
    }

    @GET("/backend/config/instrumentation")
    String getInstrumentationConfigs() throws Exception {
        List<InstrumentationConfig> configs = configService.getInstrumentationConfigs();
        configs = InstrumentationConfig.ordering.immutableSortedCopy(configs);
        List<InstrumentationConfigDto> dtos = Lists.newArrayList();
        for (InstrumentationConfig config : configs) {
            dtos.add(InstrumentationConfigDtoBase.fromConfig(config));
        }
        return mapper.writeValueAsString(InstrumentationListResponse.builder()
                .addAllConfigs(dtos)
                .jvmOutOfSync(adviceCache.isOutOfSync(configService.getInstrumentationConfigs()))
                .jvmRetransformClassesSupported(
                        transactionModule.isJvmRetransformClassesSupported())
                .build());
    }

    @GET("/backend/config/instrumentation/([0-9a-f]{40})")
    String getInstrumentationConfig(String version) throws JsonProcessingException {
        InstrumentationConfig config = configService.getInstrumentationConfig(version);
        if (config == null) {
            throw new JsonServiceException(HttpResponseStatus.NOT_FOUND);
        }
        List<MethodSignature> methodSignatures =
                getMethodSignatures(config.className(), config.methodName());
        return mapper.writeValueAsString(InstrumentationConfigResponse.builder()
                .config(InstrumentationConfigDtoBase.fromConfig(config))
                .addAllMethodSignatures(methodSignatures)
                .build());
    }

    // this is marked as @GET so it can be used without update rights (e.g. demo instance)
    @GET("/backend/config/preload-classpath-cache")
    void preloadClasspathCache() throws IOException {
        // HttpServer is configured with a very small thread pool to keep number of threads down
        // (currently only a single thread), so spawn a background thread to perform the preloading
        // so it doesn't block other http requests
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                getClasspathCache().updateCache();
            }
        });
        thread.setDaemon(true);
        thread.setName("Glowroot-Temporary-Thread");
        thread.start();
    }

    @GET("/backend/config/matching-class-names")
    String getMatchingClassNames(String queryString) throws Exception {
        ClassNamesRequest request = QueryStrings.decode(queryString, ClassNamesRequest.class);
        List<String> matchingClassNames = getClasspathCache().getMatchingClassNames(
                request.partialClassName(), request.limit());
        return mapper.writeValueAsString(matchingClassNames);
    }

    @GET("/backend/config/matching-method-names")
    String getMatchingMethodNames(String queryString) throws Exception {
        MethodNamesRequest request = QueryStrings.decode(queryString, MethodNamesRequest.class);
        List<String> matchingMethodNames = getMatchingMethodNames(request.className(),
                request.partialMethodName(), request.limit());
        return mapper.writeValueAsString(matchingMethodNames);
    }

    @GET("/backend/config/method-signatures")
    String getMethodSignatures(String queryString) throws Exception {
        MethodSignaturesRequest request =
                QueryStrings.decode(queryString, MethodSignaturesRequest.class);
        List<MethodSignature> methodSignatures =
                getMethodSignatures(request.className(), request.methodName());
        return mapper.writeValueAsString(methodSignatures);
    }

    @POST("/backend/config/instrumentation/add")
    String addInstrumentationConfig(String content) throws Exception {
        InstrumentationConfigDto configDto =
                mapper.readValue(content, InstrumentationConfigDto.class);
        InstrumentationConfig config = configDto.toConfig();
        ImmutableList<String> errors = config.validationErrors();
        if (!errors.isEmpty()) {
            return mapper.writeValueAsString(InstrumentationErrorResponse.builder()
                    .addAllErrors(errors)
                    .build());
        }
        String version = configService.insertInstrumentationConfig(config);
        return getInstrumentationConfig(version);
    }

    @POST("/backend/config/instrumentation/update")
    String updateInstrumentationConfig(String content) throws IOException {
        InstrumentationConfigDto configDto =
                mapper.readValue(content, InstrumentationConfigDto.class);
        InstrumentationConfig config = configDto.toConfig();
        String version = configDto.version();
        checkNotNull(version, "Missing required request property: version");
        version = configService.updateInstrumentationConfig(config, version);
        return getInstrumentationConfig(version);
    }

    @POST("/backend/config/instrumentation/remove")
    void removeInstrumentationConfig(String content) throws IOException {
        String version = mapper.readValue(content, String.class);
        checkNotNull(version);
        configService.deleteInstrumentationConfig(version);
    }

    private ClasspathCache getClasspathCache() {
        return classpathCache.getUnchecked(DUMMY_KEY);
    }

    // returns the first <limit> matching method names, ordered alphabetically (case-insensitive)
    private ImmutableList<String> getMatchingMethodNames(String className,
            String partialMethodName, int limit) {
        String partialMethodNameUpper = partialMethodName.toUpperCase(Locale.ENGLISH);
        Set<String> methodNames = Sets.newHashSet();
        for (UiAnalyzedMethod analyzedMethod : getClasspathCache().getAnalyzedMethods(className)) {
            String methodName = analyzedMethod.name();
            if (methodName.equals("<init>") || methodName.equals("<clinit>")) {
                // static initializers are not supported by weaver
                // (see AdviceMatcher.isMethodNameMatch())
                // and constructors do not support @OnBefore advice at this time
                continue;
            }
            if (methodName.toUpperCase(Locale.ENGLISH).contains(partialMethodNameUpper)) {
                methodNames.add(methodName);
            }
        }
        ImmutableList<String> sortedMethodNames =
                Ordering.from(String.CASE_INSENSITIVE_ORDER).immutableSortedCopy(methodNames);
        if (methodNames.size() > limit) {
            return sortedMethodNames.subList(0, limit);
        } else {
            return sortedMethodNames;
        }
    }

    private List<MethodSignature> getMethodSignatures(String className, String methodName) {
        if (methodName.contains("*") || methodName.contains("|")) {
            return ImmutableList.of();
        }
        List<UiAnalyzedMethod> analyzedMethods = getAnalyzedMethods(className, methodName);
        List<MethodSignature> methodSignatures = Lists.newArrayList();
        for (UiAnalyzedMethod analyzedMethod : analyzedMethods) {
            MethodSignature.Builder builder = MethodSignature.builder();
            builder.name(analyzedMethod.name());
            builder.addAllParameterTypes(analyzedMethod.parameterTypes());
            builder.returnType(analyzedMethod.returnType());
            // strip final and synchronized from displayed modifiers since they have no impact on
            // the weaver's method matching
            int reducedModifiers = analyzedMethod.modifiers() & ~ACC_FINAL & ~ACC_SYNCHRONIZED;
            String modifierNames = Modifier.toString(reducedModifiers);
            for (String modifier : splitter.split(modifierNames)) {
                builder.addModifiers(modifier.toLowerCase(Locale.ENGLISH));
            }
            methodSignatures.add(builder.build());
        }
        return methodSignatures;
    }

    private List<UiAnalyzedMethod> getAnalyzedMethods(String className, String methodName) {
        // use set to remove duplicate methods (e.g. same class loaded by multiple class loaders)
        Set<UiAnalyzedMethod> analyzedMethods = Sets.newHashSet();
        for (UiAnalyzedMethod analyzedMethod : getClasspathCache().getAnalyzedMethods(className)) {
            if (analyzedMethod.name().equals(methodName)) {
                analyzedMethods.add(analyzedMethod);
            }
        }
        // order methods by accessibility, then by name, then by number of args
        return UiAnalyzedMethodBase.ordering.sortedCopy(analyzedMethods);
    }

    @Value.Immutable
    abstract static class ClassNamesRequestBase {
        abstract String partialClassName();
        abstract int limit();
    }

    @Value.Immutable
    abstract static class MethodNamesRequestBase {
        abstract String className();
        abstract String partialMethodName();
        abstract int limit();
    }

    @Value.Immutable
    abstract static class MethodSignaturesRequestBase {
        abstract String className();
        abstract String methodName();
    }

    @Value.Immutable
    abstract static class MethodSignatureBase {
        abstract String name();
        abstract ImmutableList<String> parameterTypes();
        abstract String returnType();
        abstract ImmutableList<String> modifiers();
    }

    @Value.Immutable
    abstract static class InstrumentationListResponseBase {
        abstract ImmutableList<InstrumentationConfigDto> configs();
        abstract boolean jvmOutOfSync();
        abstract boolean jvmRetransformClassesSupported();
    }

    @Value.Immutable
    abstract static class InstrumentationConfigResponseBase {
        abstract InstrumentationConfigDto config();
        abstract ImmutableList<MethodSignature> methodSignatures();
    }

    @Value.Immutable
    abstract static class InstrumentationErrorResponseBase {
        abstract ImmutableList<String> errors();
    }

    @Value.Immutable
    abstract static class InstrumentationConfigDtoBase {

        abstract String className();
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
        abstract @Nullable Long traceStoreThresholdMillis();
        abstract Optional<String> enabledProperty();
        abstract Optional<String> traceEntryEnabledProperty();
        abstract @Nullable String version(); // null for insert operations

        private static InstrumentationConfigDto fromConfig(InstrumentationConfig config) {
            return InstrumentationConfigDto.builder()
                    .className(config.className())
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
                    .traceStoreThresholdMillis(config.traceStoreThresholdMillis())
                    .enabledProperty(config.enabledProperty())
                    .traceEntryEnabledProperty(config.traceEntryEnabledProperty())
                    .version(config.version())
                    .build();
        }

        InstrumentationConfig toConfig() {
            return InstrumentationConfig.builder()
                    .className(className())
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
                    .traceStoreThresholdMillis(traceStoreThresholdMillis())
                    .enabledProperty(enabledProperty().or(""))
                    .traceEntryEnabledProperty(traceEntryEnabledProperty().or(""))
                    .build();
        }
    }
}
