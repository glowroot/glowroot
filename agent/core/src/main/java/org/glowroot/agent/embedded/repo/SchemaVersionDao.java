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
package org.glowroot.agent.embedded.repo;

import java.sql.SQLException;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import org.glowroot.agent.embedded.util.DataSource;
import org.glowroot.agent.embedded.util.ImmutableColumn;
import org.glowroot.agent.embedded.util.Schemas.Column;
import org.glowroot.agent.embedded.util.Schemas.ColumnType;

class SchemaVersionDao {

    private static final ImmutableList<Column> columns =
            ImmutableList.<Column>of(ImmutableColumn.of("schema_version", ColumnType.BIGINT));

    private final DataSource dataSource;

    SchemaVersionDao(DataSource dataSource) throws SQLException {
        this.dataSource = dataSource;
        dataSource.syncTable("schema_version", columns);
    }

    @Nullable
    Integer getSchemaVersion() throws SQLException {
        Long schemaVersion =
                dataSource.queryForOptionalLong("select schema_version from schema_version");
        if (schemaVersion != null) {
            return schemaVersion.intValue();
        }
        if (dataSource.tableExists("trace")) {
            // this is glowroot prior to when the schema_version table was introduced in 0.9.18
            return 1;
        }
        // new installation
        return null;
    }

    void updateSchemaVersion(int schemaVersion) throws Exception {
        int updated =
                dataSource.update("update schema_version set schema_version = ?", schemaVersion);
        if (updated == 0) {
            dataSource.update("insert into schema_version (schema_version) values (?)",
                    schemaVersion);
        }
    }
}
