/**
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
package io.informant.local.ui;

import io.informant.util.ObjectMappers;
import io.informant.util.Singleton;
import io.informant.weaving.ParsedMethod;
import io.informant.weaving.ParsedTypeCache;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import checkers.nullness.quals.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Json service to read pointcut data.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class PointcutConfigJsonService implements JsonService {

    private static final Logger logger = LoggerFactory.getLogger(PointcutConfigJsonService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final ParsedTypeCache parsedTypeCache;

    PointcutConfigJsonService(ParsedTypeCache parsedTypeCache) {
        this.parsedTypeCache = parsedTypeCache;
    }

    @JsonServiceMethod
    String getMatchingTypeNames(String content) throws IOException {
        logger.debug("getMatchingTypeNames(): content={}", content);
        TypeNameRequest request = mapper.readValue(content, TypeNameRequest.class);
        if (request.partialTypeName == null) {
            throw new IllegalStateException("Request missing partialTypeName attribute");
        }
        List<String> matchingTypeNames = parsedTypeCache.getMatchingTypeNames(
                request.partialTypeName, request.limit);
        return mapper.writeValueAsString(matchingTypeNames);
    }

    @JsonServiceMethod
    String getMatchingMethodNames(String content) throws IOException {
        logger.debug("getMatchingMethodNames(): content={}", content);
        MethodNameRequest request = mapper.readValue(content, MethodNameRequest.class);
        if (request.typeName == null) {
            throw new IllegalStateException("Request missing typeName attribute");
        }
        if (request.partialMethodName == null) {
            throw new IllegalStateException("Request missing partialMethodName attribute");
        }
        List<String> matchingMethodNames = parsedTypeCache.getMatchingMethodNames(
                request.typeName, request.partialMethodName, request.limit);
        return mapper.writeValueAsString(matchingMethodNames);
    }

    @JsonServiceMethod
    String getMatchingMethods(String content) throws IOException {
        logger.debug("getMatchingMethods(): content={}", content);
        MethodRequest request = mapper.readValue(content, MethodRequest.class);
        if (request.typeName == null) {
            throw new IllegalStateException("Request missing typeName attribute");
        }
        if (request.methodName == null) {
            throw new IllegalStateException("Request missing methodName attribute");
        }
        List<ParsedMethod> parsedMethods = parsedTypeCache.getMatchingParsedMethods(
                request.typeName, request.methodName);
        ArrayNode matchingMethods = mapper.createArrayNode();
        for (ParsedMethod parsedMethod : parsedMethods) {
            ObjectNode matchingMethod = mapper.createObjectNode();
            matchingMethod.put("name", parsedMethod.getName());
            ArrayNode argTypeNames = mapper.createArrayNode();
            for (String argTypeName : parsedMethod.getArgTypeNames()) {
                argTypeNames.add(getSimplifiedTypeName(argTypeName));
            }
            matchingMethod.put("argTypeNames", argTypeNames);
            matchingMethod.put("returnTypeName",
                    getSimplifiedTypeName(parsedMethod.getReturnTypeName()));
            ArrayNode modifiers = mapper.createArrayNode();
            for (String modifier : Modifier.toString(parsedMethod.getModifiers()).split(" ")) {
                modifiers.add(modifier.toUpperCase(Locale.ENGLISH));
            }
            matchingMethod.put("modifiers", modifiers);
            matchingMethods.add(matchingMethod);
        }
        return mapper.writeValueAsString(matchingMethods);
    }

    // strip "java.lang." from String, Object, etc
    private String getSimplifiedTypeName(String typeName) {
        if (typeName.matches("java\\.lang\\.[^\\.]+")) {
            return typeName.substring("java.lang.".length());
        }
        return typeName;
    }

    class TypeNameRequest {

        @Nullable
        private String partialTypeName;
        private int limit;

        @Nullable
        public String getPartialTypeName() {
            return partialTypeName;
        }
        public void setPartialTypeName(@Nullable String partialTypeName) {
            this.partialTypeName = partialTypeName;
        }
        public int getLimit() {
            return limit;
        }
        public void setLimit(int limit) {
            this.limit = limit;
        }
    }

    class MethodNameRequest {

        @Nullable
        private String typeName;
        @Nullable
        private String partialMethodName;
        private int limit;

        @Nullable
        public String getTypeName() {
            return typeName;
        }
        public void setTypeName(@Nullable String typeName) {
            this.typeName = typeName;
        }
        @Nullable
        public String getPartialMethodName() {
            return partialMethodName;
        }
        public void setPartialMethodName(@Nullable String partialMethodName) {
            this.partialMethodName = partialMethodName;
        }
        public int getLimit() {
            return limit;
        }
        public void setLimit(int limit) {
            this.limit = limit;
        }
    }

    class MethodRequest {

        @Nullable
        private String typeName;
        @Nullable
        private String methodName;

        @Nullable
        public String getTypeName() {
            return typeName;
        }
        public void setTypeName(@Nullable String typeName) {
            this.typeName = typeName;
        }
        @Nullable
        public String getMethodName() {
            return methodName;
        }
        public void setMethodName(@Nullable String methodName) {
            this.methodName = methodName;
        }
    }
}
