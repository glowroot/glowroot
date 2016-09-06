/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.agent.plugin.api;

public interface OptionalThreadContext extends ThreadContext {

    /**
     * If there is no active transaction, a new transaction is started.
     * 
     * If there is already an active transaction, this method acts the same as
     * {@link #startTraceEntry(MessageSupplier, TimerName)} (the transaction name and type are not
     * modified on the existing transaction).
     */
    TraceEntry startTransaction(String transactionType, String transactionName,
            MessageSupplier messageSupplier, TimerName timerName);
}
