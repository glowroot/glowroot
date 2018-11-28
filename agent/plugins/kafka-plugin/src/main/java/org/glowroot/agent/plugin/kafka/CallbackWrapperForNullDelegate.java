/*
 * Copyright 2018 the original author or authors.
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
package org.glowroot.agent.plugin.kafka;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.RecordMetadata;

import org.glowroot.agent.plugin.api.AsyncTraceEntry;
import org.glowroot.agent.plugin.api.checker.Nullable;

public class CallbackWrapperForNullDelegate implements Callback {

    private final AsyncTraceEntry asyncTraceEntry;

    public CallbackWrapperForNullDelegate(AsyncTraceEntry asyncTraceEntry) {
        this.asyncTraceEntry = asyncTraceEntry;
    }

    @Override
    public void onCompletion(@Nullable RecordMetadata metadata, @Nullable Exception exception) {
        if (exception == null) {
            asyncTraceEntry.end();
        } else {
            asyncTraceEntry.endWithError(exception);
        }
    }
}
