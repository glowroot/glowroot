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

import static io.informant.util.ObjectMappers.checkRequiredProperty;
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

import checkers.igj.quals.ReadOnly;
import checkers.nullness.quals.Nullable;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
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
    @ReadOnly
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final ParsedTypeCache parsedTypeCache;

    PointcutConfigJsonService(ParsedTypeCache parsedTypeCache) {
        this.parsedTypeCache = parsedTypeCache;
    }

    @JsonServiceMethod
    String getMatchingTypeNames(String content) throws IOException {
        logger.debug("getMatchingTypeNames(): content={}", content);
        TypeNameRequest request =
                ObjectMappers.readRequiredValue(mapper, content, TypeNameRequest.class);
        List<String> matchingTypeNames = parsedTypeCache.getMatchingTypeNames(
                request.partialTypeName, request.limit);
        return mapper.writeValueAsString(matchingTypeNames);
    }

    @JsonServiceMethod
    String getMatchingMethodNames(String content) throws IOException {
        logger.debug("getMatchingMethodNames(): content={}", content);
        MethodNameRequest request =
                ObjectMappers.readRequiredValue(mapper, content, MethodNameRequest.class);
        List<String> matchingMethodNames = parsedTypeCache.getMatchingMethodNames(
                request.typeName, request.partialMethodName, request.limit);
        return mapper.writeValueAsString(matchingMethodNames);
    }

    @JsonServiceMethod
    String getMatchingMethods(String content) throws IOException {
        logger.debug("getMatchingMethods(): content={}", content);
        MethodRequest request =
                ObjectMappers.readRequiredValue(mapper, content, MethodRequest.class);
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

        private final String partialTypeName;
        private final int limit;

        @JsonCreator
        TypeNameRequest(@JsonProperty("partialTypeName") @Nullable String partialTypeName,
                @JsonProperty("limit") int limit) throws JsonMappingException {
            checkRequiredProperty(partialTypeName, "partialTypeName");
            this.partialTypeName = partialTypeName;
            this.limit = limit;
        }

        String getPartialTypeName() {
            return partialTypeName;
        }

        int getLimit() {
            return limit;
        }
    }

    class MethodNameRequest {

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

        String getTypeName() {
            return typeName;
        }

        String getPartialMethodName() {
            return partialMethodName;
        }

        int getLimit() {
            return limit;
        }
    }

    class MethodRequest {

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

        @Nullable
        String getTypeName() {
            return typeName;
        }

        @Nullable
        String getMethodName() {
            return methodName;
        }
    }
}
