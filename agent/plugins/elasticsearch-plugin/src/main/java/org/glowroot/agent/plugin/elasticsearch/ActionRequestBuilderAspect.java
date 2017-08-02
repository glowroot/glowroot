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
package org.glowroot.agent.plugin.elasticsearch;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.AsyncQueryEntry;
import org.glowroot.agent.plugin.api.QueryEntry;
import org.glowroot.agent.plugin.api.QueryMessageSupplier;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.config.ConfigListener;
import org.glowroot.agent.plugin.api.config.ConfigService;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindReturn;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.Mixin;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.api.weaving.Shim;
import org.glowroot.agent.plugin.elasticsearch.ListenableActionFutureAspect.ListenableActionFutureMixin;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class ActionRequestBuilderAspect {

    private static final String QUERY_TYPE = "Elasticsearch";

    private static final ConfigService configService = Agent.getConfigService("elasticsearch");

    // visibility is provided by memoryBarrier in org.glowroot.config.ConfigService
    private static int stackTraceThresholdMillis;

    static {
        configService.registerConfigListener(new ConfigListener() {
            @Override
            public void onChange() {
                Double value = configService.getDoubleProperty("stackTraceThresholdMillis").value();
                stackTraceThresholdMillis = value == null ? Integer.MAX_VALUE : value.intValue();
            }
        });
    }

    @Shim("org.elasticsearch.action.ActionRequestBuilder")
    public interface ActionRequestBuilder {

        @Shim("org.elasticsearch.action.ActionRequest request()")
        @Nullable
        ActionRequest glowroot$request();
    }

    // the field and method names are verbose since they will be mixed in to existing classes
    @Mixin("org.elasticsearch.action.search.SearchRequestBuilder")
    public abstract static class SearchRequestBuilderImpl implements SearchRequestBuilder {

        private @Nullable Object glowroot$queryBuilder;

        @Override
        public @Nullable Object glowroot$getQueryBuilder() {
            return glowroot$queryBuilder;
        }

        @Override
        public void glowroot$setQueryBuilder(@Nullable Object queryBuilder) {
            glowroot$queryBuilder = queryBuilder;
        }
    }

    // the method names are verbose since they will be mixed in to existing classes
    public interface SearchRequestBuilder extends ActionRequestBuilder {
        @Nullable
        Object glowroot$getQueryBuilder();
        void glowroot$setQueryBuilder(@Nullable Object queryBuilder);
    }

    @Shim("org.elasticsearch.action.ActionRequest")
    public interface ActionRequest {}

    @Shim("org.elasticsearch.action.index.IndexRequest")
    public interface IndexRequest extends ActionRequest {
        @Nullable
        String index();
        @Nullable
        String type();
    }

    @Shim("org.elasticsearch.action.get.GetRequest")
    public interface GetRequest extends ActionRequest {
        @Nullable
        String index();
        @Nullable
        String type();
        @Nullable
        String id();
    }

    @Shim("org.elasticsearch.action.update.UpdateRequest")
    public interface UpdateRequest extends ActionRequest {
        @Nullable
        String index();
        @Nullable
        String type();
        @Nullable
        String id();
    }

    @Shim("org.elasticsearch.action.delete.DeleteRequest")
    public interface DeleteRequest extends ActionRequest {
        @Nullable
        String index();
        @Nullable
        String type();
        @Nullable
        String id();
    }

    @Shim("org.elasticsearch.action.search.SearchRequest")
    public interface SearchRequest extends ActionRequest {
        @Nullable
        String /*@Nullable*/ [] indices();
        @Nullable
        String /*@Nullable*/ [] types();
    }

    @Shim("org.elasticsearch.common.bytes.BytesReference")
    public interface BytesReference {
        @Nullable
        String toUtf8();
    }

    @Pointcut(className = "org.elasticsearch.action.ActionRequestBuilder", methodName = "get",
            methodParameterTypes = {}, nestingGroup = "elasticsearch",
            timerName = "elasticsearch execute", suppressionKey = "wait-on-future")
    public static class ExecuteAdvice {
        private static final TimerName timerName = Agent.getTimerName(ExecuteAdvice.class);
        @OnBefore
        public static @Nullable QueryEntry onBefore(ThreadContext context,
                @BindReceiver ActionRequestBuilder actionRequestBuilder) {
            return context.startQueryEntry(QUERY_TYPE, getQueryText(actionRequestBuilder),
                    getQueryMessageSupplier(actionRequestBuilder), timerName);
        }
        @OnReturn
        public static void onReturn(@BindTraveler @Nullable QueryEntry queryEntry) {
            if (queryEntry != null) {
                queryEntry.endWithLocationStackTrace(stackTraceThresholdMillis, MILLISECONDS);
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable QueryEntry queryEntry) {
            if (queryEntry != null) {
                queryEntry.endWithError(t);
            }
        }
    }

    @Pointcut(className = "org.elasticsearch.action.ActionRequestBuilder", methodName = "execute",
            methodParameterTypes = {}, nestingGroup = "elasticsearch",
            timerName = "elasticsearch execute")
    public static class ExecuteAsyncAdvice {
        private static final TimerName timerName = Agent.getTimerName(ExecuteAsyncAdvice.class);
        @OnBefore
        public static @Nullable AsyncQueryEntry onBefore(ThreadContext context,
                @BindReceiver ActionRequestBuilder actionRequestBuilder) {
            return context.startAsyncQueryEntry(QUERY_TYPE, getQueryText(actionRequestBuilder),
                    getQueryMessageSupplier(actionRequestBuilder), timerName);
        }
        @OnReturn
        public static void onReturn(@BindReturn @Nullable ListenableActionFutureMixin future,
                @BindTraveler @Nullable AsyncQueryEntry asyncQueryEntry) {
            if (asyncQueryEntry == null) {
                return;
            }
            asyncQueryEntry.stopSyncTimer();
            if (future == null) {
                asyncQueryEntry.end();
                return;
            }
            // to prevent race condition, setting async query entry before getting completed status,
            // and the converse is done when getting async query entry
            // ok if end() happens to get called twice
            future.glowroot$setAsyncQueryEntry(asyncQueryEntry);
            if (future.glowroot$isCompleted()) {
                // ListenableActionFuture completed really fast, prior to @OnReturn
                Throwable exception = future.glowroot$getException();
                if (exception == null) {
                    asyncQueryEntry.end();
                } else {
                    asyncQueryEntry.endWithError(exception);
                }
                return;
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable AsyncQueryEntry asyncQueryEntry) {
            if (asyncQueryEntry != null) {
                asyncQueryEntry.stopSyncTimer();
                asyncQueryEntry.endWithError(t);
            }
        }
    }

    @Pointcut(className = "org.elasticsearch.action.search.SearchRequestBuilder",
            methodName = "setQuery",
            methodParameterTypes = {"org.elasticsearch.index.query.QueryBuilder"},
            nestingGroup = "elasticsearch")
    public static class SetQueryAdvice {
        @OnAfter
        public static void onAfter(@BindReceiver SearchRequestBuilder searchRequestBuilder,
                @BindParameter @Nullable Object queryBuilder) {
            searchRequestBuilder.glowroot$setQueryBuilder(queryBuilder);
        }
    }

    private static String getQueryText(ActionRequestBuilder actionRequestBuilder) {
        ActionRequest actionRequest = actionRequestBuilder.glowroot$request();
        if (actionRequest instanceof IndexRequest) {
            IndexRequest request = (IndexRequest) actionRequest;
            return "PUT " + request.index() + '/' + request.type();
        } else if (actionRequest instanceof GetRequest) {
            GetRequest request = (GetRequest) actionRequest;
            return "GET " + request.index() + '/' + request.type();
        } else if (actionRequest instanceof UpdateRequest) {
            UpdateRequest request = (UpdateRequest) actionRequest;
            return "PUT " + request.index() + '/' + request.type();
        } else if (actionRequest instanceof DeleteRequest) {
            DeleteRequest request = (DeleteRequest) actionRequest;
            return "DELETE " + request.index() + '/' + request.type();
        } else if (actionRequest instanceof SearchRequest) {
            SearchRequest request = (SearchRequest) actionRequest;
            StringBuilder sb = new StringBuilder("SEARCH ");
            @Nullable
            String[] indices = request.indices();
            @Nullable
            String[] types = request.types();
            if (indices != null && indices.length > 0) {
                if (types != null && types.length > 0) {
                    appendTo(sb, indices);
                    sb.append('/');
                    appendTo(sb, types);
                } else {
                    appendTo(sb, indices);
                }
            } else {
                if (types != null && types.length > 0) {
                    sb.append("_any/");
                    appendTo(sb, types);
                } else {
                    sb.append('/');
                }
            }
            Object source =
                    ((SearchRequestBuilder) actionRequestBuilder).glowroot$getQueryBuilder();
            if (source == null) {
                return sb.toString();
            } else if (source instanceof BytesReference) {
                sb.append(' ');
                sb.append(((BytesReference) source).toUtf8());
                return sb.toString();
            } else {
                sb.append(' ');
                sb.append(source);
                return sb.toString();
            }
        } else if (actionRequest == null) {
            return "(action request was null)";
        } else {
            return actionRequest.getClass().getName();
        }
    }

    private static QueryMessageSupplier getQueryMessageSupplier(
            ActionRequestBuilder actionRequestBuilder) {
        ActionRequest actionRequest = actionRequestBuilder.glowroot$request();
        if (actionRequest instanceof IndexRequest) {
            return QueryMessageSupplier.create(Constants.QUERY_MESSAGE_PREFIX);
        } else if (actionRequest instanceof GetRequest) {
            GetRequest request = (GetRequest) actionRequest;
            return new QueryMessageSupplierWithId(request.id());
        } else if (actionRequest instanceof UpdateRequest) {
            UpdateRequest request = (UpdateRequest) actionRequest;
            return new QueryMessageSupplierWithId(request.id());
        } else if (actionRequest instanceof DeleteRequest) {
            DeleteRequest request = (DeleteRequest) actionRequest;
            return new QueryMessageSupplierWithId(request.id());
        } else if (actionRequest instanceof SearchRequest) {
            return QueryMessageSupplier.create(Constants.QUERY_MESSAGE_PREFIX);
        } else {
            return QueryMessageSupplier.create(Constants.QUERY_MESSAGE_PREFIX);
        }
    }

    private static void appendTo(StringBuilder sb, @Nullable String[] values) {
        boolean first = true;
        for (String value : values) {
            if (!first) {
                sb.append(',');
            }
            sb.append(value);
            first = false;
        }
    }
}
