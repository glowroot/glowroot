/*
 * Copyright 2014 the original author or authors.
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
package org.glowroot.local.store;

import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.markers.UsedByJsonBinding;

@UsedByJsonBinding
public class ErrorCount {

    @Nullable
    private final String transactionType;
    @Nullable
    private final String transactionName;
    private final long errorCount;
    private final long transactionCount;

    ErrorCount(@Nullable String transactionType, @Nullable String transactionName, long errorCount,
            long transactionCount) {
        this.transactionType = transactionType;
        this.transactionName = transactionName;
        this.errorCount = errorCount;
        this.transactionCount = transactionCount;
    }

    @Nullable
    public String getTransactionType() {
        return transactionType;
    }

    @Nullable
    public String getTransactionName() {
        return transactionName;
    }

    public long getErrorCount() {
        return errorCount;
    }

    public long getTransactionCount() {
        return transactionCount;
    }
}
