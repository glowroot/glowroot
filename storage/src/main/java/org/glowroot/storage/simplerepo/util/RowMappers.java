/*
 * Copyright 2014-2015 the original author or authors.
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
package org.glowroot.storage.simplerepo.util;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import javax.annotation.Nullable;

import org.glowroot.common.live.LiveTraceRepository.Existence;
import org.glowroot.common.util.NotAvailableAware;

public class RowMappers {

    private RowMappers() {}

    public static @Nullable Long getLong(ResultSet resultSet, int columnIndex) throws SQLException {
        long value = resultSet.getLong(columnIndex);
        if (resultSet.wasNull()) {
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

    public static double getNotAvailableAwareDouble(ResultSet resultSet, int columnIndex)
            throws SQLException {
        double value = resultSet.getDouble(columnIndex);
        if (resultSet.wasNull()) {
            return NotAvailableAware.NA;
        } else {
            return value;
        }
    }

    public static void setNotAvailableAwareDouble(PreparedStatement preparedStatement,
            int columnIndex, double value) throws SQLException {
        if (NotAvailableAware.isNA(value)) {
            preparedStatement.setNull(columnIndex, Types.BIGINT);
        } else {
            preparedStatement.setDouble(columnIndex, value);
        }
    }

    public static Existence getExistence(ResultSet resultSet, int columnIndex,
            CappedDatabase cappedDatabase) throws SQLException {
        long cappedId = resultSet.getLong(columnIndex);
        if (resultSet.wasNull()) {
            return Existence.NO;
        }
        if (cappedDatabase.isExpired(cappedId)) {
            return Existence.EXPIRED;
        } else {
            return Existence.YES;
        }
    }
}
