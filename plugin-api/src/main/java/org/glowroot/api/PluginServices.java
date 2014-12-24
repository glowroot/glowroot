/*
 * Copyright 2011-2014 the original author or authors.
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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.weaving.Pointcut;

/**
 * This is the primary service exposed to plugins. Plugins acquire a {@code PluginServices} instance
 * from {@link #get(String)}, and they can (and should) cache the {@code PluginServices} instance
 * for the life of the jvm to avoid looking it up every time it is needed (which is often).
 * 
 * Here is a basic example of how to use {@code PluginServices} to create a plugin that captures
 * calls to Spring validators:
 * 
 * <pre>
 * &#064;Aspect
 * public class SpringAspect {
 * 
 *     private static final PluginServices pluginServices = PluginServices.get(&quot;spring&quot;);
 * 
 *     &#064;Pointcut(className = &quot;org.springframework.validation.Validator&quot;,
 *             methodName = &quot;validate&quot;, methodParameterTypes = {&quot;..&quot;},
 *             metricName = &quot;spring validator&quot;)
 *     public static class ValidatorAdvice {
 *         private static final MetricName metricName =
 *                 pluginServices.getMetricName(ValidatorAdvice.class);
 *         &#064;IsEnabled
 *         public static boolean isEnabled() {
 *             return pluginServices.isEnabled();
 *         }
 *         &#064;OnBefore
 *         public static TraceEntry onBefore(@BindReceiver Object validator) {
 *             return pluginServices.startTraceEntry(
 *                     MessageSupplier.from(&quot;spring validator: {}&quot;, validator.getClass().getName()),
 *                     metricName);
 *         }
 *         &#064;OnAfter
 *         public static void onAfter(@BindTraveler TraceEntry traceEntry) {
 *             traceEntry.end();
 *         }
 *     }
 * }
 * </pre>
 */
public abstract class PluginServices {

    private static final Logger logger = LoggerFactory.getLogger(PluginServices.class);

    private static final String HANDLE_CLASS_NAME =
            "org.glowroot.transaction.PluginServicesRegistry";
    private static final String HANDLE_METHOD_NAME = "get";

    /**
     * Returns the {@code PluginServices} instance for the specified {@code pluginId}.
     * 
     * The return value can (and should) be cached by the plugin for the life of the jvm to avoid
     * looking it up every time it is needed (which is often).
     */
    public static PluginServices get(String pluginId) {
        if (pluginId == null) {
            logger.error("get(): argument 'pluginId' must be non-null");
            throw new AssertionError("Argument 'pluginId' must be non-null");
        }
        return getPluginServices(pluginId);
    }

    protected PluginServices() {}

    /**
     * Registers a listener that will receive a callback when the plugin's property values are
     * changed, the plugin is enabled/disabled, or Glowroot is enabled/disabled.
     * 
     * This allows the useful plugin optimization of caching the results of {@link #isEnabled()},
     * {@link #getStringProperty(String)}, {@link #getBooleanProperty(String)}, and
     * {@link #getDoubleProperty(String)} as {@code volatile} fields, and updating the cached values
     * anytime {@link ConfigListener#onChange()} is called.
     */
    public abstract void registerConfigListener(ConfigListener listener);

    /**
     * Returns whether the plugin is enabled. When Glowroot itself is disabled, this returns
     * {@code false}.
     * 
     * Plugins can be individually disabled on the configuration page.
     */
    public abstract boolean isEnabled();

    /**
     * Returns the {@code String} plugin property value with the specified {@code name}.
     * {@code null} is never returned. If there is no {@code String} plugin property with the
     * specified {@code name} then the empty string {@code ""} is returned.
     * 
     * Plugin properties are scoped per plugin. The are defined in the plugin's
     * META-INF/glowroot.plugin.json file, and can be modified (assuming they are not marked as
     * hidden) on the configuration page under the plugin's configuration section.
     */
    public abstract String getStringProperty(String name);

    /**
     * Returns the {@code boolean} plugin property value with the specified {@code name}. If there
     * is no {@code boolean} plugin property with the specified {@code name} then {@code false} is
     * returned.
     * 
     * Plugin properties are scoped per plugin. The are defined in the plugin's
     * META-INF/glowroot.plugin.json file, and can be modified (assuming they are not marked as
     * hidden) on the configuration page under the plugin's configuration section.
     */
    public abstract boolean getBooleanProperty(String name);

    /**
     * Returns the {@code Double} plugin property value with the specified {@code name}. If there is
     * no {@code Double} plugin property with the specified {@code name} then {@code null} is
     * returned.
     * 
     * Plugin properties are scoped per plugin. The are defined in the plugin's
     * META-INF/glowroot.plugin.json file, and can be modified (assuming they are not marked as
     * hidden) on the configuration page under the plugin's configuration section.
     */
    public abstract @Nullable Double getDoubleProperty(String name);

    /**
     * Returns the {@code MetricName} instance for the specified {@code adviceClass}.
     * 
     * {@code adviceClass} must be a {@code Class} with a {@link Pointcut} annotation that has a
     * non-empty {@link Pointcut#metricName()}. This is how the {@code MetricName} is named.
     * 
     * The same {@code MetricName} is always returned for a given {@code adviceClass}.
     * 
     * The return value can (and should) be cached by the plugin for the life of the jvm to avoid
     * looking it up every time it is needed (which is often).
     */
    public abstract MetricName getMetricName(Class<?> adviceClass);

    /**
     * If there is no active transaction, a new transaction is started.
     * 
     * If there is already an active transaction, this method acts the same as
     * {@link #startTraceEntry(MessageSupplier, MetricName)} (the transaction name and type are not
     * modified on the existing transaction).
     */
    public abstract TraceEntry startTransaction(String transactionType, String transactionName,
            MessageSupplier messageSupplier, MetricName metricName);

    /**
     * Creates and starts a trace entry with the given {@code messageSupplier}. A transaction metric
     * for the specified metric is also started.
     * 
     * Since entries can be expensive in great quantities, there is a
     * {@code maxTraceEntriesPerTransaction} property on the configuration page to limit the number
     * of entries captured for any given trace.
     * 
     * Once a trace has accumulated {@code maxTraceEntriesPerTransaction} entries, this method
     * doesn't add new entries to the trace, but instead returns a dummy entry. A transaction metric
     * for the specified metric is still started, since metrics are very cheap, even in great
     * quantities. The dummy entry adhere to the {@link TraceEntry} contract and return the
     * specified {@link MessageSupplier} in response to {@link TraceEntry#getMessageSupplier()}.
     * Calling {@link TraceEntry#end()} on the dummy entry ends the transaction metric. If
     * {@link TraceEntry#endWithError(ErrorMessage)} is called on the dummy entry, then the dummy
     * entry will be escalated to a real entry. If
     * {@link TraceEntry#endWithStackTrace(long, TimeUnit)} is called on the dummy entry and the
     * dummy entry duration exceeds the specified threshold, then the dummy entry will be escalated
     * to a real entry. If {@link TraceEntry#endWithError(ErrorMessage)} is called on the dummy
     * entry, then the dummy entry will be escalated to a real entry. A hard cap (
     * {@code maxTraceEntriesPerTransaction * 2}) on the total number of (real) entries is applied
     * when escalating dummy entries to real entries.
     * 
     * If there is no current transaction, this method does nothing, and returns a no-op instance of
     * {@link TraceEntry}.
     */
    public abstract TraceEntry startTraceEntry(MessageSupplier messageSupplier,
            MetricName metricName);

    /**
     * Starts a transaction metric for the specified metric. If a transaction metric is already
     * running for the specified metric, it will keep an internal counter of the number of starts,
     * and it will only end the transaction metric after the corresponding number of ends.
     * 
     * If there is no current transaction, this method does nothing, and returns a no-op instance of
     * {@link TransactionMetric}.
     */
    public abstract TransactionMetric startTransactionMetric(MetricName metricName);

    /**
     * Adds a trace entry with duration zero. It does not set the error attribute on the trace,
     * which must be done with {@link TraceEntry#endWithError(ErrorMessage)} on the root entry.
     * 
     * If the error message has no throwable, a stack trace is captured and attached to the trace
     * entry.
     * 
     * This method bypasses the regular {@code maxTraceEntriesPerTransaction} check so that errors
     * after {@code maxTraceEntriesPerTransaction} will still be included in the trace. A hard cap (
     * {@code maxTraceEntriesPerTransaction * 2}) on the total number of entries is still applied,
     * after which this method does nothing.
     * 
     * If there is no current transaction, this method does nothing.
     */
    public abstract void addTraceEntry(ErrorMessage errorMessage);

    /**
     * Set the transaction type that is used for aggregation.
     * 
     * If there is no current transaction, this method does nothing.
     */
    public abstract void setTransactionType(@Nullable String transactionType);

    /**
     * Set the transaction name that is used for aggregation.
     * 
     * If there is no current transaction, this method does nothing.
     */
    public abstract void setTransactionName(@Nullable String transactionName);

    /**
     * Marks the transaction as an error with the given message. Normally transactions are only
     * marked as an error if {@link TraceEntry#endWithError(ErrorMessage)} is called on the root
     * entry. This method can be used to mark the entire transaction as an error from a nested
     * entry.
     * 
     * This should be used sparingly. Normally, entries should only mark themselves (using
     * {@link TraceEntry#endWithError(ErrorMessage)}), and let the root entry determine if the
     * transaction as a whole should be marked as an error.
     * 
     * E.g., this method is called from the logger plugin, to mark the entire transaction as an
     * error if an error is logged through one of the supported logger APIs.
     * 
     * If this is called multiple times within a single transaction, only the first call has any
     * effect, and subsequent calls are ignored.
     * 
     * If there is no current transaction, this method does nothing.
     */
    public abstract void setTransactionError(@Nullable String error);

    /**
     * Sets the user attribute on the transaction. This attribute is shared across all plugins, and
     * is generally set by the plugin that initiated the trace, but can be set by other plugins if
     * needed.
     * 
     * The user is used in a few ways:
     * <ul>
     * <li>The user is displayed when viewing a trace on the trace explorer page
     * <li>Traces can be filtered by their user on the trace explorer page
     * <li>Glowroot can be configured (using the configuration page) to capture traces for a
     * specific user using a lower threshold than normal (e.g. threshold=0 to capture all requests
     * for a specific user)
     * <li>Glowroot can be configured (using the configuration page) to perform profiling on all
     * transactions for a specific user
     * </ul>
     * 
     * If profiling is enabled for a specific user, this is activated (if the {@code user} matches)
     * at the time that this method is called, so it is best to call this method early in the
     * transaction.
     * 
     * If there is no current transaction, this method does nothing.
     */
    public abstract void setTransactionUser(@Nullable String user);

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
    public abstract void setTransactionCustomAttribute(String name, @Nullable String value);

    /**
     * Overrides the default trace store threshold (Configuration &gt; Traces &gt; Default store
     * threshold) for the current transaction. This can be used to store particular traces at a
     * lower or higher threshold than the general threshold.
     * 
     * If this is called multiple times for a given transaction, the minimum {@code threshold} will
     * be used.
     * 
     * If there is no current transaction, this method does nothing.
     */
    public abstract void setTraceStoreThreshold(long threshold, TimeUnit unit);

    /**
     * Returns whether a transaction is already being captured.
     * 
     * This method has very limited use. It should only be used by top-level pointcuts that define a
     * transaction, and that do not want to create a entry if they are already inside of an existing
     * transaction.
     */
    public abstract boolean isInTransaction();

    private static PluginServices getPluginServices(String pluginId) {
        try {
            Class<?> handleClass = Class.forName(HANDLE_CLASS_NAME);
            Method handleMethod = handleClass.getMethod(HANDLE_METHOD_NAME, String.class);
            PluginServices pluginServices = (PluginServices) handleMethod.invoke(null, pluginId);
            if (pluginServices == null) {
                // null return value indicates that glowroot is still starting
                logger.error("plugin services requested while glowroot is still starting",
                        new IllegalStateException());
                throw new AssertionError(
                        "Plugin services requested while glowroot is still starting");
            }
            return pluginServices;
        } catch (ClassNotFoundException e) {
            // this really really really shouldn't happen
            logger.error(e.getMessage(), e);
            throw new AssertionError(e);
        } catch (NoSuchMethodException e) {
            // this really really really shouldn't happen
            logger.error(e.getMessage(), e);
            throw new AssertionError(e);
        } catch (SecurityException e) {
            // this really really really shouldn't happen
            logger.error(e.getMessage(), e);
            throw new AssertionError(e);
        } catch (IllegalAccessException e) {
            // this really really really shouldn't happen
            logger.error(e.getMessage(), e);
            throw new AssertionError(e);
        } catch (IllegalArgumentException e) {
            // this really really really shouldn't happen
            logger.error(e.getMessage(), e);
            throw new AssertionError(e);
        } catch (InvocationTargetException e) {
            // this really really really shouldn't happen
            logger.error(e.getMessage(), e);
            throw new AssertionError(e);
        }
    }

    public interface ConfigListener {
        // the new config is not passed to onChange so that the receiver has to get the latest,
        // this avoids race condition worries that two updates may get sent to the receiver in the
        // wrong order
        void onChange();
    }
}
