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
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import org.immutables.common.marshal.Marshaling;
import org.immutables.value.Json;
import org.immutables.value.Value;

import org.glowroot.api.weaving.MethodModifier;
import org.glowroot.common.Marshaling2;
import org.glowroot.config.CapturePoint;
import org.glowroot.config.CapturePoint.CaptureKind;
import org.glowroot.config.ConfigService;
import org.glowroot.config.ImmutableCapturePoint;
import org.glowroot.config.MarshalingRoutines;
import org.glowroot.local.ui.UiAnalyzedMethod.UiAnalyzedMethodOrdering;
import org.glowroot.transaction.AdviceCache;
import org.glowroot.transaction.TransactionModule;

import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_SYNCHRONIZED;

@JsonService
class CapturePointJsonService {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Splitter splitter = Splitter.on(' ').omitEmptyStrings();

    private final ConfigService configService;
    private final AdviceCache adviceCache;
    private final TransactionModule transactionModule;
    private final ClasspathCache classpathCache;

    CapturePointJsonService(ConfigService configService, AdviceCache adviceCache,
            ClasspathCache classpathCache, TransactionModule transactionModule) {
        this.configService = configService;
        this.adviceCache = adviceCache;
        this.classpathCache = classpathCache;
        this.transactionModule = transactionModule;
    }

    @GET("/backend/config/capture-points")
    String getCapturePoint() throws Exception {
        List<CapturePoint> configs = configService.getCapturePoints();
        List<CapturePointDto> configDtos = Lists.newArrayList();
        for (CapturePoint config : configs) {
            configDtos.add(CapturePointDto.fromConfig(config));
        }
        return Marshaling2.toJson(ImmutableCapturePointResponse.builder()
                .addAllConfigs(configDtos)
                .jvmOutOfSync(adviceCache.isOutOfSync(configService.getCapturePoints()))
                .jvmRetransformClassesSupported(
                        transactionModule.isJvmRetransformClassesSupported())
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
                classpathCache.updateCache();
            }
        });
        thread.setDaemon(true);
        thread.setName("Glowroot-Temporary-Thread");
        thread.start();
    }

    @GET("/backend/config/matching-class-names")
    String getMatchingClassNames(String queryString) throws Exception {
        ClassNamesRequest request = QueryStrings.decode(queryString, ClassNamesRequest.class);
        List<String> matchingClassNames = classpathCache.getMatchingClassNames(
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
        List<UiAnalyzedMethod> analyzedMethods =
                getAnalyzedMethods(request.className(), request.methodName());
        ArrayNode matchingMethods = mapper.createArrayNode();
        for (UiAnalyzedMethod analyzedMethod : analyzedMethods) {
            ObjectNode matchingMethod = mapper.createObjectNode();
            matchingMethod.put("name", analyzedMethod.name());
            ArrayNode parameterTypes = mapper.createArrayNode();
            for (String parameterType : analyzedMethod.parameterTypes()) {
                parameterTypes.add(parameterType);
            }
            matchingMethod.set("parameterTypes", parameterTypes);
            matchingMethod.put("returnType", analyzedMethod.returnType());
            ArrayNode modifiers = mapper.createArrayNode();
            // strip final and synchronized from displayed modifiers since they have no impact on
            // the weaver's method matching
            int reducedModifiers = analyzedMethod.modifiers() & ~ACC_FINAL & ~ACC_SYNCHRONIZED;
            String modifierNames = Modifier.toString(reducedModifiers);
            for (String modifier : splitter.split(modifierNames)) {
                modifiers.add(modifier.toLowerCase(Locale.ENGLISH));
            }
            matchingMethod.set("modifiers", modifiers);
            matchingMethods.add(matchingMethod);
        }
        return mapper.writeValueAsString(matchingMethods);
    }

    @POST("/backend/config/capture-points/add")
    String addCapturePoint(String content) throws Exception {
        CapturePointDto capturePointDto = Marshaling.fromJson(content, CapturePointDto.class);
        CapturePoint capturePoint = capturePointDto.toConfig();
        configService.insertCapturePoint(capturePoint);
        return Marshaling2.toJson(CapturePointDto.fromConfig(capturePoint));
    }

    @POST("/backend/config/capture-points/update")
    String updateCapturePoint(String content) throws IOException {
        CapturePointDto capturePointDto = Marshaling.fromJson(content, CapturePointDto.class);
        CapturePoint capturePoint = capturePointDto.toConfig();
        configService.updateCapturePoint(capturePoint, capturePointDto.version().get());
        return Marshaling2.toJson(CapturePointDto.fromConfig(capturePoint));
    }

    @POST("/backend/config/capture-points/remove")
    void removeCapturePoint(String content) throws IOException {
        String version = ObjectMappers.readRequiredValue(mapper, content, String.class);
        configService.deleteCapturePoint(version);
    }

    // returns the first <limit> matching method names, ordered alphabetically (case-insensitive)
    private ImmutableList<String> getMatchingMethodNames(String className,
            String partialMethodName, int limit) {
        String partialMethodNameUpper = partialMethodName.toUpperCase(Locale.ENGLISH);
        Set<String> methodNames = Sets.newHashSet();
        for (UiAnalyzedMethod analyzedMethod : classpathCache.getAnalyzedMethods(className)) {
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

    private List<UiAnalyzedMethod> getAnalyzedMethods(String className, String methodName) {
        // use set to remove duplicate methods (e.g. same class loaded by multiple class loaders)
        Set<UiAnalyzedMethod> analyzedMethods = Sets.newHashSet();
        for (UiAnalyzedMethod analyzedMethod : classpathCache.getAnalyzedMethods(className)) {
            if (analyzedMethod.name().equals(methodName)) {
                analyzedMethods.add(analyzedMethod);
            }
        }
        // order methods by accessibility, then by name, then by number of args
        return UiAnalyzedMethodOrdering.INSTANCE.sortedCopy(analyzedMethods);
    }

    @Value.Immutable
    abstract static class ClassNamesRequest {
        abstract String partialClassName();
        abstract int limit();
    }

    @Value.Immutable
    abstract static class MethodNamesRequest {
        abstract String className();
        abstract String partialMethodName();
        abstract int limit();
    }

    @Value.Immutable
    abstract static class MethodSignaturesRequest {
        abstract String className();
        abstract String methodName();
    }

    @Value.Immutable
    @Json.Marshaled
    abstract static class CapturePointResponse {
        @Json.ForceEmpty
        abstract List<CapturePointDto> configs();
        abstract boolean jvmOutOfSync();
        abstract boolean jvmRetransformClassesSupported();
    }

    @Value.Immutable
    @Json.Marshaled
    @Json.Import(MarshalingRoutines.class)
    abstract static class CapturePointDto {

        abstract String className();
        abstract String methodName();
        @Json.ForceEmpty
        abstract List<String> methodParameterTypes();
        abstract Optional<String> methodReturnType();
        @Json.ForceEmpty
        abstract List<MethodModifier> methodModifiers();
        abstract CaptureKind captureKind();
        abstract Optional<String> metricName();
        abstract Optional<String> traceEntryTemplate();
        @Json.ForceEmpty
        abstract @Nullable Long traceEntryStackThresholdMillis();
        abstract Optional<Boolean> traceEntryCaptureSelfNested();
        abstract Optional<String> transactionType();
        abstract Optional<String> transactionNameTemplate();
        abstract Optional<String> transactionUserTemplate();
        @Json.ForceEmpty
        abstract Map<String, String> transactionCustomAttributeTemplates();
        @Json.ForceEmpty
        abstract @Nullable Long traceStoreThresholdMillis();
        abstract Optional<String> enabledProperty();
        abstract Optional<String> traceEntryEnabledProperty();
        abstract Optional<String> version(); // null for insert operations

        private static CapturePointDto fromConfig(CapturePoint config) {
            return ImmutableCapturePointDto.builder()
                    .className(config.className())
                    .methodName(config.methodName())
                    .addAllMethodParameterTypes(config.methodParameterTypes())
                    .methodReturnType(config.methodReturnType())
                    .addAllMethodModifiers(config.methodModifiers())
                    .captureKind(config.captureKind())
                    .metricName(config.metricName())
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

        private CapturePoint toConfig() {
            return ImmutableCapturePoint.builder()
                    .className(className())
                    .methodName(methodName())
                    .addAllMethodParameterTypes(methodParameterTypes())
                    .methodReturnType(methodReturnType().or(""))
                    .addAllMethodModifiers(methodModifiers())
                    .captureKind(captureKind())
                    .metricName(metricName().or(""))
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
