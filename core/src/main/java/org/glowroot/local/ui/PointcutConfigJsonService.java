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
package org.glowroot.local.ui;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.common.io.CharStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.ObjectMappers;
import org.glowroot.config.ConfigService;
import org.glowroot.config.JsonViews.UiView;
import org.glowroot.config.PointcutConfig;
import org.glowroot.markers.Singleton;
import org.glowroot.trace.ReweavableAdviceCache;
import org.glowroot.trace.TraceModule;
import org.glowroot.weaving.ParsedMethod;
import org.glowroot.weaving.ParsedType;
import org.glowroot.weaving.ParsedTypeCache;
import org.glowroot.weaving.ParsedTypeCache.ParsedMethodOrdering;

import static org.glowroot.common.ObjectMappers.checkRequiredProperty;

/**
 * Json service to support pointcut configurations.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
@JsonService
class PointcutConfigJsonService {

    private static final Logger logger = LoggerFactory.getLogger(PointcutConfigJsonService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();
    private static final Splitter splitter = Splitter.on(' ').omitEmptyStrings();

    private final ConfigService configService;
    private final ReweavableAdviceCache reweavableAdviceCache;
    private final TraceModule traceModule;
    private final ParsedTypeCache parsedTypeCache;
    private final ClasspathCache classpathCache;

    PointcutConfigJsonService(ConfigService configService,
            ReweavableAdviceCache reweavableAdviceCache, ParsedTypeCache parsedTypeCache,
            ClasspathCache classpathTypeCache, TraceModule traceModule) {
        this.configService = configService;
        this.reweavableAdviceCache = reweavableAdviceCache;
        this.parsedTypeCache = parsedTypeCache;
        this.classpathCache = classpathTypeCache;
        this.traceModule = traceModule;
    }

    @GET("/backend/config/pointcut")
    String getPointcutConfig() throws IOException, SQLException {
        logger.debug("getPointcutConfig()");
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        ObjectWriter writer = mapper.writerWithView(UiView.class);
        jg.writeStartObject();
        jg.writeFieldName("configs");
        writer.writeValue(jg, configService.getPointcutConfigs());
        jg.writeBooleanField("jvmOutOfSync",
                reweavableAdviceCache.isOutOfSync(configService.getPointcutConfigs()));
        jg.writeBooleanField("jvmRetransformClassesSupported",
                traceModule.isJvmRetransformClassesSupported());
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    // this is marked as @GET so it can be used without update rights (e.g. demo instance)
    @GET("/backend/config/preload-classpath-cache")
    void preloadClasspathCache() throws IOException {
        logger.debug("preloadClasspathCache()");
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

    @GET("/backend/config/matching-types")
    String getMatchingTypes(String content) throws IOException {
        logger.debug("getMatchingTypes(): content={}", content);
        TypesRequest request = ObjectMappers.readRequiredValue(mapper, content, TypesRequest.class);
        List<String> matchingTypeNames = getMatchingTypeNames(request.getPartialTypeName(),
                request.getLimit());

        return mapper.writeValueAsString(matchingTypeNames);
    }

    @GET("/backend/config/matching-method-names")
    String getMatchingMethodNames(String content) throws IOException {
        logger.debug("getMatchingMethodNames(): content={}", content);
        MethodNamesRequest request =
                ObjectMappers.readRequiredValue(mapper, content, MethodNamesRequest.class);
        List<String> matchingMethodNames = getMatchingMethodNames(request.getType(),
                request.getPartialMethodName(), request.getLimit());
        return mapper.writeValueAsString(matchingMethodNames);
    }

    @GET("/backend/config/method-signatures")
    String getMethodSignatures(String content) throws IOException {
        logger.debug("getMethodSignatures(): content={}", content);
        MethodSignaturesRequest request =
                ObjectMappers.readRequiredValue(mapper, content, MethodSignaturesRequest.class);
        List<ParsedMethod> parsedMethods = getParsedMethods(request.getType(),
                request.getMethodName());
        ArrayNode matchingMethods = mapper.createArrayNode();
        for (ParsedMethod parsedMethod : parsedMethods) {
            ObjectNode matchingMethod = mapper.createObjectNode();
            matchingMethod.put("name", parsedMethod.getName());
            ArrayNode argTypes = mapper.createArrayNode();
            for (String argTypeName : parsedMethod.getArgTypes()) {
                argTypes.add(argTypeName);
            }
            matchingMethod.set("argTypes", argTypes);
            matchingMethod.put("returnType", parsedMethod.getReturnType());
            ArrayNode modifiers = mapper.createArrayNode();
            String modifierNames = Modifier.toString(parsedMethod.getModifiers());
            for (String modifier : splitter.split(modifierNames)) {
                modifiers.add(modifier.toLowerCase(Locale.ENGLISH));
            }
            matchingMethod.set("modifiers", modifiers);
            matchingMethods.add(matchingMethod);
        }
        return mapper.writeValueAsString(matchingMethods);
    }

    @POST("/backend/config/pointcut/+")
    String addPointcutConfig(String content) throws IOException {
        logger.debug("addPointcutConfig(): content={}", content);
        PointcutConfig pointcutConfig =
                ObjectMappers.readRequiredValue(mapper, content, PointcutConfig.class);
        configService.insertPointcutConfig(pointcutConfig);
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        ObjectWriter writer = mapper.writerWithView(UiView.class);
        writer.writeValue(jg, pointcutConfig);
        jg.close();
        return sb.toString();
    }

    @POST("/backend/config/pointcut/([0-9a-f]+)")
    String updatePointcutConfig(String priorVersion, String content) throws IOException {
        logger.debug("updatePointcutConfig(): priorVersion={}, content={}", priorVersion,
                content);
        PointcutConfig pointcutConfig =
                ObjectMappers.readRequiredValue(mapper, content, PointcutConfig.class);
        configService.updatePointcutConfig(priorVersion, pointcutConfig);
        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        ObjectWriter writer = mapper.writerWithView(UiView.class);
        writer.writeValue(jg, pointcutConfig);
        jg.close();
        return sb.toString();
    }

    @POST("/backend/config/pointcut/-")
    void removePointcutConfig(String content) throws IOException {
        logger.debug("removePointcutConfig(): content={}", content);
        String version = ObjectMappers.readRequiredValue(mapper, content, String.class);
        configService.deletePointcutConfig(version);
    }

    // returns the first <limit> matching type names, ordered alphabetically (case-insensitive)
    private ImmutableList<String> getMatchingTypeNames(String partialTypeName, int limit) {
        Set<String> typeNames = Sets.newHashSet();
        typeNames.addAll(parsedTypeCache.getMatchingTypeNames(partialTypeName, limit));
        typeNames.addAll(classpathCache.getMatchingTypeNames(partialTypeName, limit));
        ImmutableList<String> sortedTypeNames =
                Ordering.from(String.CASE_INSENSITIVE_ORDER).immutableSortedCopy(typeNames);
        if (sortedTypeNames.size() > limit) {
            return sortedTypeNames.subList(0, limit);
        } else {
            return sortedTypeNames;
        }
    }

    // returns the first <limit> matching method names, ordered alphabetically (case-insensitive)
    private ImmutableList<String> getMatchingMethodNames(String typeName, String partialMethodName,
            int limit) {
        String partialMethodNameUpper = partialMethodName.toUpperCase(Locale.ENGLISH);
        Set<String> methodNames = Sets.newHashSet();
        for (ParsedType parsedType : getParsedTypes(typeName)) {
            for (ParsedMethod parsedMethod : parsedType.getMethods()) {
                String methodName = parsedMethod.getName();
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
        }
        ImmutableList<String> sortedMethodNames =
                Ordering.from(String.CASE_INSENSITIVE_ORDER).immutableSortedCopy(methodNames);
        if (methodNames.size() > limit) {
            return sortedMethodNames.subList(0, limit);
        } else {
            return sortedMethodNames;
        }
    }

    private List<ParsedMethod> getParsedMethods(String typeName, String methodName) {
        List<ParsedType> parsedTypes = getParsedTypes(typeName);
        // use set to remove duplicate methods (e.g. same type loaded by multiple class loaders)
        Set<ParsedMethod> parsedMethods = Sets.newHashSet();
        for (ParsedType parsedType : parsedTypes) {
            for (ParsedMethod parsedMethod : parsedType.getMethods()) {
                if (parsedMethod.getName().equals(methodName)) {
                    parsedMethods.add(parsedMethod);
                }
            }
        }
        // order methods by accessibility, then by name, then by number of args
        return ParsedMethodOrdering.INSTANCE.sortedCopy(parsedMethods);
    }

    private List<ParsedType> getParsedTypes(String typeName) {
        List<ParsedType> parsedTypes = Lists.newArrayList();
        parsedTypes.addAll(parsedTypeCache.getParsedTypes(typeName));
        parsedTypes.addAll(classpathCache.getParsedTypes(typeName));
        return parsedTypes;
    }

    private static class TypesRequest {

        private final String partialTypeName;
        private final int limit;

        @JsonCreator
        TypesRequest(@JsonProperty("partialTypeName") @Nullable String partialTypeName,
                @JsonProperty("limit") int limit) throws JsonMappingException {
            checkRequiredProperty(partialTypeName, "partialTypeName");
            this.partialTypeName = partialTypeName;
            this.limit = limit;
        }

        private String getPartialTypeName() {
            return partialTypeName;
        }

        private int getLimit() {
            return limit;
        }
    }

    private static class MethodNamesRequest {

        private final String type;
        private final String partialMethodName;
        private final int limit;

        @JsonCreator
        MethodNamesRequest(@JsonProperty("type") @Nullable String type,
                @JsonProperty("partialMethodName") @Nullable String partialMethodName,
                @JsonProperty("limit") int limit) throws JsonMappingException {
            checkRequiredProperty(type, "type");
            checkRequiredProperty(partialMethodName, "partialMethodName");
            this.type = type;
            this.partialMethodName = partialMethodName;
            this.limit = limit;
        }

        private String getType() {
            return type;
        }

        private String getPartialMethodName() {
            return partialMethodName;
        }

        private int getLimit() {
            return limit;
        }
    }

    private static class MethodSignaturesRequest {

        private final String type;
        private final String methodName;

        @JsonCreator
        MethodSignaturesRequest(@JsonProperty("type") @Nullable String type,
                @JsonProperty("methodName") @Nullable String methodName)
                throws JsonMappingException {
            checkRequiredProperty(type, "type");
            checkRequiredProperty(methodName, "methodName");
            this.type = type;
            this.methodName = methodName;
        }

        private String getType() {
            return type;
        }

        private String getMethodName() {
            return methodName;
        }
    }
}
