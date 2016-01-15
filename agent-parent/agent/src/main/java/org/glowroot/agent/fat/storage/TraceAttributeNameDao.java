/*
 * Copyright 2015-2016 the original author or authors.
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
package org.glowroot.agent.fat.storage;

import java.util.List;

import com.google.common.collect.ImmutableList;

import org.glowroot.agent.fat.storage.util.DataSource;
import org.glowroot.agent.fat.storage.util.ImmutableColumn;
import org.glowroot.agent.fat.storage.util.Schemas.Column;
import org.glowroot.agent.fat.storage.util.Schemas.ColumnType;

class TraceAttributeNameDao {

    private static final ImmutableList<Column> columns = ImmutableList.<Column>of(
            ImmutableColumn.of("trace_attribute_name", ColumnType.VARCHAR),
            ImmutableColumn.of("last_capture_time", ColumnType.BIGINT));

    private final DataSource dataSource;

    private final Object lock = new Object();

    TraceAttributeNameDao(DataSource dataSource) throws Exception {
        this.dataSource = dataSource;
        dataSource.syncTable("trace_attribute_name", columns);
    }

    public List<String> readTraceAttributeNames() throws Exception {
        return dataSource
                .queryForStringList("select trace_attribute_name from trace_attribute_name");
    }

    void updateLastCaptureTime(String traceAttributeName, long captureTime) throws Exception {
        synchronized (lock) {
            int updateCount = dataSource.update("update trace_attribute_name"
                    + " set last_capture_time = ? where trace_attribute_name = ?", captureTime,
                    traceAttributeName);
            if (updateCount == 0) {
                dataSource.update("insert into trace_attribute_name (trace_attribute_name,"
                        + " last_capture_time) values (?, ?)", traceAttributeName, captureTime);
            }
        }
    }

    void deleteAll() throws Exception {
        dataSource.update("truncate table trace_attribute_name");
    }

    void deleteBefore(long captureTime) throws Exception {
        dataSource.update("delete from trace_attribute_name where last_capture_time < ?",
                captureTime);
    }
}
