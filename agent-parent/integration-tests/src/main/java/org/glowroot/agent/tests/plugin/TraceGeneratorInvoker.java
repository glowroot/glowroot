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
package org.glowroot.agent.tests.plugin;

import java.lang.reflect.Method;
import java.util.Map;

import javax.annotation.Nullable;

public class TraceGeneratorInvoker {

    private final @Nullable Method transactionTypeMethod;
    private final @Nullable Method transactionNameMethod;
    private final @Nullable Method headlineMethod;
    private final @Nullable Method attributesMethod;
    private final @Nullable Method errorMethod;

    public TraceGeneratorInvoker(Class<?> clazz) {
        transactionTypeMethod = Invokers.getMethod(clazz, "transactionType");
        transactionNameMethod = Invokers.getMethod(clazz, "transactionName");
        headlineMethod = Invokers.getMethod(clazz, "headline");
        attributesMethod = Invokers.getMethod(clazz, "attributes");
        errorMethod = Invokers.getMethod(clazz, "error");
    }

    String transactionType(Object request) {
        return Invokers.invoke(transactionTypeMethod, request, "");
    }

    String transactionName(Object request) {
        return Invokers.invoke(transactionNameMethod, request, "");
    }

    String headline(Object request) {
        return Invokers.invoke(headlineMethod, request, "");
    }

    // TODO report checker framework issue that occurs without this warning suppression
    @SuppressWarnings("return.type.incompatible")
    @Nullable
    Map<String, String> attributes(Object request) {
        return Invokers.invoke(attributesMethod, request, null);
    }

    @Nullable
    String error(Object request) {
        return Invokers.invoke(errorMethod, request, null);
    }
}
