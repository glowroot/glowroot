/**
 * Copyright 2012-2013 the original author or authors.
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
package io.informant.local.store;

import io.informant.local.store.Schemas.Column;

import java.sql.SQLException;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class TraceSnapshotSchema {

    private TraceSnapshotSchema() {}

    static void upgradeTraceSnapshotTable(DataSource dataSource) throws SQLException {
        if (!dataSource.tableExists("trace_snapshot")) {
            return;
        }
        // 'description' column renamed to 'headline'
        for (Column column : dataSource.getColumns("trace_snapshot")) {
            if (column.getName().equals("description")) {
                dataSource.execute("alter table trace_snapshot alter column description rename to"
                        + " headline");
                break;
            }
        }
    }
}
