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

import io.informant.api.Logger;
import io.informant.api.LoggerFactory;
import io.informant.core.weaving.ParsedMethod;
import io.informant.core.weaving.ParsedTypeCache;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Locale;

import org.objectweb.asm.Type;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Json service to read pointcut data.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class PointcutConfigJsonService implements JsonService {

    private static final Logger logger = LoggerFactory.getLogger(PointcutConfigJsonService.class);
    private static final Gson gson = new Gson();

    private final ParsedTypeCache parsedTypeCache;

    @Inject
    PointcutConfigJsonService(ParsedTypeCache parsedTypeCache) {
        this.parsedTypeCache = parsedTypeCache;
    }

    @JsonServiceMethod
    String getMatchingTypeNames(String requestJson) throws IOException {
        logger.debug("getMatchingTypeNames(): requestJson={}", requestJson);
        TypeNameRequest request = gson.fromJson(requestJson, TypeNameRequest.class);
        List<String> matchingTypeNames = parsedTypeCache.getMatchingTypeNames(
                request.partialTypeName, request.limit);
        return gson.toJson(matchingTypeNames);
    }

    @JsonServiceMethod
    String getMatchingMethodNames(String requestJson) throws IOException {
        logger.debug("getMatchingMethodNames(): requestJson={}", requestJson);
        MethodNameRequest request = gson.fromJson(requestJson, MethodNameRequest.class);
        List<String> matchingMethodNames = parsedTypeCache.getMatchingMethodNames(
                request.typeName, request.partialMethodName, request.limit);
        return gson.toJson(matchingMethodNames);
    }

    @JsonServiceMethod
    String getMatchingMethods(String requestJson) throws IOException {
        logger.debug("getMatchingMethods(): requestJson={}", requestJson);
        MethodRequest request = gson.fromJson(requestJson, MethodRequest.class);
        List<ParsedMethod> parsedMethods = parsedTypeCache.getMatchingParsedMethods(
                request.typeName, request.methodName);
        JsonArray matchingMethods = new JsonArray();
        for (ParsedMethod parsedMethod : parsedMethods) {
            JsonObject matchingMethod = new JsonObject();
            matchingMethod.add("name", new JsonPrimitive(parsedMethod.getName()));
            JsonArray argTypeNames = new JsonArray();
            for (Type argType : parsedMethod.getArgTypes()) {
                argTypeNames.add(new JsonPrimitive(getSimplifiedClassName(argType)));
            }
            matchingMethod.add("argTypeNames", argTypeNames);
            matchingMethod.add("returnTypeName", new JsonPrimitive(
                    getSimplifiedClassName(parsedMethod.getReturnType())));
            JsonArray modifiers = new JsonArray();
            for (String modifier : Modifier.toString(parsedMethod.getModifiers()).split(" ")) {
                modifiers.add(new JsonPrimitive(modifier.toUpperCase(Locale.ENGLISH)));
            }
            matchingMethod.add("modifiers", modifiers);
            matchingMethods.add(matchingMethod);
        }
        return gson.toJson(matchingMethods);
    }

    // strip "java.lang." from String, Object, etc
    private String getSimplifiedClassName(Type type) {
        String className = type.getClassName();
        if (className.matches("java\\.lang\\.[^\\.]+")) {
            return className.substring("java.lang.".length());
        }
        return className;
    }

    class TypeNameRequest {

        private String partialTypeName;
        private int limit;

        public String getPartialTypeName() {
            return partialTypeName;
        }
        public void setPartialTypeName(String partialTypeName) {
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

        private String typeName;
        private String partialMethodName;
        private int limit;

        public String getTypeName() {
            return typeName;
        }
        public void setTypeName(String typeName) {
            this.typeName = typeName;
        }
        public String getPartialMethodName() {
            return partialMethodName;
        }
        public void setPartialMethodName(String partialMethodName) {
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

        private String typeName;
        private String methodName;

        public String getTypeName() {
            return typeName;
        }
        public void setTypeName(String typeName) {
            this.typeName = typeName;
        }
        public String getMethodName() {
            return methodName;
        }
        public void setMethodName(String methodName) {
            this.methodName = methodName;
        }
    }
}
