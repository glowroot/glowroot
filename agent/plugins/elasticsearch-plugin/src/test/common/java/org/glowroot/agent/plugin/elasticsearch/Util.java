/**
 * Copyright 2017-2018 the original author or authors.
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
import java.net.InetSocketAddress;

import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;

class Util {

    private static final Constructor<?> PRE_BUILT_TRANSPORT_CLIENT_CONSTRUCTOR;
    private static final Method TRANSPORT_CLIENT_BUILDER_METHOD;
    private static final Method TRANSPORT_CLIENT_BUILDER_BUILD_METHOD;
    private static final Constructor<?> TRANSPORT_ADDRESS_CONSTRUCTOR;
    private static final Constructor<?> INET_SOCKET_TRANSPORT_ADDRESS_CONSTRUCTOR;

    static {
        PRE_BUILT_TRANSPORT_CLIENT_CONSTRUCTOR = getPreBuiltTransportClientConstructor();
        TRANSPORT_CLIENT_BUILDER_METHOD = getTransportClientBuilderMethod();
        TRANSPORT_CLIENT_BUILDER_BUILD_METHOD = getTransportClientBuilderBuildMethod();
        TRANSPORT_ADDRESS_CONSTRUCTOR = getTransportAddressConstructor();
        INET_SOCKET_TRANSPORT_ADDRESS_CONSTRUCTOR = getInetSocketTransportAddressConstructor();
    }

    static TransportClient client(InetSocketAddress socketAddress) throws Exception {
        TransportClient client;
        if (PRE_BUILT_TRANSPORT_CLIENT_CONSTRUCTOR == null) {
            Object transportClientBuilder = TRANSPORT_CLIENT_BUILDER_METHOD.invoke(null);
            client = (TransportClient) TRANSPORT_CLIENT_BUILDER_BUILD_METHOD
                    .invoke(transportClientBuilder);
        } else {
            client = (TransportClient) PRE_BUILT_TRANSPORT_CLIENT_CONSTRUCTOR
                    .newInstance(Settings.EMPTY, new Class[0]);
        }
        TransportAddress address;
        if (TRANSPORT_ADDRESS_CONSTRUCTOR == null) {
            address = (TransportAddress) INET_SOCKET_TRANSPORT_ADDRESS_CONSTRUCTOR
                    .newInstance(socketAddress);
        } else {
            address = (TransportAddress) TRANSPORT_ADDRESS_CONSTRUCTOR.newInstance(socketAddress);
        }
        client.addTransportAddress(address);
        return client;
    }

    // elasticsearch 5.x
    private static Constructor<?> getPreBuiltTransportClientConstructor() {
        try {
            Class<?> clazz =
                    Class.forName("org.elasticsearch.transport.client.PreBuiltTransportClient");
            return clazz.getConstructor(Settings.class, Class[].class);
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
            Class<?> clazz = Class.forName(TransportClient.class.getName() + "$Builder");
            return clazz.getMethod("build");
        } catch (ClassNotFoundException e) {
            return null;
        } catch (NoSuchMethodException e) {
            return null;
        } catch (SecurityException e) {
            return null;
        }
    }

    // elasticsearch 6.x
    private static Constructor<?> getTransportAddressConstructor() {
        try {
            Class<?> clazz = Class.forName("org.elasticsearch.common.transport.TransportAddress");
            return clazz.getConstructor(InetSocketAddress.class);
        } catch (ClassNotFoundException e) {
            return null;
        } catch (NoSuchMethodException e) {
            return null;
        } catch (SecurityException e) {
            return null;
        }
    }

    // elasticsearch 2.x and 5.x
    private static Constructor<?> getInetSocketTransportAddressConstructor() {
        try {
            Class<?> clazz =
                    Class.forName("org.elasticsearch.common.transport.InetSocketTransportAddress");
            return clazz.getConstructor(InetSocketAddress.class);
        } catch (ClassNotFoundException e) {
            return null;
        } catch (NoSuchMethodException e) {
            return null;
        } catch (SecurityException e) {
            return null;
        }
    }
}
