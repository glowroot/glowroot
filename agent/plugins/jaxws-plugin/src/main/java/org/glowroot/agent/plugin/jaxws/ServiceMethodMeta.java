/*
 * Copyright 2017 the original author or authors.
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
package org.glowroot.agent.plugin.jaxws;

import java.lang.reflect.Method;

public class ServiceMethodMeta {

    private final String serviceClassName;
    private final String methodName;

    private final String altTransactionName;

    public ServiceMethodMeta(Method method) {
        Class<?> serviceClass = method.getDeclaringClass();
        serviceClassName = serviceClass.getName();
        methodName = method.getName();
        altTransactionName = serviceClass.getSimpleName() + "#" + methodName;
    }

    String getServiceClassName() {
        return serviceClassName;
    }

    String getMethodName() {
        return methodName;
    }

    String getAltTransactionName() {
        return altTransactionName;
    }
}
