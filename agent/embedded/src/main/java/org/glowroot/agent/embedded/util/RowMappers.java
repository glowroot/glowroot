/*
 * Copyright 2014-2018 the original author or authors.
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
package org.glowroot.agent.embedded.util;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.common.live.LiveTraceRepository.Existence;

public class RowMappers {

    private RowMappers() {}

    public static @Nullable Long getLong(ResultSet resultSet, int columnIndex) throws SQLException {
        long value = resultSet.getLong(columnIndex);
        if (value == 0 && resultSet.wasNull()) {
            return null;
        } else {
            return value;
        }
    }

    public static void setLong(PreparedStatement preparedStatement, int columnIndex,
            @Nullable Long value) throws SQLException {
        if (value == null) {
            preparedStatement.setNull(columnIndex, Types.BIGINT);
        } else {
            preparedStatement.setLong(columnIndex, value);
        }
    }

    public static void setBytes(PreparedStatement preparedStatement, int columnIndex,
            byte /*@Nullable*/ [] value) throws SQLException {
        if (value == null) {
            preparedStatement.setNull(columnIndex, Types.VARBINARY);
        } else {
            preparedStatement.setBytes(columnIndex, value);
        }
    }

    public static Existence getExistence(ResultSet resultSet, int columnIndex,
            CappedDatabase cappedDatabase) throws SQLException {
        long cappedId = resultSet.getLong(columnIndex);
        if (cappedId == 0 && resultSet.wasNull()) {
            return Existence.NO;
        }
        if (cappedDatabase.isExpired(cappedId)) {
            return Existence.EXPIRED;
        } else {
            return Existence.YES;
        }
    }
}
