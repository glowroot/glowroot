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
package org.glowroot.common.live;

import java.util.List;

import javax.annotation.Nullable;

import org.immutables.value.Value;

// TODO improve AllThreads/OneThread class names
public interface LiveThreadDumpService {

    AllThreads getAllThreads();

    @Value.Immutable
    public interface AllThreads {
        List<OneThread> matchedThreads();
        List<OneThread> unmatchedThreads();
        @Nullable
        OneThread currentThread();
    }

    @Value.Immutable
    public interface OneThread {
        String name();
        String state();
        @Nullable
        String lockName();
        List<StackTraceElement> stackTrace();

        @Nullable
        String transactionType();
        @Nullable
        String transactionName();
        @Nullable
        Long transactionDuration();
        @Nullable
        String traceId();
    }

    public class LiveThreadDumpServiceNop implements LiveThreadDumpService {

        @Override
        public AllThreads getAllThreads() {
            return ImmutableAllThreads.builder().build();
        }
    }
}
