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
package org.glowroot.agent.plugin.mongodb;

import org.glowroot.agent.plugin.api.QueryEntry;
import org.glowroot.agent.plugin.api.Timer;
import org.glowroot.agent.plugin.api.checker.Nullable;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindReturn;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.Mixin;
import org.glowroot.agent.plugin.api.weaving.OnAfter;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.mongodb.MongoCursorAspect.MongoCursorMixin;

public class MongoIterableAspect {

    // the field and method names are verbose since they will be mixed in to existing classes
    @Mixin({"com.mongodb.client.FindIterable", "com.mongodb.client.AggregateIterable"})
    public static class MongoIterableImpl implements MongoIterableMixin {

        // does not need to be volatile, app/framework must provide visibility of MongoIterables if
        // used across threads and this can piggyback
        private transient @Nullable QueryEntry glowroot$queryEntry;

        @Override
        public @Nullable QueryEntry glowroot$getQueryEntry() {
            return glowroot$queryEntry;
        }

        @Override
        public void glowroot$setQueryEntry(@Nullable QueryEntry queryEntry) {
            glowroot$queryEntry = queryEntry;
        }
    }

    // the method names are verbose since they will be mixed in to existing classes
    public interface MongoIterableMixin {

        @Nullable
        QueryEntry glowroot$getQueryEntry();

        void glowroot$setQueryEntry(@Nullable QueryEntry queryEntry);
    }

    @Pointcut(className = "com.mongodb.client.FindIterable", methodName = "*",
            methodParameterTypes = {".."}, methodReturnType = "com.mongodb.client.FindIterable")
    public static class MapAdvice {

        @OnReturn
        public static void onReturn(@BindReturn @Nullable MongoIterableMixin newMongoIterable,
                @BindReceiver MongoIterableMixin mongoIterable) {
            if (newMongoIterable != null) {
                newMongoIterable.glowroot$setQueryEntry(mongoIterable.glowroot$getQueryEntry());
            }
        }
    }

    @Pointcut(className = "com.mongodb.client.MongoIterable",
            subTypeRestriction = "com.mongodb.client.FindIterable"
                    + "|com.mongodb.client.AggregateIterable",
            methodName = "first", methodParameterTypes = {}, nestingGroup = "mongodb")
    public static class FirstAdvice {

        @OnBefore
        public static @Nullable Timer onBefore(@BindReceiver MongoIterableMixin mongoIterable) {
            QueryEntry queryEntry = mongoIterable.glowroot$getQueryEntry();
            return queryEntry == null ? null : queryEntry.extend();
        }

        @OnReturn
        public static void onReturn(@BindReturn @Nullable Object document,
                @BindReceiver MongoIterableMixin mongoIterable) {
            QueryEntry queryEntry = mongoIterable.glowroot$getQueryEntry();
            if (queryEntry == null) {
                return;
            }
            if (document != null) {
                queryEntry.incrementCurrRow();
            } else {
                queryEntry.rowNavigationAttempted();
            }
        }

        @OnAfter
        public static void onAfter(@BindTraveler @Nullable Timer timer) {
            if (timer != null) {
                timer.stop();
            }
        }
    }

    @Pointcut(className = "com.mongodb.client.MongoIterable",
            subTypeRestriction = "com.mongodb.client.FindIterable"
                    + "|com.mongodb.client.AggregateIterable",
            methodName = "iterator", methodParameterTypes = {},
            methodReturnType = "com.mongodb.client.MongoCursor", nestingGroup = "mongodb")
    public static class IteratorAdvice {

        @OnReturn
        public static void onReturn(@BindReturn @Nullable MongoCursorMixin mongoCursor,
                @BindReceiver MongoIterableMixin mongoIterable) {
            if (mongoCursor != null) {
                mongoCursor.glowroot$setQueryEntry(mongoIterable.glowroot$getQueryEntry());
            }
        }
    }
}
