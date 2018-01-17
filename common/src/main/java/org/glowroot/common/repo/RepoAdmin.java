/*
 * Copyright 2015-2018 the original author or authors.
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
package org.glowroot.common.repo;

import java.util.List;

import org.immutables.value.Value;

public interface RepoAdmin {

    void defragH2Data() throws Exception;

    void compactH2Data() throws Exception;

    long getH2DataFileSize();

    List<H2Table> analyzeH2DiskSpace() throws Exception;

    TraceCounts analyzeTraceCounts() throws Exception;

    void deleteAllData() throws Exception;

    void resizeIfNeeded() throws Exception;

    @Value.Immutable
    interface H2Table {
        String name();
        long bytes();
        long rows();
    }

    @Value.Immutable
    interface TraceCounts {
        List<TraceOverallCount> overallCounts();
        List<TraceCount> counts();
    }

    @Value.Immutable
    interface TraceOverallCount {
        String transactionType();
        long count();
        long errorCount();
    }

    @Value.Immutable
    interface TraceCount {
        String transactionType();
        String transactionName();
        long count();
        long errorCount();
    }
}
