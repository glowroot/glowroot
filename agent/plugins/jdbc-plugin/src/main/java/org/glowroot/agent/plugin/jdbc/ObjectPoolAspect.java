/*
 * Copyright 2019 the original author or authors.
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
package org.glowroot.agent.plugin.jdbc;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.checker.Nullable;
import org.glowroot.agent.plugin.api.config.BooleanProperty;
import org.glowroot.agent.plugin.api.config.ConfigService;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindReturn;
import org.glowroot.agent.plugin.api.weaving.IsEnabled;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.Pointcut;

public class ObjectPoolAspect {

    private static final ConfigService configService = Agent.getConfigService("jdbc");

    private static final BooleanProperty captureConnectionPoolLeaks =
            configService.getBooleanProperty("captureConnectionPoolLeaks");

    private static final BooleanProperty captureConnectionPoolLeakDetails =
            configService.getBooleanProperty("captureConnectionPoolLeakDetails");

    @Pointcut(
            className = "org.apache.commons.pool.impl.GenericObjectPool"
                    + "|org.apache.commons.pool2.impl.GenericObjectPool",
            methodName = "borrowObject", methodParameterTypes = {".."})
    public static class DbcpBorrowAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return captureConnectionPoolLeaks.value();
        }
        @OnReturn
        public static void onReturn(@BindReturn @Nullable Object resource, ThreadContext context) {
            if (resource != null) {
                context.trackResourceAcquired(resource, captureConnectionPoolLeakDetails.value());
            }
        }
    }

    @Pointcut(
            className = "org.apache.commons.pool.impl.GenericObjectPool"
                    + "|org.apache.commons.pool2.impl.GenericObjectPool",
            methodName = "returnObject|invalidateObject",
            methodParameterTypes = {"java.lang.Object"})
    public static class DbcpReturnAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return captureConnectionPoolLeaks.value();
        }
        @OnReturn
        public static void onReturn(ThreadContext context,
                @BindParameter @Nullable Object resource) {
            if (resource != null) {
                context.trackResourceReleased(resource);
            }
        }
    }

    @Pointcut(
            className = "org.apache.tomcat.jdbc.pool.ConnectionPool",
            methodName = "borrowConnection", methodParameterTypes = {".."})
    public static class TomcatBorrowAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return captureConnectionPoolLeaks.value();
        }
        @OnReturn
        public static void onReturn(@BindReturn @Nullable Object resource, ThreadContext context) {
            if (resource != null) {
                context.trackResourceAcquired(resource, captureConnectionPoolLeakDetails.value());
            }
        }
    }

    @Pointcut(
            className = "org.apache.tomcat.jdbc.pool.ConnectionPool",
            methodName = "returnConnection",
            methodParameterTypes = {"org.apache.tomcat.jdbc.pool.PooledConnection"})
    public static class TomcatReturnAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return captureConnectionPoolLeaks.value();
        }
        @OnReturn
        public static void onReturn(ThreadContext context,
                @BindParameter @Nullable Object resource) {
            if (resource != null) {
                context.trackResourceReleased(resource);
            }
        }
    }

    @Pointcut(
            className = "com.sun.gjc.spi.ManagedConnectionImpl", methodName = "getConnection",
            methodParameterTypes = {"javax.security.auth.Subject",
                    "javax.resource.spi.ConnectionRequestInfo"})
    public static class GlassfishBorrowAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return captureConnectionPoolLeaks.value();
        }
        @OnReturn
        public static void onReturn(@BindReturn @Nullable Object resource, ThreadContext context) {
            if (resource != null) {
                context.trackResourceAcquired(resource, captureConnectionPoolLeakDetails.value());
            }
        }
    }

    @Pointcut(
            className = "com.sun.gjc.spi.ManagedConnectionImpl", methodName = "connectionClosed",
            methodParameterTypes = {"java.lang.Exception", "com.sun.gjc.spi.base.ConnectionHolder"})
    public static class GlassfishReturnAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return captureConnectionPoolLeaks.value();
        }
        @OnReturn
        public static void onReturn(ThreadContext context,
                @SuppressWarnings("unused") @BindParameter @Nullable Exception e,
                @BindParameter Object connectionHolder) {
            if (connectionHolder != null) {
                context.trackResourceReleased(connectionHolder);
            }
        }
    }

    @Pointcut(
            className = "com.zaxxer.hikari.pool.BaseHikariPool", methodName = "getConnection",
            methodParameterTypes = {"long"})
    public static class HikariBorrowAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return captureConnectionPoolLeaks.value();
        }
        @OnReturn
        public static void onReturn(@BindReturn @Nullable Object resource, ThreadContext context) {
            if (resource != null) {
                context.trackResourceAcquired(resource, captureConnectionPoolLeakDetails.value());
            }
        }
    }

    @Pointcut(
            className = "com.zaxxer.hikari.proxy.ConnectionProxy", methodName = "close",
            methodParameterTypes = {})
    public static class HikariReturnAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return captureConnectionPoolLeaks.value();
        }
        @OnReturn
        public static void onReturn(ThreadContext context, @BindReceiver Object connectionProxy) {
            context.trackResourceReleased(connectionProxy);
        }
    }

    @Pointcut(
            className = "bitronix.tm.resource.jdbc.PoolingDataSource", methodName = "getConnection",
            methodParameterTypes = {})
    public static class BitronixBorrowAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return captureConnectionPoolLeaks.value();
        }
        @OnReturn
        public static void onReturn(@BindReturn @Nullable Object resource, ThreadContext context) {
            if (resource != null) {
                context.trackResourceAcquired(resource, captureConnectionPoolLeakDetails.value());
            }
        }
    }

    @Pointcut(
            className = "bitronix.tm.resource.jdbc.proxy.ConnectionJavaProxy", methodName = "close",
            methodParameterTypes = {})
    public static class BitronixReturnAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return captureConnectionPoolLeaks.value();
        }
        @OnReturn
        public static void onReturn(ThreadContext context, @BindReceiver Object connectionProxy) {
            context.trackResourceReleased(connectionProxy);
        }
    }
}
