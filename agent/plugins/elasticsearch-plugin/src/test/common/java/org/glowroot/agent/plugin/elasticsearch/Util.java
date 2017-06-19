/**
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
package org.glowroot.agent.plugin.elasticsearch;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;

class Util {

    private static final Constructor<?> PRE_BUILT_TRANSPORT_CLIENT_CONSTRUCTOR;
    private static final Method TRANSPORT_CLIENT_BUILDER_METHOD;
    private static final Method TRANSPORT_CLIENT_BUILDER_BUILD_METHOD;

    static {
        PRE_BUILT_TRANSPORT_CLIENT_CONSTRUCTOR = getPreBuiltTransportClientConstructor();
        TRANSPORT_CLIENT_BUILDER_METHOD = getTransportClientBuilderMethod();
        TRANSPORT_CLIENT_BUILDER_BUILD_METHOD = getTransportClientBuilderBuildMethod();
    }

    static TransportClient client() throws Exception {
        if (PRE_BUILT_TRANSPORT_CLIENT_CONSTRUCTOR == null) {
            Object transportClientBuilder = TRANSPORT_CLIENT_BUILDER_METHOD.invoke(null);
            return (TransportClient) TRANSPORT_CLIENT_BUILDER_BUILD_METHOD
                    .invoke(transportClientBuilder);
        } else {
            return (TransportClient) PRE_BUILT_TRANSPORT_CLIENT_CONSTRUCTOR
                    .newInstance(Settings.EMPTY, new Class[0]);
        }
    }

    // elasticsearch 5.x
    private static Constructor<?> getPreBuiltTransportClientConstructor() {
        try {
            Class<?> preBuildTransportClientClass =
                    Class.forName("org.elasticsearch.transport.client.PreBuiltTransportClient");
            return preBuildTransportClientClass.getConstructor(Settings.class, Class[].class);
        } catch (ClassNotFoundException e) {
            return null;
        } catch (NoSuchMethodException e) {
            return null;
        } catch (SecurityException e) {
            return null;
        }
    }

    // elasticsearch 2.x
    private static Method getTransportClientBuilderMethod() {
        try {
            return TransportClient.class.getMethod("builder");
        } catch (NoSuchMethodException e) {
            return null;
        } catch (SecurityException e) {
            return null;
        }
    }

    // elasticsearch 2.x
    private static Method getTransportClientBuilderBuildMethod() {
        try {
            Class<?> transportClientBuilderClass =
                    Class.forName(TransportClient.class.getName() + "$Builder");
            return transportClientBuilderClass.getMethod("build");
        } catch (ClassNotFoundException e) {
            return null;
        } catch (NoSuchMethodException e) {
            return null;
        } catch (SecurityException e) {
            return null;
        }
    }
}
