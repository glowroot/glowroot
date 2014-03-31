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
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.ObjectMappers;
import org.glowroot.markers.Singleton;
import org.glowroot.weaving.ParsedMethod;
import org.glowroot.weaving.ParsedType;
import org.glowroot.weaving.ParsedTypeCache;
import org.glowroot.weaving.ParsedTypeCache.ParsedMethodOrdering;

import static org.glowroot.common.ObjectMappers.checkRequiredProperty;

/**
 * Json service to read and update pointcut config data, bound to /backend/pointcut.
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

    private final ParsedTypeCache parsedTypeCache;
    private final ClasspathCache classpathCache;

    PointcutConfigJsonService(ParsedTypeCache parsedTypeCache,
            ClasspathCache classpathTypeCache) {
        this.parsedTypeCache = parsedTypeCache;
        this.classpathCache = classpathTypeCache;
    }

    // this is marked as @GET so it can be used without update rights (e.g. demo instance)
    @GET("/backend/pointcut/pre-load-auto-complete")
    void preLoadAutoComplete() throws IOException {
        logger.debug("preLoadAutoComplete()");
        // HttpServer is configured with a very small thread pool to keep number of threads down
        // (currently only a single thread), so spawn a background thread to perform the pre-loading
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

    @GET("/backend/pointcut/matching-type-names")
    String getMatchingTypeNames(String content) throws IOException {
        logger.debug("getMatchingTypeNames(): content={}", content);
        TypeNameRequest request =
                ObjectMappers.readRequiredValue(mapper, content, TypeNameRequest.class);
        List<String> matchingTypeNames = getMatchingTypeNames(request.getPartialTypeName(),
                request.getLimit());

        return mapper.writeValueAsString(matchingTypeNames);
    }

    @GET("/backend/pointcut/matching-method-names")
    String getMatchingMethodNames(String content) throws IOException {
        logger.debug("getMatchingMethodNames(): content={}", content);
        MethodNameRequest request =
                ObjectMappers.readRequiredValue(mapper, content, MethodNameRequest.class);
        List<String> matchingMethodNames = getMatchingMethodNames(request.getTypeName(),
                request.getPartialMethodName(), request.getLimit());
        return mapper.writeValueAsString(matchingMethodNames);
    }

    @GET("/backend/pointcut/matching-methods")
    String getMatchingMethods(String content) throws IOException {
        logger.debug("getMatchingMethods(): content={}", content);
        MethodRequest request =
                ObjectMappers.readRequiredValue(mapper, content, MethodRequest.class);
        List<ParsedMethod> parsedMethods = getParsedMethods(request.getTypeName(),
                request.getMethodName());
        ArrayNode matchingMethods = mapper.createArrayNode();
        for (ParsedMethod parsedMethod : parsedMethods) {
            ObjectNode matchingMethod = mapper.createObjectNode();
            matchingMethod.put("name", parsedMethod.getName());
            ArrayNode argTypeNames = mapper.createArrayNode();
            for (String argTypeName : parsedMethod.getArgTypeNames()) {
                argTypeNames.add(argTypeName);
            }
            matchingMethod.put("argTypeNames", argTypeNames);
            matchingMethod.put("returnTypeName", parsedMethod.getReturnTypeName());
            ArrayNode modifiers = mapper.createArrayNode();
            String modifierNames = Modifier.toString(parsedMethod.getModifiers());
            for (String modifier : splitter.split(modifierNames)) {
                modifiers.add(modifier.toLowerCase(Locale.ENGLISH));
            }
            matchingMethod.put("modifiers", modifiers);
            matchingMethods.add(matchingMethod);
        }
        return mapper.writeValueAsString(matchingMethods);
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

    private static class TypeNameRequest {

        private final String partialTypeName;
        private final int limit;

        @JsonCreator
        TypeNameRequest(@JsonProperty("partialTypeName") @Nullable String partialTypeName,
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

    private static class MethodNameRequest {

        private final String typeName;
        private final String partialMethodName;
        private final int limit;

        @JsonCreator
        MethodNameRequest(@JsonProperty("typeName") @Nullable String typeName,
                @JsonProperty("partialMethodName") @Nullable String partialMethodName,
                @JsonProperty("limit") int limit) throws JsonMappingException {
            checkRequiredProperty(typeName, "typeName");
            checkRequiredProperty(partialMethodName, "partialMethodName");
            this.typeName = typeName;
            this.partialMethodName = partialMethodName;
            this.limit = limit;
        }

        private String getTypeName() {
            return typeName;
        }

        private String getPartialMethodName() {
            return partialMethodName;
        }

        private int getLimit() {
            return limit;
        }
    }

    private static class MethodRequest {

        private final String typeName;
        private final String methodName;

        @JsonCreator
        MethodRequest(@JsonProperty("typeName") @Nullable String typeName,
                @JsonProperty("methodName") @Nullable String methodName)
                throws JsonMappingException {
            checkRequiredProperty(typeName, "typeName");
            checkRequiredProperty(methodName, "methodName");
            this.typeName = typeName;
            this.methodName = methodName;
        }

        private String getTypeName() {
            return typeName;
        }

        private String getMethodName() {
            return methodName;
        }
    }
}
