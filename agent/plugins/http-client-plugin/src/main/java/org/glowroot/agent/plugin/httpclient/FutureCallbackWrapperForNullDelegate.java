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
package org.glowroot.agent.plugin.httpclient;

import org.apache.http.concurrent.FutureCallback;

import org.glowroot.agent.plugin.api.AsyncTraceEntry;

public class FutureCallbackWrapperForNullDelegate<T> implements FutureCallback<T> {

    private final AsyncTraceEntry asyncTraceEntry;

    public FutureCallbackWrapperForNullDelegate(AsyncTraceEntry asyncTraceEntry) {
        this.asyncTraceEntry = asyncTraceEntry;
    }

    @Override
    public void completed(T result) {
        asyncTraceEntry.end();
    }

    @Override
    public void failed(Exception exception) {
        asyncTraceEntry.endWithError(exception);
    }

    @Override
    public void cancelled() {
        asyncTraceEntry.end();
    }
}
