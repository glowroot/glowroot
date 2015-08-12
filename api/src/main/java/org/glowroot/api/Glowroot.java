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
package org.glowroot.api;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.internal.GlowrootService;
import org.glowroot.api.internal.NopGlowrootService;

public class Glowroot {

    private static final Logger logger = LoggerFactory.getLogger(Glowroot.class);

    private static final GlowrootService glowrootService;

    static {
        GlowrootService service;
        try {
            Class<?> registryClass =
                    Class.forName("org.glowroot.transaction.ServiceRegistryImpl");
            Method getInstanceMethod = registryClass.getMethod("getGlowrootService");
            service = (GlowrootService) getInstanceMethod.invoke(null);
            if (service == null) {
                service = NopGlowrootService.INSTANCE;
            }
        } catch (Exception e) {
            // this is expected when Glowroot is not running
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            service = NopGlowrootService.INSTANCE;
        }
        glowrootService = service;
    }

    private Glowroot() {}

    /**
     * Set the transaction type that is used for aggregation.
     * 
     * If there is no current transaction, this method does nothing.
     */
    public static void setTransactionType(@Nullable String transactionType) {
        glowrootService.setTransactionType(transactionType);
    }

    /**
     * Set the transaction name that is used for aggregation.
     * 
     * If there is no current transaction, this method does nothing.
     */
    public static void setTransactionName(@Nullable String transactionName) {
        glowrootService.setTransactionName(transactionName);
    }

    /**
     * Marks the transaction as an error with the given message.
     * 
     * The error message text is captured from {@code Throwable#getMessage()}.
     * 
     * If this is called multiple times within a single transaction, only the first call has any
     * effect, and subsequent calls are ignored.
     * 
     * If there is no current transaction, this method does nothing.
     */
    public static void setTransactionError(Throwable t) {
        glowrootService.setTransactionError(t);
    }

    /**
     * Marks the transaction as an error with the given message.
     * 
     * If {@code message} is empty or null, then the error message text is captured from
     * {@code Throwable#getMessage()}.
     * 
     * If this is called multiple times within a single transaction, only the first call has any
     * effect, and subsequent calls are ignored.
     * 
     * If there is no current transaction, this method does nothing.
     */
    public static void setTransactionError(@Nullable String message) {
        glowrootService.setTransactionError(message);
    }

    /**
     * Marks the transaction as an error with the given message.
     * 
     * If this is called multiple times within a single transaction, only the first call has any
     * effect, and subsequent calls are ignored.
     * 
     * If there is no current transaction, this method does nothing.
     */
    public static void setTransactionError(@Nullable String message, Throwable t) {
        glowrootService.setTransactionError(message, t);
    }

    /**
     * Sets the user attribute on the transaction.
     * 
     * If there is no current transaction, this method does nothing.
     */
    public static void setTransactionUser(@Nullable String user) {
        glowrootService.setTransactionUser(user);
    }

    /**
     * Adds an attribute on the current transaction with the specified {@code name} and
     * {@code value}. A transaction's attributes are displayed when viewing a trace on the trace
     * explorer page.
     * 
     * Subsequent calls to this method with the same {@code name} on the same transaction will add
     * an additional attribute if there is not already an attribute with the same {@code name} and
     * {@code value}.
     * 
     * If there is no current transaction, this method does nothing.
     * 
     * {@code null} values are normalized to the empty string.
     */
    public static void addTransactionCustomAttribute(String name, @Nullable String value) {
        glowrootService.addTransactionCustomAttribute(name, value);
    }

    /**
     * Overrides the default slow trace threshold (Configuration &gt; General &gt; Slow trace
     * threshold) for the current transaction. This can be used to store particular traces at a
     * lower or higher threshold than the general threshold.
     * 
     * If this is called multiple times for a given transaction, the minimum {@code threshold} will
     * be used.
     * 
     * If there is no current transaction, this method does nothing.
     */
    public static void setTransactionSlowThreshold(long threshold, TimeUnit unit) {
        glowrootService.setTransactionSlowThreshold(threshold, unit);
    }
}
